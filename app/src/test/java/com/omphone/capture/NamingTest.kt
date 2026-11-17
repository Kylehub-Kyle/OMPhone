package com.omphone.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 命名规则的纯逻辑校验（不依赖 Android）。 */
class NamingTest {

    @Test
    fun `原始文件名保持相机命名`() {
        val r = NamingRule(prefix = NamePart.NONE, base = NamePart.NONE, suffix = NamePart.ORIGINAL)
        assertEquals("P1170492.JPG", Naming.build(r, "P1170492.JPG", FileKind.JPG, 1))
        assertEquals("PB170514.ORF", Naming.build(r, "PB170514.ORF", FileKind.RAW, 1))
    }

    @Test
    fun `连续号码按位数补零`() {
        val r = NamingRule(prefix = NamePart.NONE, base = NamePart.SEQ, suffix = NamePart.ORIGINAL, start = 1, digits = 4)
        assertEquals("0001-P1170492.JPG", Naming.build(r, "P1170492.JPG", FileKind.JPG, 1))
        assertEquals("0042-P1170492.JPG", Naming.build(r, "P1170492.JPG", FileKind.JPG, 42))
        assertEquals("42-P1170492.JPG", Naming.build(r.copy(digits = 2), "P1170492.JPG", FileKind.JPG, 42))
    }

    @Test
    fun `三段拼接与自定义文字`() {
        val r = NamingRule(
            prefix = NamePart.CUSTOM, prefixText = "OM",
            base = NamePart.CUSTOM, baseText = "TRAVEL",
            suffix = NamePart.SEQ, digits = 3
        )
        assertEquals("OM-TRAVEL-007.JPG", Naming.build(r, "P1170492.JPG", FileKind.JPG, 7))
    }

    @Test
    fun `空段自动省略`() {
        val r = NamingRule(prefix = NamePart.NONE, base = NamePart.NONE, suffix = NamePart.NONE)
        assertEquals("P1170492.JPG", Naming.build(r, "P1170492.JPG", FileKind.JPG, 1))
    }

    @Test
    fun `拍摄日期与拍摄时间分别输出`() {
        val r = NamingRule(prefix = NamePart.DATE, suffix = NamePart.TIME)
        val name = Naming.build(r, "P1170492.JPG", FileKind.JPG, 1)
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val time = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        assertTrue(name.startsWith(day))
        assertTrue(name.endsWith("-$time.JPG"))
    }

    @Test
    fun `日期时间按预设格式`() {
        val r = NamingRule(prefix = NamePart.DATETIME, datePreset = "DDMMYYYY_HHMMSS")
        val name = Naming.build(r, "P1170492.JPG", FileKind.JPG, 1)
        val expect = SimpleDateFormat("ddMMyyyy_HHmmss", Locale.US).format(Date())
        assertTrue(name.startsWith(expect))
        assertTrue(name.endsWith(".JPG"))
    }

    @Test
    fun `非法字符被替换`() {
        val r = NamingRule(prefix = NamePart.NONE, base = NamePart.CUSTOM, baseText = "a/b:c*?", suffix = NamePart.NONE)
        assertEquals("a_b_c__.JPG", Naming.build(r, "P1170492.JPG", FileKind.JPG, 1))
    }

    @Test
    fun `扩展名跟随文件类型`() {
        val r = NamingRule(prefix = NamePart.NONE, base = NamePart.SEQ, suffix = NamePart.NONE)
        assertEquals("0001.MOV", Naming.build(r, "P1170492.MOV", FileKind.MOV, 1))
        assertEquals("0001.ORF", Naming.build(r, "P1170492.ORF", FileKind.RAW, 1))
    }
}
