package io.grovs.handlers

/// Why a request failed once its retry budget was spent. Decides which triggers may retry it on
/// their own.
internal enum class RequestFailure {
    /// The phone had no network. Not the backend's fault, so the window does not grow.
    OFFLINE,
    /// A timeout, a dropped connection, a 429 or a 5xx. Worth trying again later.
    RETRYABLE,
    /// Any other 4xx, for example a bad API key. Waiting does not heal it.
    REJECTED,
}
