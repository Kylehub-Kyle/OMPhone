package com.omphone.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * PTP/IP 线级验证：本机起一个「假相机」，逐字节比对客户端发出的包。
 * 校验点是 PC 端实机踩过的三个坑（0x94de 五参数 / 0x94be 上行数据阶段 / 结束包 0x0c）。
 */
class PtpWireTest {

    private lateinit var server: ServerSocket
    private val rx = LinkedBlockingQueue<ByteArray>()

    private fun le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    private fun readPacket(inp: DataInputStream): ByteArray {
        val head = ByteArray(4)
        inp.readFully(head)
        val len = le(head, 0)
        val pkt = ByteArray(len)
        System.arraycopy(head, 0, pkt, 0, 4)
        inp.readFully(pkt, 4, len - 4)
        return pkt
    }

    private fun resp(code: Int, tid: Int): ByteArray {
        val b = ByteArray(14)
        b[0] = 14; b[4] = 7
        b[8] = (code and 0xff).toByte(); b[9] = ((code shr 8) and 0xff).toByte()
        b[10] = (tid and 0xff).toByte()
        return b
    }

    @Before
    fun setUp() {
        server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", Ptp.PORT))
    }

    @After
    fun tearDown() {
        try { server.close() } catch (_: IOException) { }
    }

