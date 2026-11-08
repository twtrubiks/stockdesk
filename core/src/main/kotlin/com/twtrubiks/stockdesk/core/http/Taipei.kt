package com.twtrubiks.stockdesk.core.http

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 所有「今天」、「資料日期」都以台北時間為準，不看手機的時區。 */
object Taipei {
    val ZONE: ZoneId = ZoneOffset.ofHours(8)
    private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val SLASH = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    fun today(now: () -> Instant = Instant::now): LocalDate = LocalDateTime.ofInstant(now(), ZONE).toLocalDate()

    fun ymd(d: LocalDate): String = d.format(YMD)

    fun slash(d: LocalDate): String = d.format(SLASH)

    /** epoch 毫秒 → 「2026-11-08 09:30」。 */
    fun stamp(epochMs: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
