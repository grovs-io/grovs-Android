package io.grovs.storage

import android.content.Context
import io.grovs.utils.InstantCompat
import java.time.Instant

class LocalCache(val context: Context) : ILocalCache {
    private val preferences = context.getSharedPreferences(EventsStorage.GROVS_STORAGE, Context.MODE_PRIVATE)

    companion object {
        private const val GROVS_NUMBER_OF_OPENS = "grovs_number_of_opens"
        private const val GROVS_RESIGN_TIMESTAMP = "grovs_resign_timestamp"
        private const val GROVS_LAST_START_TIMESTAMP = "grovs_last_start_timestamp"
    }

    override var numberOfOpens:Int
        set(value) {
            val editor = preferences.edit()
            editor.putInt(GROVS_NUMBER_OF_OPENS, value)
            editor.apply()
        }
        get() {
            return preferences.getInt(GROVS_NUMBER_OF_OPENS, 0)
        }

    override var resignTimestamp:InstantCompat?
        set(value) {
            val editor = preferences.edit()
            editor.putString(GROVS_RESIGN_TIMESTAMP, value.toString())
            editor.apply()
        }
        get() {
            val string = preferences.getString(GROVS_RESIGN_TIMESTAMP, null)
            string?.let {
                val instant = InstantCompat.parse(it)
                return instant
            } ?: run {
                return null
            }
        }

    override var lastStartTimestamp:InstantCompat?
        set(value) {
            val editor = preferences.edit()
            editor.putString(GROVS_LAST_START_TIMESTAMP, value.toString())
            editor.apply()
        }
        get() {
            val string = preferences.getString(GROVS_LAST_START_TIMESTAMP, null)
            string?.let {
                val instant = InstantCompat.parse(it)
                return instant
            } ?: run {
                return null
            }
        }

}