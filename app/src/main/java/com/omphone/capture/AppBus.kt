package com.omphone.capture

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** 一条传输记录（UI 列表用）。 */
data class TransferUi(
    val name: String,          // 落盘文件名（可能已被命名规则重写）
    val kind: FileKind,
    val size: Long,
    val done: Long,
    val state: String,     // downloading / saved / failed
    val note: String = "",
    val uri: String? = null,  // 落盘后的 MediaStore Uri（预览用）
    val orig: String = ""     // 相机原始文件名（RAW/JPG 配对预览用）
)

data class UiState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val stage: String = "未连接",
    val device: String = "",
    val host: String = "",
    val transfers: List<TransferUi> = emptyList(),
    val logLine: String = "",
    val savedCount: Int = 0,
    val savedBytes: Long = 0L,
    val waiting: Int = 0
)

/** 全局唯一状态源：Service 写，Activity 读。 */
object AppBus {
    val state = MutableStateFlow(UiState())

    fun edit(block: (UiState) -> UiState) {
        state.value = block(state.value)
    }

    fun reset() {
        state.value = UiState()
    }

    fun upsertTransfer(item: TransferUi) {
        edit { s ->
            val list = s.transfers.toMutableList()
            val idx = list.indexOfFirst {
                (item.orig.isNotEmpty() && it.orig == item.orig) || it.name == item.name
            }
            if (idx >= 0) list[idx] = item else list.add(0, item)
            s.copy(transfers = list.take(60))
        }
    }
}

/** 用户偏好：格式勾选 + 上次相机 IP。 */
object Prefs {
    private const val NAME = "omphone"
    private const val KEY_FORMATS = "formats"
    private const val KEY_IP = "last_ip"

    fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun key(slot: Int, kind: FileKind) = "$slot:${kind.name}"

    fun enabled(ctx: Context): Set<String> =
        sp(ctx).getStringSet(KEY_FORMATS, DEFAULT)?.toSet() ?: DEFAULT

    val DEFAULT = setOf("1:JPG", "1:RAW", "2:MOV")

    fun isEnabled(ctx: Context, slot: Int, kind: FileKind): Boolean =
        enabled(ctx).contains(key(slot, kind))

    fun toggle(ctx: Context, slot: Int, kind: FileKind): Boolean {
        val cur = enabled(ctx).toMutableSet()
        val k = key(slot, kind)
        val on = if (cur.contains(k)) {
            cur.remove(k); false
        } else {
            cur.add(k); true
        }
        sp(ctx).edit().putStringSet(KEY_FORMATS, cur).apply()
        return on
    }

    fun lastIp(ctx: Context): String = sp(ctx).getString(KEY_IP, "") ?: ""

    fun saveIp(ctx: Context, ip: String) {
        sp(ctx).edit().putString(KEY_IP, ip).apply()
    }
}
