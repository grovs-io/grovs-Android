package io.grovs.service

import java.io.IOException

/**
 * The backend answered with the non-2xx HTTP [code]. Callers decide what a code means for their
 * endpoint: the same status can be a permanent refusal on one and a fixable condition on another.
 */
internal class HttpStatusException(val code: Int, message: String) : IOException(message)
