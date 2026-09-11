package io.grovs.service

import com.google.gson.Gson
import io.grovs.model.ErrorMessage
import io.grovs.utils.GVRetryResult
import io.grovs.utils.LSResult
import retrofit2.Response
import java.io.IOException

/**
 * A failure worth trying again: 429 and every 5xx. Thrown, because the request executor retries on
 * a throw. The executor turns a spent budget into a terminal result, so this never escapes a
 * service method.
 */
internal class RetryableHttpException(val code: Int, message: String) : IOException(message)

/** Reads the backend's error text without ever throwing, so a bad body cannot look like a retry. */
private fun Response<*>.errorReason(gson: Gson): String? {
    val body = runCatching { errorBody()?.string() }.getOrNull()
    val parsed = runCatching { gson.fromJson(body, ErrorMessage::class.java)?.error }.getOrNull()
    return parsed?.takeIf { it.isNotBlank() } ?: body?.takeIf { it.isNotBlank() }
}

/**
 * The one rule, applied by all three helpers below:
 *
 *     2xx            success
 *     429, 5xx       thrown, so the executor spends an attempt
 *     other non-2xx  terminal, carrying its status
 *
 * Returns null for a 2xx, otherwise the terminal failure to report.
 */
private fun Response<*>.failureOrNull(label: String, gson: Gson): Exception? {
    if (isSuccessful) return null

    val code = code()
    val reason = errorReason(gson)
    val message = "$label - failed ($code)." + (reason?.let { " $it" } ?: "")

    if (code == 429 || code >= 500) throw RetryableHttpException(code, message)
    return HttpStatusException(code, message)
}

private fun Response<*>.emptyBody(label: String) =
    IOException("$label - empty response body (${code()}).")

/** For an endpoint with a payload. A 2xx with no body is terminal, not an endless retry. */
internal fun <T : Any> Response<T>.toResult(label: String, gson: Gson): LSResult<T> {
    failureOrNull(label, gson)?.let { return LSResult.Error(it) }
    return LSResult.Success(body() ?: return LSResult.Error(emptyBody(label)))
}

/** The same, for the two flow-based calls. */
internal fun <T : Any> Response<T>.toRetryResult(label: String, gson: Gson): GVRetryResult<T> {
    failureOrNull(label, gson)?.let { return GVRetryResult.Error(it) }
    return GVRetryResult.Success(body() ?: return GVRetryResult.Error(emptyBody(label)))
}

/**
 * For a `Response<Unit>` endpoint, where the status is the whole answer, so a bodiless 200 is a
 * success rather than a failure.
 */
internal fun Response<Unit>.toStatusResult(label: String, gson: Gson): LSResult<Boolean> {
    failureOrNull(label, gson)?.let { return LSResult.Error(it) }
    return LSResult.Success(true)
}
