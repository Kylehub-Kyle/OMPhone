package com.omphone.capture

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 相机发现。手机开热点时相机是 DHCP 客户端，手机不知道它拿了哪个 IP，
 * 所以两条路一起走：
 *  1) SSDP 组播 M-SEARCH（相机有 UPnP 栈，会回 LOCATION）
 *  2) 对本机所有 /24 网段并发扫 15740 端口（AP 常丢弃组播，这条兜底）
 */
object Discovery {

    suspend fun find(ctx: Context, timeoutMs: Long = 3500): String? = withContext(Dispatchers.IO) {
        ssdp(ctx, timeoutMs)?.let { return@withContext it }
        for (prefix in localPrefixes()) {
            scanSubnet(prefix)?.let { return@withContext it }
        }
        null
    }

    private suspend fun ssdp(ctx: Context, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        var lock: WifiManager.MulticastLock? = null
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("omphone")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            DatagramSocket().use { sock ->
                sock.soTimeout = 400
                val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:microsoft-com:device:mtp:1\r\n\r\n").toByteArray(Charsets.US_ASCII)
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val text = String(p.data, 0, p.length)
                        val host = Regex("(?i)LOCATION:\\s*http://([^/:\\r\\n]+)").find(text)
                            ?.groupValues?.get(1)
                        if (host != null && tcpProbe(host, 400)) return@withContext host
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            try { lock?.release() } catch (_: Exception) { }
        }
    }

    private fun localPrefixes(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val a = ia.address
                    val host = a.hostAddress ?: continue
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val parts = host.split(".")
                        if (parts.size == 4 && (parts[0] != "127")) {
                            out.add("${parts[0]}.${parts[1]}.${parts[2]}")
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    /** 并发扫 /24 网段的 15740 端口，第一个响应的就是相机。 */
    private suspend fun scanSubnet(prefix: String): String? = coroutineScope {
        val found = CompletableDeferred<String>()
        val jobs = (1..254).map { i ->
            async(Dispatchers.IO) {
                val ip = "$prefix.$i"
                if (!found.isCompleted && tcpProbe(ip, 350)) found.complete(ip)
            }
        }
        val r = withTimeoutOrNull(6000) { found.await() }
        jobs.forEach { it.cancel() }
        r
    }

    fun tcpProbe(ip: String, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, Ptp.PORT), timeoutMs)
            true
        }
    } catch (e: Exception) {
        false
    }
}
