package io.grovs.handlers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/*
 * Operation admission and execution on top of [ConsentController].
 *
 * Acquire a token at the public entry point, before anything is queued, then run the work as a
 * registered child that carries the token in its coroutine context. Revocation cancels that child
 * only: never the caller's job, a host lifecycle scope or its siblings. Nested SDK calls inherit the
 * token and never acquire fresh consent on behalf of a revoked parent.
 */

/** The token of the consent operation this coroutine belongs to, if any. */
internal suspend fun currentConsentToken(): ConsentToken? = currentCoroutineContext()[ConsentToken]

/**
 * Runs [block] as a registered child of the caller under [token] and returns its result.
 *
 * Throws [ConsentRevokedException] if [token] is not current when the child would start, if the
 * token is revoked while [block] runs (the child is cancelled), or if it was revoked by the time
 * [block] returned, so a revoked operation's late result is never handed back. Host cancellation
 * propagates as ordinary cancellation. Exceptions from [block] are rethrown unchanged.
 */
internal suspend fun <T> ConsentController.runOperation(
    token: ConsentToken,
    block: suspend CoroutineScope.() -> T,
): T {
    ensureCurrent(token)
    return coroutineScope {
        val child = async(token, start = CoroutineStart.LAZY) {
            ensureCurrent(token)
            block()
        }
        // Registered before it starts, so revocation can never miss a running body.
        register(token, child) ?: throw revocationFor(token)
        val result = child.await()
        ensureCurrent(token)
        result
    }
}

/**
 * Runs [block] under the token inherited from the calling operation, or under a token acquired now
 * for [configuration] when there is none. An inherited token that is no longer current, or that
 * belongs to another configuration, is rejected. Fresh consent is never acquired for it.
 */
internal suspend fun <T> ConsentController.runOperation(
    configuration: ConsentConfiguration,
    block: suspend CoroutineScope.() -> T,
): T {
    val inherited = currentConsentToken()
    val token = when {
        inherited == null -> tryAcquire(configuration)
            ?: throw ConsentRevokedException(RevocationReason.NOT_ADMITTED, token = null)
        inherited.configuration !== configuration ->
            throw ConsentRevokedException(RevocationReason.CONFIGURATION_RETIRED, inherited)
        else -> inherited
    }
    return runOperation(token, block)
}

/**
 * Launches [block] in [scope] as a registered operation of [token]. Returns null, having started
 * nothing, if [token] is not current. The body re-checks the token when it is dispatched, so work
 * queued before a revocation cannot run even if the cancellation has not been delivered yet.
 */
internal fun ConsentController.launchOperation(
    token: ConsentToken,
    scope: CoroutineScope = token.configuration.scope,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit,
): Job? {
    val job = scope.launch(context + token, start = CoroutineStart.LAZY) {
        ensureCurrent(token)
        block()
    }
    register(token, job) ?: return null
    job.start()
    return job
}

/**
 * Public-entry form of [launchOperation]: acquires consent for [configuration] now, on the
 * caller's thread, before anything is queued. Returns null when consent is not granted, so a call
 * made while disabled is not revived by a later enable.
 */
internal fun ConsentController.launchOperation(
    configuration: ConsentConfiguration,
    scope: CoroutineScope = configuration.scope,
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend CoroutineScope.() -> Unit,
): Job? {
    val token = tryAcquire(configuration) ?: return null
    return launchOperation(token, scope, context, block)
}

/** Runs [block] under this admitted permit and closes it on every path. */
internal inline fun <T> CommitPermit.use(block: (CommitPermit) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
