package io.grovs.e2e.inapp

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import com.google.gson.JsonObject
import io.grovs.Grovs
import io.grovs.R
import io.grovs.e2e.contract.InAppCampaigns
import io.grovs.e2e.contract.InAppCampaigns.button
import io.grovs.e2e.contract.InAppCampaigns.event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
internal class InAppActionsImagesE2ETest : InAppTestBase() {

    private fun showWith(vararg buttons: JsonObject) {
        showOne(InAppCampaigns.campaign().buttons(*buttons).build())
    }

    private fun clickProperties() = reportedWhen("in_app_clicked").single().getJSONObject("properties")

    // open_url: "Intent(ACTION_VIEW, Uri.parse(url))"; a click is reported with the button
    @Test
    fun `I38 open_url opens the link, reports the click with its button and closes`() {
        showWith(button("go", "Go", InAppCampaigns.openUrl("https://grovs.io/promo")))
        tapButton("go")
        val started = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://grovs.io/promo", started.dataString)
        val click = clickProperties()
        assertEquals("open_url", click.getString("action"))
        assertEquals("go", click.getString("button_id"))
        pumpUntil("closed") { presenter().shownWindow == null }
        assertEquals(listOf(Triple("c1", "go", null as Map<String, Any?>?)), recorder.clicked)
    }

    // open_store: "open the https store_url with ACTION_VIEW"
    @Test
    fun `I38 open_store opens the store url from sync`() {
        showWith(button("s", "Store", InAppCampaigns.openStore()))
        tapButton("s")
        val started = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(inApp.storeUrl, started.dataString)
        assertEquals("open_store", clickProperties().getString("action"))
    }

    // key_value: "the payload goes to onInAppMessageClicked"
    @Test
    fun `I38 key_value hands its payload to the listener and opens nothing`() {
        showWith(button("kv", "Deal", InAppCampaigns.keyValue(mapOf("deal" to "spring"))))
        tapButton("kv")
        pumpUntil("clicked") { recorder.of("clicked").isNotEmpty() }
        val call = recorder.of("clicked").single()
        assertEquals("kv", call.args[0])
        assertEquals(mapOf("deal" to "spring"), call.args[1])
        assertEquals("key_value", clickProperties().getString("action"))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // listener: "buttonId is null for a tap on the message"
    @Test
    fun `I38 a tap on the message itself has no button id`() {
        showOne(InAppCampaigns.campaign().tapAction(InAppCampaigns.keyValue(mapOf("k" to "v"))).build())
        view<View>(R.id.grovs_in_app_card).performClick(); pump()
        pumpUntil("clicked") { recorder.of("clicked").isNotEmpty() }
        val call = recorder.of("clicked").single()
        assertNull(call.args[0])
        assertEquals(mapOf("k" to "v"), call.args[1])
        val click = clickProperties()
        assertEquals("key_value", click.getString("action"))
        assertTrue(!click.has("button_id") || click.isNull("button_id") || click.getString("button_id").isEmpty())
    }

    // after open_url "it waits for the next trigger (screen view, foreground, resume)"
    @Test
    fun `I39 after open_url what waits shows only at the next trigger`() {
        start(
            InAppCampaigns.campaign().id("a").triggers(event("a")).buttons(button("go", "Go", InAppCampaigns.openUrl("https://grovs.io/x"))).build(),
            InAppCampaigns.campaign().id("b").priority(5).triggers(event("b")).build(),
        )
        Grovs.track("a"); pumpUntil("a shown") { recorder.shown == listOf("a") }
        Grovs.track("b"); settle(1_000)
        assertEquals("b waits while a is on screen", listOf("a"), recorder.shown)
        tapButton("go")
        assertEquals(Intent.ACTION_VIEW, shadowOf(application).nextStartedActivity.action)
        pumpUntil("a closed") { presenter().shownWindow == null }
        settle(3_000)
        assertEquals("b does not pop up after leaving for the link", listOf("a"), recorder.shown)
        Grovs.track("b"); pumpUntil("b at the next trigger") { recorder.shown == listOf("a", "b") }
    }

    // "If the host does not declare it on API 33+, treat the action as unsupported"
    @Test
    @Config(sdk = [33])
    fun `I40 a push permission campaign is unsupported on 33 without POST_NOTIFICATIONS declared`() {
        // The test manifest gets the permission from firebase-messaging; take it away from the package info.
        val packages = application.packageManager
        val info = packages.getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions = info.requestedPermissions.orEmpty().filter { it != "android.permission.POST_NOTIFICATIONS" }.toTypedArray()
        shadowOf(packages).installPackage(info)
        start(
            InAppCampaigns.campaign().id("perm").buttons(button("p", "Allow", InAppCampaigns.requestPushPermission())).build(),
            InAppCampaigns.campaign().id("control").triggers(event("other")).build(),
        )
        Grovs.track("cart_viewed"); settle(3_000)
        assertTrue(recorder.shown.isEmpty())
        Grovs.track("other"); pumpUntil("control") { recorder.shown == listOf("control") }
    }

    // on API 33+ the action needs the host's POST_NOTIFICATIONS declaration; this is the positive side
    @Test
    @Config(sdk = [33])
    fun `I40 a push permission campaign shows on 33 when the host declares POST_NOTIFICATIONS`() {
        val declared = application.packageManager.getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        assertTrue(declared.orEmpty().contains("android.permission.POST_NOTIFICATIONS"))
        showWith(button("p", "Allow", InAppCampaigns.requestPushPermission()))
        assertEquals(listOf("c1"), recorder.shown)
    }

    // API < 33: "the action opens Settings.ACTION_APP_NOTIFICATION_SETTINGS when disabled"
    @Test
    fun `I40 below 33 with notifications off the button opens the notification settings`() {
        val manager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(false)
        showWith(button("p", "Allow", InAppCampaigns.requestPushPermission()))
        tapButton("p")
        val started = shadowOf(application).nextStartedActivity
        assertNotNull("settings screen opened", started)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals("request_push_permission", clickProperties().getString("action"))
    }

    // "A campaign whose images are not all on disk is not_ready"
    @Test
    fun `I41 a campaign waits until its https images are cached`() {
        val url = "https://img.example/hero.png"
        start(
            InAppCampaigns.campaign().id("pic").template("interstitial_image").images(url, null).build(),
            InAppCampaigns.campaign().id("control").triggers(event("other")).build(),
        )
        Grovs.track("cart_viewed"); settle(3_000)
        assertTrue("not ready without the image", "pic" !in recorder.shown)
        Grovs.track("other"); pumpUntil("control") { recorder.shown.contains("control") }
        assertTrue("pic still not ready", "pic" !in recorder.shown)
    }
}
