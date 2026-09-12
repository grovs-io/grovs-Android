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
import io.grovs.utils.LSResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import okhttp3.Interceptor
import java.io.IOException

/**
 * The single path every Retrofit attempt of one [GrovsService] takes.
 *
 * - **Ownership.** Bound to the [configuration] current when the service was built. It never
 *   acquires consent for another one, so a service kept past a later `configure` stays retired.
 * - **Admission.** A request runs under the calling operation's token ([currentConsentToken]).
 *   A direct call with no operation is admitted now for [configuration]. A revoked or foreign
 *   parent is rejected; fresh consent is never acquired on its behalf.
 * - **Attempts.** Each attempt, request construction and response parsing included, runs as a child
 *   operation registered with the controller before Retrofit is invoked, so revocation cancels it
 *   (and with it the OkHttp call), and a result that arrives after revocation is dropped by the
 *   generation check in [runOperation].
 * - **Retries.** One token per call. A retry is decided against that token, and the wait before it
 *   is itself a registered operation that revocation cancels. Consent rejection and cancellation
 *   are terminal; transport failures are retried on the standard schedule while consent holds.
 * - **Transport gate.** Every [io.grovs.api.GrovsApi] method takes the attempt's token as its
 *   Retrofit `@Tag`, and [gateInterceptor] refuses the request, before any header or device detail
 *   is built, if the call was cancelled or the token is no longer current. Cancellation is per
 *   call; nothing here cancels other calls on the client.
 *
 * Rejections are thrown as [ConsentRevokedException]; explicit API boundaries map it to the
 * method's public error, and everything else lets it end the operation as a cancellation.
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
     * Runs one attempt under [token] as a registered child operation. [block] receives [token] and
     * passes it to the API call as the request tag. A failure that coincides with revocation (a
     * cancelled call, or one refused by [gateInterceptor], surfaces as an I/O error) is reported as
     * the revocation, never as a retryable failure.
     */
    suspend fun <T> attempt(token: ConsentToken, block: suspend (ConsentToken) -> T): T {
        try {
            return consent.runOperation(token) { block(token) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!consent.isCurrent(token)) throw consent.revocationFor(token).also { it.initCause(e) }
            throw e
        }
    }

    /** One admitted attempt. */
    suspend fun <T> single(block: suspend (ConsentToken) -> T): T = attempt(admit(), block)

    /**
     * An admitted request, retried on the standard schedule. [block] returns its result, or throws
     * a retryable failure to spend one attempt.
     *
     * A spent budget returns [LSResult.Error]. It must never throw: callers such as
     * `GrovsManager.getDataForDevice`, `ClipboardHandler.sendMatch` and the notification view
     * models have no handler for a network failure, because this helper could not produce one
     * before. Consent rejection and every other cancellation still propagate untouched.
     */
    suspend fun <T : Any> retrying(label: String, block: suspend (ConsentToken) -> LSResult<T>): LSResult<T> {
        val token = admit()
        var retryCount = 0L
        while (true) {
            try {
                return attempt(token, block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogger.instance.log(LogLevel.INFO, "$label - Failed. ${e.message}")
                if (retryCount >= GrovsService.MAX_ATTEMPTS - 1) {
                    DebugLogger.instance.log(LogLevel.ERROR, "$label - Giving up after ${GrovsService.MAX_ATTEMPTS} attempts.")
                    return LSResult.Error(e)
                }
                awaitRetry(token, retryCount)
                retryCount++
            }
        }
    }

    /**
     * A flow that admits one token when collected (not when built) and retries failed attempts on
     * the standard schedule, emitting [GVRetryResult.Retrying] before each wait. [block] returns the
     * value to emit, or null to complete without emitting.
     */
    fun <T : Any> retryingFlow(
        label: String,
        block: suspend (ConsentToken) -> GVRetryResult<T>?,
    ): Flow<GVRetryResult<T>> = flow {
        val token = admit()
        val attempts = flow {
            attempt(token, block)?.let { emit(it) }
        }.retryWhen { cause, attempt ->
            // Consent rejections and every other cancellation end the flow.
            if (cause is CancellationException) return@retryWhen false
            if (!consent.isCurrent(token)) throw consent.revocationFor(token).also { it.initCause(cause) }
            DebugLogger.instance.log(LogLevel.INFO, "$label - Failed. Exception: ${cause.message}")
            // attempt is zero-based, so this allows MAX_ATTEMPTS requests. Returning false rethrows
            // the cause, which the catch below turns into a terminal Error.
            if (attempt >= GrovsService.MAX_ATTEMPTS - 1) return@retryWhen false
            emit(GVRetryResult.Retrying(attempt.toInt()))
            awaitRetry(token, attempt)
            true
        }.catch { cause ->
            // A consent rejection is a CancellationException and must end the flow, never become a
            // result. Anything else becomes the terminal Error the collector expects, so the caller
            // records a failure instead of being left at Retrying forever.
            if (cause is CancellationException) throw cause
            DebugLogger.instance.log(LogLevel.ERROR, "$label - Giving up after ${GrovsService.MAX_ATTEMPTS} attempts.")
            emit(GVRetryResult.Error(cause as? Exception ?: IOException(cause.message)))
        }
        emitAll(attempts)
    }

    /** The wait before retry [retryCount], as a registered operation of [token]. */
    private suspend fun awaitRetry(token: ConsentToken, retryCount: Long) {
        consent.runOperation(token) { delay(retryDelayMs(retryCount)) }
    }

    /**
     * First application interceptor. Refuses, before the header interceptor builds anything, a call
     * that was cancelled while queued in OkHttp, a request without a consent tag, and one whose
     * token was revoked after the attempt started.
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
        /** First retry wait before doubling per retry count. Overridden in tests that wait on it in real time. */
        val defaultBaseDelayMs: () -> Long = { GrovsService.RETRY_BASE_DELAY_MS }

        @Volatile
        internal var retryBaseDelayMs: () -> Long = defaultBaseDelayMs

        /** Jitter keeps recovering clients from syncing up. Overridden in tests for exact timing. */
        val defaultJitterMs: () -> Long = { (0L..1000L).random() }

        @Volatile
        internal var retryJitterMs: () -> Long = defaultJitterMs

        /** 2s, 4s, 8s, plus jitter. */
        fun retryDelayMs(retryCount: Long): Long =
            (retryBaseDelayMs() shl retryCount.toInt()) + retryJitterMs()
    }
}
