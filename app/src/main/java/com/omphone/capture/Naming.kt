package com.omphone.capture

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件命名规则（参考 OM Capture 的三段式：前缀 - 基本 - 后缀）。
 *
 * OM Capture 里只能填「拍摄日期」这种语义段，这里保持一致；日期时间格式也沿用它的几种预设。
 * 保存目录固定为 DCIM/OM Phone，只有文件名可自定义。
 */
enum class NamePart(val label: String) {
    NONE("无"),
    ORIGINAL("原始文件名"),
    CUSTOM("任意字符串"),
    DATE("拍摄日期"),
    TIME("拍摄时间"),
    DATETIME("拍摄日期和时间"),
    SEQ("连续号码")
}

/** 日期时间格式预设：显示名 → SimpleDateFormat 模板。 */
val DATE_PRESETS: List<Pair<String, String>> = listOf(
    "YYYYMMDD_HHMMSS" to "yyyyMMdd_HHmmss",
    "YYMMDD_HHMMSS" to "yyMMdd_HHmmss",
    "MMDDYYYY_HHMMSS" to "MMddyyyy_HHmmss",
    "MMDDYY_HHMMSS" to "MMddyy_HHmmss",
    "DDMMYYYY_HHMMSS" to "ddMMyyyy_HHmmss",
    "DDMMYY_HHMMSS" to "ddMMyy_HHmmss"
)

data class NamingRule(
    // 默认沿用 OM Capture 的三段式：拍摄日期 - 连续号码 - 原始文件名
    val prefix: NamePart = NamePart.DATE,
    val prefixText: String = "",
    val base: NamePart = NamePart.SEQ,
    val baseText: String = "",
    val suffix: NamePart = NamePart.ORIGINAL,
    val suffixText: String = "",
    val datePreset: String = "YYMMDD_HHMMSS",
    val start: Int = 1,
    val digits: Int = 4,
    val separator: String = "-"
) {
    val usesSeq: Boolean
        get() = prefix == NamePart.SEQ || base == NamePart.SEQ || suffix == NamePart.SEQ

    /** 人类可读摘要，例如「拍摄日期和时间-连续号码-原始文件名」。 */
    fun summary(): String {
        val parts = listOf(
            prefix to prefixText,
            base to baseText,
            suffix to suffixText
        ).filter { it.first != NamePart.NONE }
        if (parts.isEmpty()) return "原始文件名"
        return parts.joinToString(separator) {
            if (it.first == NamePart.CUSTOM) it.second.ifBlank { "自定义" } else it.first.label
        }
    }
}

object Naming {
    private const val K_PREFIX = "nm_prefix"
    private const val K_PREFIX_TXT = "nm_prefix_text"
    private const val K_BASE = "nm_base"
    private const val K_BASE_TXT = "nm_base_text"
    private const val K_SUFFIX = "nm_suffix"
    private const val K_SUFFIX_TXT = "nm_suffix_text"
    private const val K_DATE = "nm_date_preset"
    private const val K_START = "nm_start"
    private const val K_DIGITS = "nm_digits"
    private const val K_SEP = "nm_sep"
    private const val K_NEXT = "nm_next"

    private fun enumOf(name: String?, def: NamePart): NamePart =
        NamePart.entries.firstOrNull { it.name == name } ?: def

    fun rule(ctx: Context): NamingRule {
        val sp = Prefs.sp(ctx)
        return NamingRule(
            prefix = enumOf(sp.getString(K_PREFIX, null), NamePart.DATE),
            prefixText = sp.getString(K_PREFIX_TXT, "") ?: "",
            base = enumOf(sp.getString(K_BASE, null), NamePart.SEQ),
            baseText = sp.getString(K_BASE_TXT, "") ?: "",
            suffix = enumOf(sp.getString(K_SUFFIX, null), NamePart.ORIGINAL),
            suffixText = sp.getString(K_SUFFIX_TXT, "") ?: "",
            datePreset = sp.getString(K_DATE, "YYMMDD_HHMMSS") ?: "YYMMDD_HHMMSS",
            start = sp.getInt(K_START, 1),
            digits = sp.getInt(K_DIGITS, 4).coerceIn(1, 8),
            separator = sp.getString(K_SEP, "-") ?: "-"
        )
    }

    fun save(ctx: Context, r: NamingRule) {
        Prefs.sp(ctx).edit()
            .putString(K_PREFIX, r.prefix.name)
            .putString(K_PREFIX_TXT, r.prefixText)
            .putString(K_BASE, r.base.name)
            .putString(K_BASE_TXT, r.baseText)
            .putString(K_SUFFIX, r.suffix.name)
            .putString(K_SUFFIX_TXT, r.suffixText)
            .putString(K_DATE, r.datePreset)
            .putInt(K_START, r.start)
            .putInt(K_DIGITS, r.digits)
            .putString(K_SEP, r.separator)
            .apply()
    }

    /** 取下一个连续号；开始数字被改动过就回到新的起点。 */
    @Synchronized
    private fun nextSeq(ctx: Context, r: NamingRule): Int {
        val sp = Prefs.sp(ctx)
        val storedStart = sp.getInt(K_START, 1)
        var next = sp.getInt(K_NEXT, r.start)
        if (storedStart != r.start || next < r.start) next = r.start
        sp.edit().putInt(K_NEXT, next + 1).apply()
        return next
    }

    /** 按规则生成落盘文件名（含扩展名）。 */
    @Synchronized
    fun apply(ctx: Context, original: String, kind: FileKind): String {
        val r = rule(ctx)
        val seq = if (r.usesSeq) nextSeq(ctx, r) else 0
        return build(r, original, kind, seq)
    }

    fun build(r: NamingRule, original: String, kind: FileKind, seq: Int): String {
        val stem = original.substringBeforeLast('.')
        fun render(p: NamePart, custom: String): String = when (p) {
            NamePart.NONE -> ""
            NamePart.ORIGINAL -> stem
            NamePart.CUSTOM -> custom.trim()
            NamePart.DATE -> SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            NamePart.TIME -> SimpleDateFormat("HHmmss", Locale.US).format(Date())
            NamePart.DATETIME -> SimpleDateFormat(
                DATE_PRESETS.firstOrNull { it.first == r.datePreset }?.second ?: "yyMMdd_HHmmss",
                Locale.US
            ).format(Date())
            NamePart.SEQ -> seq.toString().padStart(r.digits, '0')
        }

        val segs = listOf(
            render(r.prefix, r.prefixText),
            render(r.base, r.baseText),
            render(r.suffix, r.suffixText)
        ).filter { it.isNotEmpty() }

        val raw = segs.joinToString(r.separator).ifEmpty { stem }
        // MediaStore 不接受路径分隔符等字符
        val safe = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        return "${safe.ifEmpty { "IMG" }}.${kind.ext}"
    }

    /** 对话框里的实时示例。 */
    fun sample(r: NamingRule): String = build(r, "P1170492.JPG", FileKind.JPG, r.start)
}
