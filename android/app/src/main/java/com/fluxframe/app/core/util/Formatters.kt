package com.fluxframe.app.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/* 全局格式化工具：后端返回的是 ISO 字符串 / 已格式化体积，这里统一成中文展示。 */

private val dateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)

private val zone: ZoneId get() = ZoneId.systemDefault()

fun parseInstant(iso: String?): Instant? = try {
    if (iso.isNullOrBlank()) null else Instant.parse(iso)
} catch (_: Exception) {
    null
}

/** 人类可读体积（后端也会给一份字符串，但本地计算进度时需要它） */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = 1024.0
    return when {
        bytes < kb -> "$bytes B"
        bytes < kb * kb -> String.format(Locale.CHINA, "%.1f KB", bytes / kb)
        bytes < kb * kb * kb -> String.format(Locale.CHINA, "%.1f MB", bytes / (kb * kb))
        else -> String.format(Locale.CHINA, "%.2f GB", bytes / (kb * kb * kb))
    }
}

/** 秒 → `12:34` 或 `1:02:03` */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "0:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.CHINA, "%d:%02d", m, s)
    }
}

/** `刚刚` / `5 分钟前` / `3 小时前` / `2025-01-31` */
fun formatRelative(iso: String?): String {
    val instant = parseInstant(iso) ?: return iso.orEmpty()
    val diff = abs(System.currentTimeMillis() - instant.toEpochMilli())
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} 天前"
        else -> instant.atZone(zone).format(dateFormatter)
    }
}

fun formatDateTime(iso: String?): String =
    parseInstant(iso)?.atZone(zone)?.format(dateTimeFormatter) ?: iso.orEmpty()

fun formatDate(iso: String?): String =
    parseInstant(iso)?.atZone(zone)?.format(dateFormatter) ?: iso.orEmpty()

/** 大数缩写：12345 → `1.2万` */
fun formatCount(value: Long): String = when {
    value < 10_000 -> value.toString()
    value < 100_000_000 -> String.format(Locale.CHINA, "%.1f万", value / 10_000.0)
    else -> String.format(Locale.CHINA, "%.2f亿", value / 100_000_000.0)
}

/** 金额展示：CNY 用 ¥，其它币种加前缀 */
fun formatMoney(value: Double, currency: String? = "CNY"): String {
    val symbol = when (currency?.uppercase(Locale.ROOT)) {
        "CNY", "RMB", null, "" -> "¥"
        "USD" -> "$"
        else -> "${currency.orEmpty()} "
    }
    return symbol + String.format(Locale.CHINA, "%.2f", value)
}

/** 0.1234 → `12.3%` */
fun formatPercent(ratio: Double?, digits: Int = 1): String {
    if (ratio == null || ratio.isNaN()) return "--"
    return String.format(Locale.CHINA, "%.${digits}f%%", ratio * 100)
}

/**
 * 由字符串稳定地派生一个色相，用于「没有封面时的占位色块」。
 * 与网页端 `color` 字段的行为一致：同一个 id 永远得到同一种颜色。
 */
fun hueFromKey(key: String): Float {
    var hash = 0
    for (char in key) {
        hash = hash * 31 + char.code
    }
    return (abs(hash) % 360).toFloat()
}
