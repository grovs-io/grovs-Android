package io.grovs.inapp

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

class InAppImagesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun campaign(template: String, portrait: String?, landscape: String? = null) = JsonParser.parseString(
        """{"id":"c1","template":"$template","content":{"image":{"portrait":${portrait?.let { "\"$it\"" }},"landscape":${landscape?.let { "\"$it\"" }}}}}"""
    )

    @Test
    fun `only https portrait and landscape images count, never for html`() {
        assertEquals(listOf("https://a/p.png", "https://a/l.png"), InAppImages.images(campaign("cover", "https://a/p.png", "https://a/l.png")))
        assertEquals(emptyList<String>(), InAppImages.images(campaign("cover", "http://a/p.png")))
        assertEquals(emptyList<String>(), InAppImages.images(campaign("html", "https://a/p.png")))
        assertEquals(emptyList<String>(), InAppImages.images(JsonParser.parseString("""{"content":5}""")))
        assertEquals(emptyList<String>(), InAppImages.images(JsonParser.parseString("[]")))
    }

    @Test
    fun `a campaign is ready once every image is on disk`() {
        val served = mutableListOf<String>()
        val images = InAppImages(temp.root, loader = { url, done -> served.add(url); done(byteArrayOf(1, 2, 3)) }, decodes = { true })
        val raw = campaign("cover", "https://a/p.png")
        assertFalse(images.ready(raw))
        images.preload(listOf(raw))
        assertTrue(images.ready(raw))
        assertNotNull(images.file("https://a/p.png"))
        images.preload(listOf(raw))
        assertEquals(listOf("https://a/p.png"), served)
    }

    @Test
    fun `says when a picture is stored, and only then`() {
        val served = mutableListOf<(ByteArray?) -> Unit>()
        val images = InAppImages(temp.root, loader = { _, done -> served.add(done) }, decodes = { it.size > 1 })
        var stored = 0
        images.onStored = { stored++ }
        images.preload(listOf(campaign("cover", "https://a/p.png", "https://a/l.png")))
        served[0](null)
        served[1](byteArrayOf(1))
        assertEquals(0, stored)
        images.preload(listOf(campaign("cover", "https://a/p.png")))
        served[2](byteArrayOf(1, 2))
        assertEquals(1, stored)
    }

    @Test
    fun `a failed download is tried again on the next preload`() {
        var asked = 0
        val images = InAppImages(temp.root, loader = { _, done -> asked++; done(null) }, decodes = { true })
        val raw = campaign("cover", "https://a/p.png")
        images.preload(listOf(raw))
        images.preload(listOf(raw))
        assertEquals(2, asked)
        assertFalse(images.ready(raw))
    }

    @Test
    fun `a download in flight is not started twice`() {
        val pending = mutableListOf<(ByteArray?) -> Unit>()
        val images = InAppImages(temp.root, loader = { _, done -> pending.add(done) }, decodes = { true })
        val raw = campaign("cover", "https://a/p.png")
        images.preload(listOf(raw))
        images.preload(listOf(raw))
        assertEquals(1, pending.size)
        pending.single()(byteArrayOf(1))
        assertTrue(images.ready(raw))
    }

    @Test
    fun `a file over five megabytes or one that does not decode is not kept`() {
        val big = InAppImages(temp.newFolder("big"), loader = { _, done -> done(ByteArray(InAppImages.MAX_BYTES + 1)) }, decodes = { true })
        big.preload(listOf(campaign("cover", "https://a/p.png")))
        assertNull(big.file("https://a/p.png"))

        val junk = InAppImages(temp.newFolder("junk"), loader = { _, done -> done(byteArrayOf(1)) }, decodes = { false })
        junk.preload(listOf(campaign("cover", "https://a/p.png")))
        assertNull(junk.file("https://a/p.png"))
    }

    @Test
    fun `keepOnly removes pictures no campaign names any more`() {
        val images = InAppImages(temp.root, loader = { _, done -> done(byteArrayOf(1)) }, decodes = { true })
        val a = campaign("cover", "https://a/a.png")
        val b = campaign("cover", "https://a/b.png")
        images.preload(listOf(a, b))
        images.keepOnly(listOf(b))
        assertFalse(images.has("https://a/a.png"))
        assertTrue(images.has("https://a/b.png"))
    }

    @Test
    fun `keepOnly deletes the files on the background, not on the caller`() {
        val queued = mutableListOf<Runnable>()
        val images = InAppImages(temp.root, loader = { _, done -> done(byteArrayOf(1)) }, decodes = { true }, background = Executor { queued.add(it) })
        val a = campaign("cover", "https://a/a.png")
        val b = campaign("cover", "https://a/b.png")
        images.preload(listOf(a, b))
        images.keepOnly(listOf(b))
        assertFalse(images.has("https://a/a.png"))
        assertEquals(2, temp.root.listFiles()!!.size)

        queued.forEach { it.run() }
        assertEquals(listOf(File(temp.root, InAppHash.sha256Hex("https://a/b.png"))), temp.root.listFiles()!!.toList())
        assertTrue(images.has("https://a/b.png"))
    }

    @Test
    fun `has and ready follow what the loader stored without asking the disk again`() {
        val images = InAppImages(temp.root, loader = { _, done -> done(byteArrayOf(1)) }, decodes = { true })
        val raw = campaign("cover", "https://a/p.png", "https://a/l.png")
        assertFalse(images.has("https://a/p.png"))
        images.preload(listOf(raw))
        assertTrue(images.has("https://a/p.png"))
        assertTrue(images.ready(raw))

        temp.root.listFiles()!!.forEach { it.delete() }
        assertTrue("readiness is kept in memory", images.ready(raw))
    }

    @Test
    fun `pictures stored by an earlier launch count as ready`() {
        val raw = campaign("cover", "https://a/p.png")
        InAppImages(temp.root, loader = { _, done -> done(byteArrayOf(1)) }, decodes = { true }).preload(listOf(raw))
        val later = InAppImages(temp.root, loader = { _, done -> done(null) }, decodes = { true })
        assertTrue(later.ready(raw))
        assertNotNull(later.file("https://a/p.png"))
    }
}
