package io.grovs.utils

sealed class LSResult<out T : Any> {
    data class Success<out T : Any>(val data: T) : LSResult<T>()
    data class Error(val exception: Exception)   : LSResult<Nothing>()

    override fun toString(): String {
        return when (this) {
            is Success<*> -> "Success[data=$data]"
            is Error -> "Error[exception=$exception]"
        }
    }
}

sealed class GVRetryResult<out T : Any> {
    data class Success<out T : Any>(val data: T) : GVRetryResult<T>()
    data class Error(val exception: Exception)   : GVRetryResult<Nothing>()
    data class Retrying(val count: Int) : GVRetryResult<Nothing>()

    override fun toString(): String {
        return when (this) {
            is Success<*> -> "Success[data=$data]"
            is Error -> "Error[exception=$exception]"
            is Retrying -> "Retrying[count=$count]"
        }
    }
}