    @Test
    fun handshake_then_commands_match_capture() {
        val fake = thread {
            server.accept().use { s ->
                val inp = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()

                // 1) Init Command Request（抓包 frame 539：38 字节）
                val init = readPacket(inp)
                rx.put(init)
                out.write(byteArrayOf(
                    28, 0, 0, 0, 2, 0, 0, 0,
                    0x7c, 0x3d, 0, 0,   // connNum = 15740 (0x3D7C 小端)
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
                ))
                out.flush()

                // 2) OpenSession（tid 必须为 0）
                val open = readPacket(inp)
                rx.put(open)
                out.write(resp(Ptp.OK, 0)); out.flush()

                // 3) 0x94dd 查元数据
                val dd = readPacket(inp)
                rx.put(dd)
                // 数据阶段：START(21) + DATA(21 bytes: name\0 + size + 0) + END
                val meta = buildMeta("PB170486.JPG", 8676819)
                val sd = ByteArray(20).also { it[0] = 20; it[4] = 9 }
                val dp = ByteArray(12 + meta.size).also {
                    it[0] = (12 + meta.size).toByte(); it[4] = 10
                }
                System.arraycopy(meta, 0, dp, 12, meta.size)
                val ed = ByteArray(12).also { it[0] = 12; it[4] = 12 }
                out.write(sd); out.write(dp); out.write(ed)
                out.write(resp(Ptp.OK, 1)); out.flush()

                // 4) 0x94de 分块（5 参数）
                val de = readPacket(inp)
                rx.put(de)
                val chunk = ByteArray(16) { 0x41 }
                val s2 = ByteArray(20).also { it[0] = 20; it[4] = 9 }
                val d2 = ByteArray(12 + chunk.size).also {
                    it[0] = (12 + chunk.size).toByte(); it[4] = 10
                }
                System.arraycopy(chunk, 0, d2, 12, chunk.size)
                val e2 = ByteArray(12).also { it[0] = 12; it[4] = 12 }
                out.write(s2); out.write(d2); out.write(e2)
                out.write(resp(Ptp.OK, 2)); out.flush()

                // 5) 0x94be + 上行数据阶段
                val be = readPacket(inp)
                rx.put(be)
                rx.put(readPacket(inp))   // START_DATA
                rx.put(readPacket(inp))   // DATA
                rx.put(readPacket(inp))   // END
                out.write(resp(Ptp.OK, 3)); out.flush()
            }
        }

        val link = PtpLink("127.0.0.1", "test")
        link.connect(2000)
        // 客户端名是变长的，抓包里是 4 个字符；这里用实际默认值，偏移量按名长推算
        val clientName = "OM Phone"
        val nameBytes = clientName.toByteArray(Charsets.UTF_16LE)
        // 测试用占位 GUID：真实 GUID 是设备专属凭据，不进仓库
        val testGuidHex = "00112233445566778899aabbccddeeff"
        val conn = link.initCommand(
            testGuidHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            clientName
        )
        assertEquals("Init ACK 连接号", 15740, conn)

        val open = link.command(Ptp.OP_OPEN_SESSION, intArrayOf(1))
        assertEquals("OpenSession 结果", Ptp.OK, open.code)

        val dd = link.command(Ptp.OP_94DD, intArrayOf(0x10000001))
        assertEquals("元数据长度", 21, dd.data?.size)
        assertEquals("文件名", "PB170486.JPG",
            String(dd.data!!, 0, 12, Charsets.US_ASCII))

        val de = link.command(Ptp.OP_94DE, intArrayOf(0x10000001, 0, 0x300000, 0, 0))
        assertEquals("分块数据长度", 16, de.data?.size)

        link.command(Ptp.OP_94BE, IntArray(0), dataOut = byteArrayOf(1, 0, 0, 0, 1, 0, 0, 0x10))
        link.close()
        fake.join(3000)

        // ---- 字节级断言 ----
        val init = rx.take()
        // 抓包里客户端名是 4 字符（38 字节）；名长变化时整包长度与后续偏移一起平移
        assertEquals("Init 包长", 24 + nameBytes.size + 2 + 4, init.size)
        assertEquals("Init 类型", 1, le(init, 4))
        assertEquals("Init GUID", testGuidHex,
            init.copyOfRange(8, 24).joinToString("") { "%02x".format(it) })
        assertEquals("客户端名 UTF-16LE", clientName,
            String(init, 24, nameBytes.size, Charsets.UTF_16LE))
        // 名字后面跟 2 字节结束符，再接协议版本 0x00000100
        val verOff = 24 + nameBytes.size + 2
        assertEquals("协议版本字面值", "00000100",
            init.copyOfRange(verOff, verOff + 4).joinToString("") { "%02x".format(it) })

        val openPkt = rx.take()
        assertEquals("OpenSession 包长", 22, openPkt.size)
        assertEquals("OpenSession 事务号必须为 0", 0, le(openPkt, 14))

        val ddPkt = rx.take()
        assertEquals("0x94dd 包长", 22, ddPkt.size)
        assertEquals("0x94dd opcode", 0x94dd, le(ddPkt, 12) and 0xffff)
        assertEquals("0x94dd 句柄参数", 0x10000001, le(ddPkt, 18))

        val dePkt = rx.take()
        assertEquals("0x94de 包长（5 参数=38 字节）", 38, dePkt.size)
        assertEquals("0x94de opcode", 0x94de, le(dePkt, 12) and 0xffff)
        assertEquals("0x94de 句柄", 0x10000001, le(dePkt, 18))
        assertEquals("0x94de 偏移", 0, le(dePkt, 22))
        assertEquals("0x94de 长度", 0x300000, le(dePkt, 26))
        assertEquals("0x94de 第四参数必须存在且为 0", 0, le(dePkt, 30))
        assertEquals("0x94de 第五参数必须存在且为 0", 0, le(dePkt, 34))

        val bePkt = rx.take()
        assertEquals("0x94be 命令包长（无参数=18）", 18, bePkt.size)
        val sd = rx.take()
        assertEquals("0x94be START_DATA 类型", 9, le(sd, 4))
        assertEquals("0x94be 上行总长 8", 8, le(sd, 12))
        val dp = rx.take()
        assertEquals("0x94be DATA 类型", 10, le(dp, 4))
        assertEquals("0x94be 上行载荷 (1, 句柄) 长度", 20, dp.size)
        assertEquals("0x94be 上行载荷 参数1", 1, le(dp, 12))
        assertEquals("0x94be 上行载荷 句柄", 0x10000001, le(dp, 16))
        val ed = rx.take()
        assertEquals("数据结束包必须是 0x0c（奥巴偏差）", 12, le(ed, 4))
    }

    @Test
    fun fourcc_mapping() {
        assertEquals(FileKind.JPG, FileKind.fromFourcc("GPJ."))
        assertEquals(FileKind.RAW, FileKind.fromFourcc("FRO."))
        assertEquals(FileKind.MOV, FileKind.fromFourcc("VOM."))
        assertEquals(null, FileKind.fromFourcc("XXXX"))
    }

    @Test
    fun slot_is_top_nibble() {
        assertEquals(1, 0x10000001 ushr 28)
        assertEquals(2, 0x20000001 ushr 28)
    }

    private fun buildMeta(name: String, size: Int): ByteArray {
        val n = name.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(n.size + 1 + 8)
        System.arraycopy(n, 0, out, 0, n.size)
        out[n.size] = 0
        out[n.size + 1] = (size and 0xff).toByte()
        out[n.size + 2] = ((size shr 8) and 0xff).toByte()
        out[n.size + 3] = ((size shr 16) and 0xff).toByte()
        out[n.size + 4] = ((size shr 24) and 0xff).toByte()
        return out
    }
}
