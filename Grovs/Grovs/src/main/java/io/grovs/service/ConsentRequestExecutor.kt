package io.grovs.service

import io.grovs.handlers.ConsentConfiguration
import io.grovs.handlers.ConsentController
import io.grovs.handlers.ConsentRevokedException
import io.grovs.handlers.ConsentToken
import io.grovs.handlers.RevocationReason
import io.grovs.handlers.currentConsentToken
import io.grovs.handlers.runOperation
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import io.grovs.utils.GVRetryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * The single path every Retrofit attempt of one [GrovsService] takes (plan §3.3).
 *
 * - **Ownership.** Bound to the [configuration] current when the service was built. It never
 *   acquires consent for another one, so a service kept past a later `configure` stays retired.
 * - **Admission.** A request runs under the calling operation's token ([currentConsentToken]).
 *   A direct call with no operation (the notification view models, or a caller not yet migrated to
 *   operations) is admitted now for [configuration]. A revoked or foreign parent is rejected; fresh
 *   consent is never acquired on its behalf.
 * - **Attempts.** Each attempt, request construction and response parsing included, runs as a child
 *   operation registered with the controller before Retrofit is invoked, so revocation cancels it
 *   (and with it the OkHttp call), and a result that arrives after revocation is dropped by the
 *   generation check in [runOperation].
 * - **Retries.** One token per call. A retry is decided against that token, and the wait before it
 *   is itself a registered operation that revocation cancels. Consent rejection and cancellation
 *   are terminal; genuine transport failures keep the existing schedule while consent holds.
 * - **Transport gate.** [callFactory] tags each OkHttp request with the attempt's token and
 *   [gateInterceptor] refuses it, before any header or device detail is built, if the call was
 *   cancelled or the token is no longer current. Cancellation is per call; nothing here cancels
 *   other calls on the client.
 *
 * Rejections are thrown as [ConsentRevokedException]; explicit API boundaries map it to their
 * existing error shape, and everything else lets it end the operation as a cancellation.
 */
internal class ConsentRequestExecutor(
    private val consent: ConsentController,
    val configuration: ConsentConfiguration = consent.currentConfiguration,
) {
    /**
     * The token a request runs under: the calling operation's, or one admitted now for
     * [configuration] when the caller has none. Throws [ConsentRevokedException] otherwise.
     */
    suspend fun admit(): ConsentToken {
        val inherited = currentConsentToken()
        if (inherited != null) {
            if (inherited.configuration !== configuration) {
                throw ConsentRevokedException(RevocationReason.CONFIGURATION_RETIRED, inherited)
            }
            consent.ensureCurrent(inherited)
            return inherited
        }
        return consent.tryAcquire(configuration) ?: throw ConsentRevokedException(
            if (consent.currentConfiguration === configuration) {
                RevocationReason.NOT_ADMITTED
            } else {
                RevocationReason.CONFIGURATION_RETIRED
            },
            token = null,
        )
    }

    /**
     * Runs one attempt under [token] as a registered child operation. A failure that coincides with
     * revocation (a cancelled call, or one refused by [gateInterceptor], surfaces as an I/O error)
     * is reported as the revocation, never as a retryable failure.
     */
    suspend fun <T> attempt(token: ConsentToken, block: suspend () -> T): T {
        try {
            return consent.runOperation(token) {
                withContext(attemptToken.asContextElement(token)) { block() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!consent.isCurrent(token)) throw consent.revocationFor(token).also { it.initCause(e) }
            throw e
        }
    }

    /** One admitted attempt. */
    suspend fun <T> single(block: suspend () -> T): T = attempt(admit(), block)

    /**
     * An admitted request retried with the existing schedule: [block] returns the result, or null
     * (or throws a non-cancellation exception) to retry after the wait for that retry count.
     */
    suspend fun <T : Any> retrying(label: String, block: suspend () -> T?): T {
        val token = admit()
        var retryCount = 0L
        while (true) {
            val result = try {
                attempt(token, block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogger.instance.log(LogLevel.INFO, "$label - Failed. ${e.message}")
                null
            }
            if (result != null) return result
            awaitRetry(token, retryCount)
            retryCount++
        }
    }

    /**
     * A flow that admits one token when collected (not when built) and retries failed attempts with
     * the existing schedule, emitting [GVRetryResult.Retrying] before each wait. [block] returns the
     * value to emit, or null to complete without emitting.
     */
    fun <T : Any> retryingFlow(label: String, block: suspend () -> GVRetryResult<T>?): Flow<GVRetryResult<T>> = flow {
        val token = admit()
        val attempts = flow {
            attempt(token, block)?.let { emit(it) }
        }.retryWhen { cause, attempt ->
            // Consent rejections and every other cancellation end the flow.
            if (cause is CancellationException) return@retryWhen false
            if (!consent.isCurrent(token)) throw consent.revocationFor(token).also { it.initCause(cause) }
            DebugLogger.instance.log(LogLevel.INFO, "$label - Failed. Exception: ${cause.message}")
            emit(GVRetryResult.Retrying(attempt.toInt()))
            awaitRetry(token, attempt)
            true
        }
        emitAll(attempts)
    }

    /** The wait before retry [retryCount], as a registered operation of [token]. */
    private suspend fun awaitRetry(token: ConsentToken, retryCount: Long) {
        consent.runOperation(token) { delay(retryDelayMs(retryCount)) }
    }

    /**
     * The Retrofit call factory: tags each request with the attempt's token. Runs on the calling
     * coroutine's thread, synchronously inside the suspend call, where [attemptToken] is set.
     */
    fun callFactory(client: OkHttpClient): Call.Factory = Call.Factory { request ->
        val token = attemptToken.get()
        client.newCall(if (token == null) request else request.newBuilder().tag(ConsentToken::class.java, token).build())
    }

    /**
     * First application interceptor. Refuses, before the header interceptor builds anything, a call
     * that was cancelled while queued in OkHttp, a request that did not come through [attempt], and
     * one whose token was revoked after the attempt started.
     */
    val gateInterceptor: Interceptor = Interceptor { chain ->
        val token = chain.request().tag(ConsentToken::class.java)
        when {
            chain.call().isCanceled() -> throw IOException("Canceled")
            token == null -> throw IOException("Grovs request outside a consent operation")
            !consent.isCurrent(token) -> throw IOException("Grovs SDK consent withdrawn")
        }
        chain.proceed(chain.request())
    }

    internal companion object {
        /** The token of the attempt running on this thread; read by [callFactory]. */
        private val attemptToken = ThreadLocal<ConsentToken?>()

        fun retryDelayMs(retryCount: Long): Long =
            if (retryCount < GrovsService.EAGER_RETRY_COUNT) GrovsService.EAGER_RETRY_FALLBACK_TIME else GrovsService.RETRY_FALLBACK_TIME
    }
}
