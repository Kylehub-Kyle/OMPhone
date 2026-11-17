package com.omphone.capture

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP/IP（ISO 15740）常量。全部来自 OM-1 实机抓包逆向，
 * 详见工程根目录的《OM1_PTPIP_抓包逆向分析.md》。
 */
object Ptp {
    const val PORT = 15740

    // 包类型
    const val INIT_CMD_REQ = 1
    const val INIT_CMD_ACK = 2
    const val INIT_EVT_REQ = 3
    const val INIT_EVT_ACK = 4
    const val INIT_FAIL = 5
    const val CMD = 6
    const val RESP = 7
    const val EVENT = 8
    const val START_DATA = 9
    const val DATA = 10
    const val END_DATA = 11
    const val OM_END = 12   // 奥巴厂商偏差：数据阶段结束包用 0x0c（标准是 0x0b）

    // 标准操作码
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002

    // OM 厂商操作码
    const val OP_94BA = 0x94ba   // 保活（续 60s 通知租期）
    const val OP_94BB = 0x94bb   // 初始化
    const val OP_94C3 = 0x94c3   // (1,60) 启用通知 / (2) 关闭
    const val OP_94DD = 0x94dd   // 查对象元数据
    const val OP_94DE = 0x94de   // 分块读数据 (句柄,偏移,长度,0,0)
    const val OP_94BE = 0x94be   // 传完确认，必须带上行数据 (1,句柄)

    // 事件
    const val EV_NEW_FILE = 0xc10d    // 新文件：FourCC + count + 句柄
    const val EV_REC_STATE = 0xc10f   // 录像状态

    // 响应码
    const val OK = 0x2001
    const val INCOMPLETE = 0x2019     // 视频还在刷盘
}

data class PtpResp(val code: Int, val params: IntArray, val data: ByteArray?)

/** 一条 PTP/IP TCP 连接（命令通道或事件通道）。 */
class PtpLink(private val host: String, private val label: String) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var tid = 0
    private val txnLock = Any()

    val connected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    fun connect(timeoutMs: Int = 6000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, Ptp.PORT), timeoutMs)
        s.soTimeout = 9000
        socket = s
        input = DataInputStream(s.getInputStream())
        output = s.getOutputStream()
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        input = null
        output = null
    }

    /** OpenSession 必须用事务号 0，之后逐条 +1（TID 从 1 起会被相机直接 RST）。 */
    private fun nextTid(): Int = tid++

    private fun send(bytes: ByteArray) {
        val out = output ?: throw IllegalStateException("$label 未连接")
        out.write(bytes)
        out.flush()
    }

    /** 读一个完整包；allowIdle=true 时读超时返回 null（事件通道空闲）。 */
    private fun readPacket(allowIdle: Boolean = false): ByteArray? {
        val inp = input ?: throw IllegalStateException("$label 未连接")
        try {
            val head = ByteArray(4)
            inp.readFully(head)
            val len = le(head, 0).toInt()
            if (len < 4 || len > 64 * 1024 * 1024) throw IllegalStateException("$label 非法包长 $len")
            val pkt = ByteArray(len)
            System.arraycopy(head, 0, pkt, 0, 4)
            inp.readFully(pkt, 4, len - 4)
            return pkt
        } catch (e: SocketTimeoutException) {
            if (allowIdle) return null
            throw IllegalStateException("$label 等待相机响应超时", e)
        }
    }

    fun initCommand(guid: ByteArray, hostname: String): Int {
        val name = hostname.toByteArray(Charsets.UTF_16LE)
        val ver = byteArrayOf(0, 0, 1, 0)      // 抓包里就是这个字面值，非字符串
        val len = 4 + 4 + 16 + name.size + 2 + ver.size
        val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(len); b.putInt(Ptp.INIT_CMD_REQ); b.put(guid); b.put(name)
        b.put(byteArrayOf(0, 0)); b.put(ver)
        send(b.array())

        val resp = readPacket() ?: throw IllegalStateException("$label Init 无响应")
        return when (val type = le(resp, 4).toInt()) {
            Ptp.INIT_FAIL -> throw IllegalStateException("相机拒绝 Init（GUID 不在白名单）")
            Ptp.INIT_CMD_ACK -> le(resp, 8).toInt()
            else -> throw IllegalStateException("$label Init 未获 ACK（type=0x%x）".format(type))
        }
    }

    fun initEvent(connNum: Int) {
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(12); b.putInt(Ptp.INIT_EVT_REQ); b.putInt(connNum)
        send(b.array())
        val resp = readPacket() ?: throw IllegalStateException("$label InitEvent 无响应")
        val type = le(resp, 4).toInt()
        if (type != Ptp.INIT_EVT_ACK) throw IllegalStateException("$label InitEvent 未获 ACK")
    }

    /**
     * 发一条命令并收完整响应（含数据阶段）。
     * dataOut 不为空时先发上行数据阶段（0x94be 需要 8 字节 (1,句柄)）。
     * 整个事务加锁，保证下载线程与保活线程不会交叉收发。
     */
    fun command(opcode: Int, params: IntArray = IntArray(0), dataOut: ByteArray? = null): PtpResp {
        synchronized(txnLock) {
            val tid = nextTid()
            // 包结构：len(4) type(4) reserved(4) opcode(2) tid(4) params(4n) = 18 + 4n
            val len = 18 + 4 * params.size
            val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(len); b.putInt(Ptp.CMD); b.putInt(0)
            b.putShort(opcode.toShort()); b.putInt(tid)
            for (p in params) b.putInt(p)
            send(b.array())

            if (dataOut != null) {
                val sd = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                sd.putInt(20); sd.putInt(Ptp.START_DATA); sd.putInt(tid); sd.putLong(dataOut.size.toLong())
                val dp = ByteBuffer.allocate(12 + dataOut.size).order(ByteOrder.LITTLE_ENDIAN)
                dp.putInt(12 + dataOut.size); dp.putInt(Ptp.DATA); dp.putInt(tid); dp.put(dataOut)
                val ed = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                ed.putInt(12); ed.putInt(Ptp.OM_END); ed.putInt(tid)
                send(sd.array() + dp.array() + ed.array())
            }

            var acc: ByteArrayOutputStream? = null
            while (true) {
                val pkt = readPacket() ?: continue
                when (le(pkt, 4).toInt()) {
                    Ptp.RESP -> return PtpResp(
                        code = le(pkt, 8).toInt() and 0xffff,
                        params = parseParams(pkt),
                        data = acc?.toByteArray()
                    )
                    Ptp.START_DATA -> if (acc == null) acc = ByteArrayOutputStream(64 * 1024)
                    Ptp.DATA, Ptp.END_DATA, Ptp.OM_END -> {
                        if (acc == null) acc = ByteArrayOutputStream(64 * 1024)
                        acc.write(pkt, 12, pkt.size - 12)
                    }
                    else -> { /* 事件等其它包，忽略 */ }
                }
            }
        }
    }

    /** 事件通道阻塞读一包，空闲返回 null。 */
    fun pollEvent(): ByteArray? {
        val pkt = readPacket(allowIdle = true) ?: return null
        return if (le(pkt, 4).toInt() == Ptp.EVENT) pkt else null
    }

    private fun parseParams(pkt: ByteArray): IntArray {
        val n = ((pkt.size - 14) / 4).coerceIn(0, 5)
        return IntArray(n) { le(pkt, 14 + 4 * it).toInt() }
    }

    private fun le(b: ByteArray, off: Int): Long =
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
}
