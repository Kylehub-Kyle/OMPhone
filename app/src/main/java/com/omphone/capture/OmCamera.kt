package com.omphone.capture

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** 文件类型：由 0xc10d 事件的 FourCC 判定（扩展名倒序）。 */
enum class FileKind(val ext: String, val mime: String) {
    JPG("JPG", "image/jpeg"),
    RAW("ORF", "image/x-olympus-orf"),
    MOV("MOV", "video/quicktime");

    companion object {
        /** fourcc 例如 "GPJ." → JPG；"FRO." → RAW；"VOM." → MOV */
        fun fromFourcc(fourcc: String): FileKind? = when (fourcc) {
            "GPJ." -> JPG
            "FRO." -> RAW
            "VOM." -> MOV
            else -> null
        }
    }
}

data class NewFile(val handle: Int, val slot: Int, val kind: FileKind, val fourcc: String)

/**
 * 配置类错误（GUID 缺失/格式错、地址非法等）。
 *
 * 这类错误重试没有意义，必须让上层直接停下并原样显示原因——
 * 否则会被"连接超时""主机不可达"这类网络错误盖住，用户会去查网络而不是查配置。
 */
class ConfigException(message: String) : IllegalStateException(message)

/** 下载落盘目标（由 Service 用 MediaStore 实现）。 */
interface Sink {
    fun write(buf: ByteArray, len: Int)
    fun commit(): String          // 返回 uri 字符串
    fun abort()
}

