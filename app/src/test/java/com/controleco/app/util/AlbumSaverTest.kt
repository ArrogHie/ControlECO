package com.controleco.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 相册保存的纯逻辑：扩展名→MIME、重名递增。
 * 真正写 MediaStore / 公共目录的部分依赖 Android 运行时，不在单测覆盖范围内。
 */
class AlbumSaverTest {

    @Test
    fun `mime follows extension`() {
        assertEquals("image/png", AlbumSaver.mimeOf("png"))
        assertEquals("image/png", AlbumSaver.mimeOf("PNG"))
        assertEquals("image/jpeg", AlbumSaver.mimeOf("jpg"))
        assertEquals("image/jpeg", AlbumSaver.mimeOf("JPEG"))
        assertEquals("image/webp", AlbumSaver.mimeOf("webp"))
        assertNull(AlbumSaver.mimeOf("gif"))
        assertNull(AlbumSaver.mimeOf(""))
    }

    @Test
    fun `next name keeps first free name`() {
        val base = "易控钱包-微信收款码"
        assertEquals("$base.png", AlbumSaver.nextName(base, "png", emptySet()))
        assertEquals("$base.png", AlbumSaver.nextName(base, "png", setOf("$base-2.png")))
    }

    @Test
    fun `next name increments past taken names`() {
        val base = "易控钱包-微信收款码"
        val existing = setOf("$base.png", "$base-2.png", "$base-3.png")
        assertEquals("$base-4.png", AlbumSaver.nextName(base, "png", existing))
    }

    @Test
    fun `unique file picks first free path`() {
        val dir = Files.createTempDirectory("album-test").toFile()
        try {
            val base = "易控钱包-支付宝收款码"
            assertEquals("$base.jpg", AlbumSaver.uniqueFile(dir, base, "jpg").name)

            File(dir, "$base.jpg").writeText("first")
            assertEquals("$base-2.jpg", AlbumSaver.uniqueFile(dir, base, "jpg").name)

            File(dir, "$base-2.jpg").writeText("second")
            assertEquals("$base-3.jpg", AlbumSaver.uniqueFile(dir, base, "jpg").name)
        } finally {
            dir.deleteRecursively()
        }
    }
}
