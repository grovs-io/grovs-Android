package io.grovs.inapp.model

/** Times are epoch milliseconds; days are 1 = Monday to 7 = Sunday; from and to are "HH:MM" local. */
internal data class InAppSchedule(val startAt: Long, val endAt: Long?, val days: List<Int>?, val from: String?, val to: String?)
