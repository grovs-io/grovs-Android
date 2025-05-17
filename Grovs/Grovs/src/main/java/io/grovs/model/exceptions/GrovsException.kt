package io.grovs.model.exceptions

import java.io.PrintWriter
import java.io.StringWriter

fun Throwable.getStackTraceAsString(): String {
    val stringWriter = StringWriter()
    val printWriter = PrintWriter(stringWriter)
    this.printStackTrace(printWriter)
    return stringWriter.toString()
}

enum class GrovsErrorCode {
    LINK_GENERATION_ERROR, SDK_NOT_INITIALIZED, NOTIFICATIONS_ERROR, LINK_DETAILS_ERROR
}

class GrovsException(message: String?) : Exception(message) {
    var errorCode: GrovsErrorCode? = null

    constructor(message: String?, errorCode: GrovsErrorCode) : this(message) {
        this.errorCode = errorCode
    }

    override fun toString(): String {
        return "GrovsException(errorCode=$errorCode, message=${super.message})"
    }
}