package io.grovs.inapp

import io.grovs.inapp.model.InAppSchedule
import io.grovs.inapp.model.InAppTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class InAppTimeTest {
    private val start = 1788220800000L

    @Test
    fun dayFollowsTheOffsetIncludingHalfAndQuarterHours() {
        assertEquals("2026-10-01", InAppTime.dayOf(1790897400000L, 0))
        assertEquals("2026-10-02", InAppTime.dayOf(1790897400000L, 330))
        assertEquals("2026-10-01", InAppTime.dayOf(1790897400000L, -300))
        assertEquals("2026-09-30", InAppTime.dayOf(1790820000000L, -300))
        assertEquals("2026-10-01", InAppTime.dayOf(1790820000000L, 345))
    }

    @Test
    fun weekStartsOnMondayInLocalTime() {
        assertEquals("2026-09-28", InAppTime.weekOf(1790848800000L, 0))
        assertEquals("2026-09-28", InAppTime.weekOf(1791156600000L, 0))
        assertEquals("2026-09-28", InAppTime.weekOf(1791156600000L, -300))
        assertEquals("2026-10-05", InAppTime.weekOf(1791156600000L, 345))
    }

    @Test
    fun instantsBefore1970Floor() {
        assertEquals("1969-12-31", InAppTime.dayOf(-1000L, 0))
        assertEquals("1969-12-29", InAppTime.weekOf(-1000L, 0))
        assertEquals(0L, InAppTime.daysFromCivil(1970, 1, 1))
        assertEquals(-1L, InAppTime.daysFromCivil(1969, 12, 31))
        assertEquals(29, InAppTime.daysInMonth(2028, 2))
        assertEquals(28, InAppTime.daysInMonth(2100, 2))
        assertEquals(29, InAppTime.daysInMonth(2000, 2))
    }

    @Test
    fun startIsIncludedAndEndIsNot() {
        val schedule = InAppSchedule(start, 1790848800000L, null, null, null)
        assertTrue(InAppTime.inSchedule(schedule, start, 0))
        assertFalse(InAppTime.inSchedule(schedule, start - 1, 0))
        assertFalse(InAppTime.inSchedule(schedule, 1790848800000L, 0))
        assertTrue(InAppTime.inSchedule(schedule, 1790848800000L - 1, 0))
    }

    @Test
    fun aWindowCrossingMidnightCountsTheDayItOpened() {
        val thursdayNights = InAppSchedule(start, null, listOf(4), "22:00", "02:00")
        assertTrue(InAppTime.inSchedule(thursdayNights, 1790895600000L, 0))
        assertTrue(InAppTime.inSchedule(thursdayNights, 1790902800000L, 0))
        assertFalse(InAppTime.inSchedule(thursdayNights, 1790816400000L, 0))
        assertFalse(InAppTime.inSchedule(thursdayNights, 1790848800000L, 0))
    }

    @Test
    fun equalFromAndToIsTheWholeDay() {
        assertTrue(InAppTime.inSchedule(InAppSchedule(start, null, listOf(4), "09:00", "09:00"), 1790848800000L, 0))
        assertTrue(InAppTime.inSchedule(InAppSchedule(start, null, null, "09:00", "17:00"), 1790848800000L, 0))
        assertFalse(InAppTime.inSchedule(InAppSchedule(start, null, null, "11:00", "17:00"), 1790848800000L, 0))
    }

    @Test
    fun daysUseLocalTime() {
        val fridays = InAppSchedule(start, null, listOf(5), null, null)
        assertFalse(InAppTime.inSchedule(fridays, 1790897400000L, 0))
        assertTrue(InAppTime.inSchedule(fridays, 1790897400000L, 345))
    }

    @Test
    fun dayKeysAreAsciiUnderArabicLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar"))
            assertEquals("2026-10-01", InAppTime.dayOf(1790848800000L, 0))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
