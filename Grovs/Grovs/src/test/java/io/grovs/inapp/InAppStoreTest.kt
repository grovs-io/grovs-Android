package io.grovs.inapp

import com.google.gson.JsonParser
import io.grovs.inapp.model.InAppGlobalCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InAppStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a record written is read back`() {
        val store = InAppFileStore(temp.root, "key")
        assertNull(store.read(InAppRecord.STATE))
        assertTrue(store.write("{\"a\":1}", InAppRecord.STATE))
        assertEquals("{\"a\":1}", store.read(InAppRecord.STATE))
    }

    @Test
    fun `each key has its own folder named by the first eight bytes of its hash`() {
        InAppFileStore(temp.root, "key").write("x", InAppRecord.CAMPAIGNS)
        InAppFileStore(temp.root, "test_key").write("y", InAppRecord.CAMPAIGNS)
        val folder = File(temp.root, "grovs/in_app/${InAppHash.short("key")}")
        assertEquals("x", File(folder, "in_app_campaigns.json").readText())
        assertEquals("y", InAppFileStore(temp.root, "test_key").read(InAppRecord.CAMPAIGNS))
        assertEquals(16, InAppHash.short("key").length)
        assertNotEquals(InAppHash.short("key"), InAppHash.short("test_key"))
    }

    @Test
    fun `the hash is lowercase hex sha256`() {
        assertEquals("2c70e12b7a0646f92279f427c7b38e7334d8e5389cff167a1dc30e73f826b683", InAppHash.sha256Hex("key"))
    }

    @Test
    fun `synced campaigns round trip with null caps and stamps`() {
        val store = InAppFileStore(temp.root, "key")
        val campaign = JsonParser.parseString("""{"id":"c1","revision":2}""")
        val synced = InAppSynced(
            campaigns = listOf(campaign), globalCaps = InAppGlobalCaps(session = 3, day = null),
            firstSeenAt = "2026-08-01T00:00:00Z", storeUrl = "https://play.google.com/store/apps/details?id=a",
            campaignsChangedAt = "c1", userChangedAt = null, clockOffset = -1500,
        )
        assertTrue(synced.save(store))
        assertEquals(synced, InAppSynced.load(store))
        val raw = JsonParser.parseString(store.read(InAppRecord.CAMPAIGNS)).asJsonObject
        assertTrue(raw.getAsJsonObject("global_caps").get("day").isJsonNull)
    }

    @Test
    fun `an unreadable campaigns record loads as nothing`() {
        for (text in listOf("not json", "[]", "{\"campaigns\":5}", "{}")) {
            val store = FakeInAppStore()
            store.records[InAppRecord.CAMPAIGNS] = text
            assertNull(text, InAppSynced.load(store))
        }
    }

    @Test
    fun `kept state round trips with its session`() {
        val store = InAppFileStore(temp.root, "key")
        val kept = InAppKept(
            sessionId = "s1",
            state = InAppEngineState(
                views = listOf(InAppView(id = "v1", campaign = "c1", at = 1_000L, global = true)),
                session = listOf("v1"), counts = mapOf("cart_viewed" to 2), syncedAt = 5_000L, answered = listOf("c9"),
            ),
        )
        assertTrue(kept.save(store))
        assertEquals(kept, InAppKept.load(store))
    }

    @Test
    fun `an unreadable state record loads as nothing`() {
        for (text in listOf("not json", "[]", "{\"state\":5}", "{\"state\":{\"views\":5}}", "{\"sessionId\":\"s1\"}")) {
            val store = FakeInAppStore()
            store.records[InAppRecord.STATE] = text
            assertNull(text, InAppKept.load(store))
        }
    }

    @Test
    fun `a write that cannot create its folder fails without throwing`() {
        val blocker = temp.newFile("grovs")
        assertTrue(blocker.exists())
        assertEquals(false, InAppFileStore(temp.root, "key").write("x", InAppRecord.STATE))
    }
}
