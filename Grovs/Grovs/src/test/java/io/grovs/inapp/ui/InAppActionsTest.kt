package io.grovs.inapp.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.testing.FakeReviewManager
import io.grovs.inapp.model.InAppPushPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28, 34])
class InAppActionsTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var activity: ComponentActivity
    private val push = "android.permission.POST_NOTIFICATIONS"
    private val notifications get() = app.getSystemService(android.app.NotificationManager::class.java)

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        app.getSharedPreferences("grovs_in_app", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        InAppPlayReview.managerFactory = { ReviewManagerFactory.create(it) }
    }

    private fun actions(
        sdkInt: Int = 34,
        declares: Boolean = true,
        reviewAvailable: Boolean = true,
        request: (Activity, (Boolean) -> Unit) -> Unit = { _, done -> done(true) },
        review: (Activity, () -> Unit) -> Unit = { _, done -> done() },
    ) = InAppActions(app, { activity }, sdkInt, { declares }, request, reviewAvailable, review)

    private fun started(): Intent? = shadowOf(activity).nextStartedActivity

    @Test
    fun openUrlStartsAViewIntent() {
        actions().open("https://acme.example/sale")
        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://acme.example/sale"), intent.data)
    }

    @Test
    fun openUrlWithNothingToHandleItOnlyLogs() {
        shadowOf(app).checkActivities(true)
        actions().open("myapp://nowhere")
        assertNull(started())
    }

    @Test
    fun theStoreOpensInPlayFirstThenAnyHandler() {
        val store = "https://play.google.com/store/apps/details?id=io.acme"
        shadowOf(app).checkActivities(true)
        addViewHandler(ComponentName("com.browser", "Browser"))
        actions().openStore(store)
        val intent = started()!!
        assertNull(intent.`package`)
        assertEquals(Uri.parse(store), intent.data)
    }

    @Test
    fun theStoreOpensInPlayWhenPlayIsThere() {
        val store = "https://play.google.com/store/apps/details?id=io.acme"
        shadowOf(app).checkActivities(true)
        addViewHandler(ComponentName("com.android.vending", "Store"))
        actions().openStore(store)
        val intent = started()!!
        assertEquals("com.android.vending", intent.`package`)
        assertEquals(Uri.parse(store), intent.data)
    }

    private fun addViewHandler(component: ComponentName) {
        val packages = shadowOf(app.packageManager)
        packages.addActivityIfNotPresent(component)
        packages.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("https")
        })
    }

    @Test
    fun withoutPlayReviewRatingIsUnsupportedAndTheReviewStepEndsAtOnce() {
        val actions = actions(reviewAvailable = false)
        assertFalse(actions.canRequestReview)
        assertTrue("request_app_rating" in actions.unsupportedActions())
        var done = false
        actions.requestReview { done = true }
        assertTrue(done)
    }

    @Test
    fun thePlayReviewFlowEndsWithItsCompletion() {
        InAppPlayReview.managerFactory = { FakeReviewManager(it) }
        var done = false
        InAppActions(app, { activity }).requestReview { done = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(done)
    }

    @Test
    fun belowAndroid13PushFollowsTheNotificationsSwitch() {
        val actions = actions(sdkInt = 28, declares = false)
        assertTrue(actions.canRequestPushPermission())
        shadowOf(notifications).setNotificationsEnabled(true)
        assertEquals(InAppPushPermission.GRANTED, actions.pushPermission())
        shadowOf(notifications).setNotificationsEnabled(false)
        assertEquals(InAppPushPermission.DENIED, actions.pushPermission())
        var result: String? = null
        actions.requestPushPermission { result = it }
        assertEquals("denied", result)
        val settings = started()!!
        assertTrue(settings.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS ||
            settings.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    @Test
    @Config(sdk = [34])
    fun onAndroid13AnUndeclaredPermissionIsUnsupported() {
        val actions = actions(declares = false)
        assertFalse(actions.canRequestPushPermission())
        assertTrue("request_push_permission" in actions.unsupportedActions())
        var result: String? = null
        actions.requestPushPermission { result = it }
        assertEquals("unsupported", result)
        assertEquals("unsupported", actions.pushCapability())
    }

    @Test
    @Config(sdk = [34])
    fun onAndroid13NeverAskedIsNotAskedAndAskingRemembersIt() {
        var asked = 0
        val actions = actions(request = { _, done -> asked++; done(false) })
        assertEquals(InAppPushPermission.NOT_ASKED, actions.pushPermission())
        assertEquals("prompt", actions.pushCapability())
        var result: String? = null
        actions.requestPushPermission { result = it }
        assertEquals(1, asked)
        assertEquals("denied", result)
        assertEquals(InAppPushPermission.DENIED, actions.pushPermission())
    }

    @Test
    @Config(sdk = [34])
    fun onAndroid13AGrantedPermissionIsGranted() {
        shadowOf(app).grantPermissions(push)
        shadowOf(notifications).setNotificationsEnabled(true)
        var result: String? = null
        actions().requestPushPermission { result = it }
        assertEquals("granted", result)
        assertEquals(InAppPushPermission.GRANTED, actions().pushPermission())
    }

    @Test
    @Config(sdk = [34])
    fun onAndroid13DeniedForGoodOpensTheSettings() {
        app.getSharedPreferences("grovs_in_app", 0).edit().putBoolean("push_asked", true).commit()
        var asked = 0
        var result: String? = null
        actions(request = { _, done -> asked++; done(true) }).requestPushPermission { result = it }
        assertEquals(0, asked)
        assertEquals("denied", result)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started()!!.action)
    }

    @Test
    @Config(sdk = [34])
    fun theRealRequestAsksTheSystemThroughTheActivity() {
        requestNotificationPermission(activity) { }
        assertTrue(shadowOf(activity).lastRequestedPermission.requestedPermissions.contains(push))
    }

    @Test
    @Config(sdk = [34])
    fun aPlainActivityDestroyedBeforeItResumesStillAnswersAndLetsGo() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val before = lifecycleCallbacks()
        var answers = 0
        requestNotificationPermission(controller.get()) { answers++ }
        assertEquals(before + 1, lifecycleCallbacks())
        controller.pause().stop().destroy()
        assertEquals(1, answers)
        assertEquals(before, lifecycleCallbacks())
    }

    private fun lifecycleCallbacks(): Int {
        val field = android.app.Application::class.java.getDeclaredField("mActivityLifecycleCallbacks")
        field.isAccessible = true
        return (field.get(app) as java.util.ArrayList<*>).size
    }
}