class OmCamera(
    private val host: String,
    /** 上报给相机的客户端名，会显示在相机「连接至 PC」列表里；用中性名称避免暴露电脑主机名。 */
    private val hostname: String = "OM Phone",
    /**
     * 客户端 GUID 是设备专属凭据——相机只接受白名单里的那一组，换一组就 Init Failed。
     * 因此源码里不留默认值，构建时由 `local.properties` 的 `ptp.client.guid` 注入
     * （见 `app/build.gradle.kts` 的 `buildConfigField`）。
     */
    private val guidHex: String = BuildConfig.PTP_CLIENT_GUID,
    private val openSink: (name: String, kind: FileKind, size: Long) -> Sink?,
    private val rename: (orig: String, kind: FileKind) -> String = { n, _ -> n },
    private val accept: (NewFile) -> Boolean,
    private val cb: Callback
) {

    interface Callback {
        fun onStage(text: String)
        fun onDevice(text: String)
        fun onLog(text: String)
        fun onStart(name: String, orig: String, kind: FileKind, size: Long)
        fun onProgress(orig: String, done: Long, total: Long)
        fun onSaved(name: String, orig: String, kind: FileKind, size: Long, uri: String)
        fun onFailed(name: String, orig: String, reason: String)
        /** [fatal] = true 表示重试也没用（配置问题），上层应停下而不是自动重连。 */
        fun onStopped(reason: String, fatal: Boolean = false)
    }

    companion object {
        private const val TAG = "OmCamera"
        private const val CHUNK = 0x300000          // 3MB，与 OM Capture 一致
        private const val MAX_2019_RETRY = 40       // 视频刷盘最多等 80s
    }

    private val cmd = PtpLink(host, "命令通道")
    private val ev = PtpLink(host, "事件通道")
    private val downloads = Executors.newSingleThreadExecutor()

    /** 未配置或格式不对时给出可操作的提示，而不是带上一个假 GUID 去撞 Init Failed。 */
    private fun clientGuid(): ByteArray {
        if (guidHex.isBlank()) {
            throw ConfigException(
                "未配置客户端 GUID：在 local.properties 里写入 ptp.client.guid=<32 位十六进制>，或者设置同名环境变量，然后重新构建"
            )
        }
        // 只做 hexToBytes 是不够的：短串（如 "abc123"）本身也是合法十六进制，
        // 会被当成 3 字节 GUID 发给相机，最后只得到一句 Init Failed
        if (guidHex.length != 32 || guidHex.any { it.digitToIntOrNull(16) == null }) {
            throw ConfigException(
                "客户端 GUID 格式错误：需要 32 位十六进制字符，当前是 ${guidHex.length} 位"
            )
        }
        return hexToBytes(guidHex)
    }

    @Volatile private var running = false
    private var keepalive: Thread? = null

    /** 建连并进入监听（阻塞，需在后台线程调用）。 */
    fun runSession() {
        running = true
        try {
            // 配置错误必须最先暴露：放在 connect 之后会被网络错误盖住
            val guid = clientGuid()

            cb.onStage("UPnP 枚举…")
            val model = Upnp.probe(host)
            if (model != null) cb.onLog("UPnP: $model")

            cb.onStage("建立命令通道…")
            cmd.connect()
            val connNum = cmd.initCommand(guid, hostname)
            cb.onLog("InitCommand ACK, connection=$connNum")

            cb.onStage("建立事件通道…")
            ev.connect()
            ev.initEvent(connNum)

            val open = cmd.command(Ptp.OP_OPEN_SESSION, intArrayOf(1))
            cb.onLog("OpenSession: 0x%x".format(open.code))

            val dev = cmd.command(Ptp.OP_GET_DEVICE_INFO)
            val info = DeviceInfo.parse(dev.data)
            cb.onDevice(
                if (info != null) "${info.model} · ${info.serial}"
                else "OM-1"
            )

            val c3 = cmd.command(Ptp.OP_94C3, intArrayOf(0x1, 0x3c))
            cmd.command(Ptp.OP_94BB, intArrayOf(0x1))
            if (c3.code != Ptp.OK) cb.onLog("启用通知返回 0x%x".format(c3.code))

            cb.onStage("监听中")
            startKeepalive()
            eventLoop()
        } catch (e: ConfigException) {
            Log.w(TAG, "config error", e)
            cb.onStopped(e.message ?: "配置错误", fatal = true)
        } catch (e: Exception) {
            Log.w(TAG, "session error", e)
            cb.onStopped(friendlyError(e), fatal = false)
        } finally {
            running = false
            try { cmd.close() } catch (_: Exception) { }
            try { ev.close() } catch (_: Exception) { }
            downloads.shutdown()
        }
    }

    /**
     * 把 Socket 层的英文异常翻成「能照着做」的中文。
     * 原始 message 仍留在 logcat 里（TAG=OmCamera），排障时不受影响。
     */
    internal fun friendlyError(e: Throwable): String {
        val msg = e.message.orEmpty()
        val low = msg.lowercase()
        return when {
            e is NoRouteToHostException || low.contains("ehostunreach") ->
                "连不上相机（$host 主机不可达）。相机通常只接受一台设备：先在相机上断开其他连接，或等几秒再试"
            e is ConnectException || low.contains("econnrefused") ->
                "相机拒绝连接（$host:15740 未开放）。确认相机已进入「连接至 PC」模式，且 IP 是相机当前地址"
            e is SocketTimeoutException || low.contains("timeout") || low.contains("timed out") ->
                "连接 $host 超时。确认手机连的是这台相机的 WiFi，而不是家里的路由器"
            e is UnknownHostException ->
                "相机地址无法解析：检查 IP 是否填对（形如 192.168.1.50）"
            low.contains("econnreset") || low.contains("connection reset") ->
                "连接被相机重置：GUID 可能不在相机白名单里，或相机上已有其他客户端在连"
            else -> msg.ifEmpty { e.javaClass.simpleName }
        }
    }

    fun stop() {
        running = false
        try { cmd.close() } catch (_: Exception) { }
        try { ev.close() } catch (_: Exception) { }
    }

    private fun eventLoop() {
        while (running && ev.connected) {
            val pkt = try {
                ev.pollEvent()
            } catch (e: Exception) {
                cb.onStopped("事件通道断开（${e.message ?: "相机可能已休眠"}）")
                return
            } ?: continue

            if (pkt.size < 26) continue
            when (val code = le32(pkt, 8) and 0xffff) {
                Ptp.EV_NEW_FILE -> {
                    val fourcc = String(pkt, 14, 4, Charsets.US_ASCII)
                    val count = le32(pkt, 18)
                    val handle = le32(pkt, 22)
                    val kind = FileKind.fromFourcc(fourcc)
                    if (kind == null) {
                        cb.onLog("未知类型事件 ${fourcc.trim()} (handle=0x${handle.toString(16)})")
                        continue
                    }
                    val nf = NewFile(handle, (handle ushr 28), kind, fourcc)
                    Log.i(TAG, "EVENT 新文件 fourcc=$fourcc kind=$kind slot=${nf.slot} handle=0x${handle.toString(16)} count=$count accept=${accept(nf)}")
                    cb.onLog("新文件 ${fourcc.reversed()} · 卡槽${nf.slot} · #$count")
                    if (!accept(nf)) continue
                    downloads.execute { download(nf) }
                }
                Ptp.EV_REC_STATE -> {
                    val a = if (pkt.size >= 26) le32(pkt, 14) else 0
                    val b = if (pkt.size >= 30) le32(pkt, 18) else 0
                    cb.onLog("录像状态 ($a,$b)")
                }
                else -> cb.onLog("其它事件 0x%x".format(code))
            }
        }
    }

    private fun download(nf: NewFile) {
        var orig = "IMG_${nf.handle.toString(16)}.${nf.kind.ext}"
        var name = orig
        var sink: Sink? = null
        try {
            // 1) 查元数据：name\0 + uint32 size + uint32 0
            val meta = cmd.command(Ptp.OP_94DD, intArrayOf(nf.handle))
            val d = meta.data
            if (meta.code != Ptp.OK || d == null) {
                cb.onFailed(name, orig, "查询元数据失败 0x${meta.code.toString(16)}")
                return
            }
            val z = d.indexOf(0.toByte())
            if (z > 0) orig = String(d, 0, z, Charsets.US_ASCII)
            val total: Long = if (z + 5 <= d.size) le32(d, z + 1).toLong() and 0xffffffffL else 0L

            val useKind = kindOf(orig) ?: nf.kind
            name = rename(orig, useKind)
            Log.i(TAG, "download: orig=$orig -> name=$name total=$total kind=$useKind handle=0x${nf.handle.toString(16)}")
            val s = openSink(name, useKind, total)
            if (s == null) {
                Log.e(TAG, "download: openSink($name, $useKind) 返回 null")
                cb.onFailed(name, orig, "无法创建目标文件")
                return
            }
            sink = s
            cb.onStart(name, orig, nf.kind, total)

            // 2) 分块下载（0x94de 必须 5 个参数）
            var offset = 0L
            var retry2019 = 0
            while (running) {
                val reqLen = if (total > 0) minOf(CHUNK.toLong(), total - offset) else CHUNK.toLong()
                if (reqLen <= 0) break
                val r = cmd.command(
                    Ptp.OP_94DE,
                    intArrayOf(nf.handle, offset.toInt(), reqLen.toInt(), 0, 0)
                )
                if (r.code == Ptp.INCOMPLETE) {
                    // 视频刚录完还在刷盘：等 2 秒重试
                    if (++retry2019 > MAX_2019_RETRY) {
                        cb.onFailed(name, orig, "相机长时间未写完文件")
                        s.abort(); return
                    }
                    cb.onLog("$name 尚未写完，等待重试($retry2019)…")
                    Thread.sleep(2000)
                    continue
                }
                if (r.code != Ptp.OK) {
                    cb.onFailed(name, orig, "分块读取失败 0x${r.code.toString(16)}")
                    s.abort(); return
                }
                val chunk = r.data ?: break
                if (chunk.isEmpty()) break
                s.write(chunk, chunk.size)
                offset += chunk.size
                retry2019 = 0
                cb.onProgress(orig, offset, if (total > 0) total else offset)
                if (total > 0 && offset >= total) break
                if (chunk.size < reqLen) break
            }

            if (total > 0 && offset < total) {
                cb.onFailed(name, orig, "下载不完整 $offset/$total")
                s.abort(); return
            }

            // 3) 传完确认：必须带上行数据 8 字节 (1, 句柄)
            runCatching {
                cmd.command(
                    Ptp.OP_94BE, IntArray(0),
                    dataOut = leBytes(1, nf.handle)
                )
            }

            val uri = s.commit()
            cb.onSaved(name, orig, nf.kind, offset, uri)
        } catch (e: Exception) {
            Log.w(TAG, "download error", e)
            runCatching { sink?.abort() }
            cb.onFailed(name, orig, e.message ?: "下载异常")
        }
    }

    private fun startKeepalive() {
        keepalive = thread(isDaemon = true, name = "ptp-keepalive") {
            while (running) {
                try {
                    Thread.sleep(45_000)
                    if (!running) break
                    val r = cmd.command(Ptp.OP_94BA, intArrayOf(0x3c))
                    if (r.code != Ptp.OK) {
                        cb.onLog("保活异常 0x%x，重挂通知".format(r.code))
                        cmd.command(Ptp.OP_94C3, intArrayOf(0x1, 0x3c))
                    }
                } catch (e: Exception) {
                    if (running) cb.onLog("保活失败：${e.message}")
                    break
                }
            }
        }
    }

    private fun kindOf(name: String): FileKind? = when (name.substringAfterLast('.', "").uppercase()) {
        "JPG", "JPEG" -> FileKind.JPG
        "ORF" -> FileKind.RAW
        "MOV", "MP4" -> FileKind.MOV
        else -> null
    }
}

