package io.grovs.inapp.model

/** Local time for the engine: the instant shifted by the device's current offset, read as UTC. */
internal object InAppTime {
    private const val DAY = 86_400_000L

    private class Local(val year: Long, val month: Int, val day: Int, val weekday: Int, val hour: Int, val minute: Int)

    /** Days since 1970-01-01 (proleptic Gregorian). */
    fun daysFromCivil(year: Long, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = y.floorDiv(400L)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    fun daysInMonth(year: Long, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0L && year % 100 != 0L) || year % 400 == 0L) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun local(at: Long, offsetMinutes: Int): Local {
        val shifted = at + offsetMinutes * 60_000L
        val days = shifted.floorDiv(DAY)
        val inDay = shifted.mod(DAY)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val shiftedMonth = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * shiftedMonth + 2) / 5 + 1).toInt()
        val month = (if (shiftedMonth < 10) shiftedMonth + 3 else shiftedMonth - 9).toInt()
        val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
        // 1970-01-01 was a Thursday; 1 is Monday and 7 is Sunday.
        val weekday = (days + 3).mod(7L).toInt() + 1
        return Local(year, month, day, weekday, (inDay / 3_600_000).toInt(), ((inDay / 60_000) % 60).toInt())
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')

    private fun key(local: Local): String = "${pad(local.year, 4)}-${pad(local.month.toLong(), 2)}-${pad(local.day.toLong(), 2)}"

    fun dayOf(at: Long, offsetMinutes: Int): String = key(local(at, offsetMinutes))

    /** The date of the Monday that starts the local week holding the instant. */
    fun weekOf(at: Long, offsetMinutes: Int): String {
        val here = local(at, offsetMinutes)
        return key(local(at - (here.weekday - 1) * DAY, offsetMinutes))
    }

    private fun inWindow(from: String?, to: String?, clock: String): Boolean {
        if (from == null || to == null || from == to) return true
        if (from < to) return clock >= from && clock < to
        return clock >= from || clock < to
    }

    fun inSchedule(schedule: InAppSchedule, at: Long, offsetMinutes: Int): Boolean {
        if (at < schedule.startAt) return false
        val end = schedule.endAt
        if (end != null && at >= end) return false

        val here = local(at, offsetMinutes)
        val clock = "${pad(here.hour.toLong(), 2)}:${pad(here.minute.toLong(), 2)}"
        if (!inWindow(schedule.from, schedule.to, clock)) return false
        val days = schedule.days ?: return true

        // After midnight inside a window that crosses it, the day that counts is the one the window opened on.
        val from = schedule.from
        val to = schedule.to
        val crossed = from != null && to != null && from > to && clock < to
        return (if (crossed) local(at - DAY, offsetMinutes) else here).weekday in days
    }
}
