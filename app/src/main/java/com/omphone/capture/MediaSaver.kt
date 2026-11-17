package com.omphone.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedOutputStream

/**
 * 通过 MediaStore 写入相册目录 DCIM/OM Phone。
 * Android 10+ 无需存储权限；IS_PENDING 保证写一半的文件不会出现在相册里。
 *
 * RAW(.ORF) 是"非媒体"类型，个别 ROM（HyperOS/MIUI）对 Files 集合的插入会直接返回 null，
 * 因此这里做多集合回退：Files → Images → Downloads，任一成功即用。
 */
class MediaSaver(private val ctx: Context) {

    companion object {
        const val DIR = "DCIM/OM Phone"
        const val TAG = "OmPhone"
    }

    private fun candidates(kind: FileKind): List<Uri> {
        val primary = MediaStore.VOLUME_EXTERNAL_PRIMARY
        return when (kind) {
            FileKind.JPG -> listOf(MediaStore.Images.Media.getContentUri(primary))
            FileKind.MOV -> listOf(MediaStore.Video.Media.getContentUri(primary))
            // Android 14+ 起 Files 集合禁止写 DCIM（只允许 Download/Documents），
            // 因此 RAW 直接走 Images 集合（DCIM 合法），Files 仅作兜底。
            FileKind.RAW -> listOf(
                MediaStore.Images.Media.getContentUri(primary),
                MediaStore.Files.getContentUri(primary),
                MediaStore.Downloads.getContentUri(primary)
            )
        }
    }

    fun open(name: String, kind: FileKind, size: Long): Sink? {
        val resolver = ctx.contentResolver
        var lastError = "未知原因"

        for ((idx, collection) in candidates(kind).withIndex()) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, kind.mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = try {
                resolver.insert(collection, values)
            } catch (e: Exception) {
                lastError = "insert#$idx ${shortName(collection)}: ${e.javaClass.simpleName} ${e.message}"
                Log.e(TAG, "open($name) $lastError", e)
                null
            }
            Log.i(TAG, "open($name) insert#$idx ${shortName(collection)} -> $uri")
            if (uri == null) continue

            // MediaStore 可能因 mime 与扩展名不匹配而自动补后缀（如 PB170492.MOV → .MOV.mp4），
            // 这里校验并纠正为相机原始文件名。
            val actual = queryName(uri)
            if (actual != null && actual != name) {
                Log.w(TAG, "open($name) 名称被改为 $actual，尝试纠正")
                val fix = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                }
                val ok = runCatching { resolver.update(uri, fix, null, null) > 0 }.getOrDefault(false)
                Log.i(TAG, "open($name) 纠正${if (ok) "成功" else "失败"}，现名=${queryName(uri)}")
            }

            val out = try {
                resolver.openOutputStream(uri)
            } catch (e: Exception) {
                lastError = "openOutputStream#$idx: ${e.message}"
                Log.e(TAG, "open($name) $lastError", e)
                null
            }
            if (out == null) {
                runCatching { resolver.delete(uri, null, null) }
                continue
            }

            Log.i(TAG, "open($name) 成功，集合=${shortName(collection)}")
            return object : Sink {
                private val bos = BufferedOutputStream(out, 256 * 1024)
                private var closed = false

                override fun write(buf: ByteArray, len: Int) {
                    bos.write(buf, 0, len)
                }

                override fun commit(): String {
                    bos.flush()
                    bos.close()
                    closed = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        runCatching { resolver.update(uri, v, null, null) }
                    }
                    Log.i(TAG, "commit($name) uri=$uri")
                    return uri.toString()
                }

                override fun abort() {
                    if (!closed) runCatching { bos.close() }
                    runCatching { resolver.delete(uri, null, null) }
                    Log.w(TAG, "abort($name)")
                }
            }
        }

        Log.e(TAG, "open($name) 全部集合均失败: $lastError")
        return null
    }

    private fun shortName(uri: Uri): String = uri.lastPathSegment ?: uri.toString()

    private fun queryName(uri: Uri): String? = runCatching {
        ctx.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** 命名探针：尝试多种 mime/集合组合，报告 MediaStore 最终落盘的文件名。 */
    fun nametest(): String {
        val sb = StringBuilder()
        val stamp = System.currentTimeMillis() % 100000
        val video = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val cases = listOf(
            Triple("video/quicktime", "A$stamp.MOV", video),
            Triple("video/mp4", "B$stamp.MOV", video),
            Triple(null, "C$stamp.MOV", video),
            Triple("video/quicktime", "D$stamp.MOV", files),
            Triple("application/octet-stream", "E$stamp.MOV", files)
        )
        for ((mime, name, coll) in cases) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                if (mime != null) put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val tag = "${mime ?: "无mime"}@${shortName(coll)}"
            val uri = try {
                ctx.contentResolver.insert(coll, values)
            } catch (e: Exception) {
                sb.append("$tag -> 异常 ${e.javaClass.simpleName}: ${e.message?.take(60)} | ")
                continue
            }
            if (uri == null) {
                sb.append("$tag -> null | ")
                continue
            }
            val actual = runCatching {
                ctx.contentResolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
                )?.use { if (it.moveToFirst()) it.getString(0) else "?" } ?: "?"
            }.getOrDefault("?")
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            sb.append("$name → $actual | ")
        }
        return sb.toString()
    }

    /** 写盘自检：每种类型各写 1KB 再删除，返回可读报告（adb logcat 里看）。 */
    fun selftest(keepFiles: Boolean = false): String {
        val sb = StringBuilder()
        val stamp = System.currentTimeMillis() % 100000
        for (kind in FileKind.values()) {
            val name = "ZZSELFTEST_${stamp}.${kind.ext}"
            val started = System.currentTimeMillis()
            val sink = open(name, kind, 1024)
            if (sink == null) {
                sb.append("${kind.name}: 建文件失败 | ")
                continue
            }
            try {
                sink.write(ByteArray(1024) { 0x41 }, 1024)
                val uri = sink.commit()
                sb.append("${kind.name}: OK ${System.currentTimeMillis() - started}ms $uri | ")
                if (keepFiles) sb.append("(保留) ") else runCatching { ctx.contentResolver.delete(Uri.parse(uri), null, null) }
            } catch (e: Exception) {
                runCatching { sink.abort() }
                sb.append("${kind.name}: 写入异常 ${e.message} | ")
            }
        }
        return sb.toString()
    }
}
