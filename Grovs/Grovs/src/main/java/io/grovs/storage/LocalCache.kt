package io.grovs.storage

import android.content.Context
import java.time.Instant

class LocalCache(val context: Context) {
    private val preferences = context.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE)

    companion object {
        private const val GROVS_NUMBER_OF_OPENS = "grovs_number_of_opens"
        private const val GROVS_RESIGN_TIMESTAMP = "grovs_resign_timestamp"
        private const val GROVS_LAST_START_TIMESTAMP = "grovs_last_start_timestamp"
    }

    var numberOfOpens:Int
        set(value) {
            val editor = preferences.edit()
            editor.putInt(GROVS_NUMBER_OF_OPENS, value)
            editor.apply()
        }
        get() {
            return preferences.getInt(GROVS_NUMBER_OF_OPENS, 0)
        }

    var resignTimestamp:Instant?
        set(value) {
            val editor = preferences.edit()
            editor.putString(GROVS_RESIGN_TIMESTAMP, value.toString())
            editor.apply()
        }
        get() {
            val string = preferences.getString(GROVS_RESIGN_TIMESTAMP, null)
            string?.let {
                val instant = Instant.parse(it)
                return instant
            } ?: run {
                return null
            }
        }

    var lastStartTimestamp:Instant?
        set(value) {
            val editor = preferences.edit()
            editor.putString(GROVS_LAST_START_TIMESTAMP, value.toString())
            editor.apply()
        }
        get() {
            val string = preferences.getString(GROVS_LAST_START_TIMESTAMP, null)
            string?.let {
                val instant = Instant.parse(it)
                return instant
            } ?: run {
                return null
            }
        }

}