private fun le32(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xff) or
        ((b[off + 1].toInt() and 0xff) shl 8) or
        ((b[off + 2].toInt() and 0xff) shl 16) or
        ((b[off + 3].toInt() and 0xff) shl 24)

private fun leBytes(vararg values: Int): ByteArray {
    val out = ByteArray(values.size * 4)
    values.forEachIndexed { i, v ->
        out[i * 4] = (v and 0xff).toByte()
        out[i * 4 + 1] = ((v ushr 8) and 0xff).toByte()
        out[i * 4 + 2] = ((v ushr 16) and 0xff).toByte()
        out[i * 4 + 3] = ((v ushr 24) and 0xff).toByte()
    }
    return out
}

private fun hexToBytes(s: String): ByteArray =
    ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

/** GetDeviceInfo 的 PTP data 解析（找操作码表签名反推各字段）。 */
object DeviceInfo {
    data class Info(val manufacturer: String, val model: String, val version: String, val serial: String)

    fun parse(data: ByteArray?): Info? {
        if (data == null || data.size < 32) return null
        return try {
            val sig = byteArrayOf(0x01, 0x10, 0x02, 0x10, 0x03, 0x10)
            var s = -1
            for (i in 0..data.size - sig.size) {
                if ((0 until sig.size).all { data[i + it] == sig[it] }) { s = i; break }
            }
            if (s < 4) return null
            val nOps = le32(data, s - 4)
            var o = s + 2 * nOps
            val nEv = le32(data, o); o += 4 + 2 * nEv
            val nProp = le32(data, o); o += 4 + 2 * nProp
            val nCap = le32(data, o); o += 4 + 2 * nCap
            val nPin = le32(data, o); o += 4 + 2 * nPin
            val strs = ArrayList<String>(4)
            repeat(4) {
                if (o >= data.size) return null
                val len = data[o].toInt() and 0xff
                o += 1
                val raw = data.copyOfRange(o, minOf(o + 2 * len, data.size))
                o += 2 * len
                strs.add(String(raw, Charsets.UTF_16LE).trimEnd('\u0000'))
            }
            Info(strs[0], strs[1], strs[2], strs[3])
        } catch (e: Exception) {
            null
        }
    }
}

/** OM Capture 建连前必做的 UPnP/MTP 枚举（GET :1900/DeviceDescription.xml）。 */
object Upnp {
    fun probe(host: String, timeoutMs: Int = 4000): String? {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, 1900), timeoutMs)
                s.soTimeout = timeoutMs
                val req = "GET /DeviceDescription.xml HTTP/1.1\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Accept: text/xml, application/xml\r\n" +
                    "User-Agent: DAFUPnP\r\n" +
                    "Host: $host:1900\r\n\r\n"
                s.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
                val buf = ByteArrayOutputStream()
                val tmp = ByteArray(4096)
                while (buf.size() < 200 * 1024) {
                    val n = s.getInputStream().read(tmp)
                    if (n <= 0) break
                    buf.write(tmp, 0, n)
                    if (buf.toString("UTF-8").contains("</root>")) break
                }
                val text = buf.toString("UTF-8")
                Regex("<friendlyName>(.*?)</friendlyName>").find(text)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }
}
