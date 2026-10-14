package com.naury.pulse.ui

import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test

class TimeRangeTest {
    private val original = TimeZone.getDefault()

    @Before
    fun setUp() {
        // 일광 절약 시간이 있는 시간대로 날짜 경계를 확인한다.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(original)
    }

    private fun local(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute)
    }.timeInMillis

    private fun utcMidnight(year: Int, month: Int, day: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis

    @Test
    fun `달력의 UTC 날짜를 기기 시간대의 하루 전체로 바꾼다`() {
        val range = TimeRange.fromPicker(utcMidnight(2026, 10, 1), utcMidnight(2026, 10, 3))

        assertThat(range.from).isEqualTo(local(2026, 10, 1))
        assertThat(range.to).isEqualTo(local(2026, 10, 4) - 1)
        assertThat(local(2026, 10, 3, 23, 59) in range).isTrue()
        assertThat(local(2026, 10, 4) in range).isFalse()
        assertThat(range.label).isEqualTo("10.01 – 10.03")
    }

    @Test
    fun `하루만 고르면 그날 0시부터 다음 날 0시 직전까지다`() {
        val range = TimeRange.fromPicker(utcMidnight(2026, 10, 14), utcMidnight(2026, 10, 14))

        assertThat(range.from).isEqualTo(local(2026, 10, 14))
        assertThat(range.to).isEqualTo(local(2026, 10, 15) - 1)
    }

    @Test
    fun `어제는 일광 절약 시간이 끝나는 25시간짜리 날도 빠짐없이 덮는다`() {
        // 2026-11-01은 미국 동부에서 서머타임이 끝나 하루가 25시간이다.
        val now = local(2026, 11, 2, 9)
        val yesterday = TimeRange.days(startDaysAgo = 1, endDaysAgo = 1, label = "어제", now = now)

        assertThat(yesterday.from).isEqualTo(local(2026, 11, 1))
        assertThat(yesterday.to).isEqualTo(local(2026, 11, 2) - 1)
        assertThat(yesterday.to!! - yesterday.from!! + 1).isEqualTo(25L * 60 * 60 * 1000)
    }

    @Test
    fun `최근 7일은 6일 전 0시부터 오늘 끝까지다`() {
        val now = local(2026, 10, 14, 15)
        val week = TimeRange.days(startDaysAgo = 6, endDaysAgo = 0, label = "7일", now = now)

        assertThat(week.from).isEqualTo(local(2026, 10, 8))
        assertThat(week.to).isEqualTo(local(2026, 10, 15) - 1)
        assertThat(now in week).isTrue()
        assertThat(TimeRange.ALL.active).isFalse()
    }
}
