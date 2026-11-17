package com.omphone.capture

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8 修的两个问题：
 * 1. GUID 为空时必须「在建连之前」就报配置错误——否则会被 EHOSTUNREACH / 超时盖住，
 *    用户会去查网络而不是查配置；
 * 2. Socket 层英文异常要翻成能照着做的中文。
 */
class OmCameraErrorTest {

    private class Recorder : OmCamera.Callback {
        var stopped: String? = null
        var fatal = false
        var connected = false

        override fun onStage(text: String) {}
        override fun onDevice(text: String) { connected = true }
        override fun onLog(text: String) {}
        override fun onStart(name: String, orig: String, kind: FileKind, size: Long) {}
        override fun onProgress(orig: String, done: Long, total: Long) {}
        override fun onSaved(name: String, orig: String, kind: FileKind, size: Long, uri: String) {}
        override fun onFailed(name: String, orig: String, reason: String) {}
        override fun onStopped(reason: String, fatal: Boolean) {
            stopped = reason
            this.fatal = fatal
        }
    }

    private fun camera(host: String, guid: String, rec: Recorder) = OmCamera(
        host = host,
        guidHex = guid,
        openSink = { _, _, _ -> null },
        accept = { false },
        cb = rec
    )

    @Test
    fun `GUID 为空时在建连之前就报配置错误`() {
        val rec = Recorder()
        // TEST-NET-3，正常网络里不可达：如果先去连，必然要等超时
        val cam = camera("203.0.113.1", "", rec)

        val t0 = System.currentTimeMillis()
        cam.runSession()
        val cost = System.currentTimeMillis() - t0

        val msg = rec.stopped.orEmpty()
        assertTrue("应报未配置 GUID，实际：$msg", msg.contains("GUID"))
        assertTrue("配置错误应标记 fatal，上层不要自动重连", rec.fatal)
        assertTrue("应在建连前返回，实际耗时 ${cost}ms", cost < 1500)
        assertTrue("没有连上相机", !rec.connected)
    }

    @Test
    fun `GUID 长度不对时报格式错误`() {
        // "abc123" 本身是合法十六进制：只调 hexToBytes 会把它当成 3 字节 GUID 发出去
        for (bad in listOf("abc123", "00112233445566778899aabbccddee", "zz112233445566778899aabbccddeeff")) {
            val rec = Recorder()
            camera("203.0.113.1", bad, rec).runSession()
            val msg = rec.stopped.orEmpty()
            assertTrue("[$bad] 应报格式错误，实际：$msg", msg.contains("格式错误"))
            assertTrue(rec.fatal)
        }
    }

    @Test
    fun `主机不可达的英文异常翻成中文建议`() {
        val rec = Recorder()
        val text = camera("192.168.1.50", "00".repeat(16), rec)
            .friendlyError(NoRouteToHostException("failed to connect: EHOSTUNREACH (No route to host)"))
        assertTrue(text, text.contains("主机不可达"))
        assertTrue("应带上相机地址", text.contains("192.168.1.50"))
    }

    @Test
    fun `超时与拒绝连接分别给不同建议`() {
        val rec = Recorder()
        val timeout = camera("192.168.1.50", "00".repeat(16), rec)
            .friendlyError(SocketTimeoutException("failed to connect to /192.168.1.50:15740 after 6000ms"))
        assertTrue(timeout, timeout.contains("超时"))

        val refused = camera("192.168.1.50", "00".repeat(16), rec)
            .friendlyError(ConnectException("failed to connect to /192.168.1.50:15740 - ECONNREFUSED"))
        assertTrue(refused, refused.contains("拒绝连接"))
        assertTrue("两条建议不应相同", timeout != refused)
    }
}
