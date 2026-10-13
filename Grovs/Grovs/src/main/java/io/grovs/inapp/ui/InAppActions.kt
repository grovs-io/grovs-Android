package io.grovs.inapp.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.grovs.inapp.model.InAppActionType
import io.grovs.inapp.model.InAppPushPermission
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import java.util.UUID

private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
private const val PLAY_STORE = "com.android.vending"
private const val PREFS = "grovs_in_app"
private const val ASKED = "push_asked"

internal class InAppActions(
    private val context: Context,
    private val activity: () -> Activity?,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val declares: (String) -> Boolean = { declaredPermission(context, it) },
    private val requestPermission: (Activity, (Boolean) -> Unit) -> Unit = ::requestNotificationPermission,
    private val reviewAvailable: Boolean = playReviewPresent(),
    private val review: (Activity, () -> Unit) -> Unit = { host, done -> InAppPlayReview.launch(host, done) },
) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun open(url: String) {
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun openStore(storeUrl: String) {
        val uri = Uri.parse(storeUrl)
        if (!start(Intent(Intent.ACTION_VIEW, uri).setPackage(PLAY_STORE))) start(Intent(Intent.ACTION_VIEW, uri))
    }

    val canRequestReview: Boolean get() = reviewAvailable

    fun requestReview(done: () -> Unit) {
        val host = activity()
        if (!reviewAvailable || host == null) return done()
        review(host, done)
    }

    private fun notificationsOn() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun permissionGranted() =
        ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun rationale(host: Activity?) =
        host != null && ActivityCompat.shouldShowRequestPermissionRationale(host, POST_NOTIFICATIONS)

    /** Android cannot tell "never asked" from "refused for good", so a request the SDK made counts as asked. */
    fun pushPermission(): InAppPushPermission {
        if (sdkInt < 33) return if (notificationsOn()) InAppPushPermission.GRANTED else InAppPushPermission.DENIED
        if (permissionGranted()) return if (notificationsOn()) InAppPushPermission.GRANTED else InAppPushPermission.DENIED
        val asked = prefs.getBoolean(ASKED, false) || rationale(activity())
        return if (asked) InAppPushPermission.DENIED else InAppPushPermission.NOT_ASKED
    }

    /** On Android 13 the app must declare the permission; the SDK never declares it for the app. */
    fun canRequestPushPermission(): Boolean = sdkInt < 33 || declares(POST_NOTIFICATIONS)

    /** What an html page reads as `grovs.capabilities.pushPermission`. */
    fun pushCapability(): String = when {
        !canRequestPushPermission() -> "unsupported"
        else -> when (pushPermission()) {
            InAppPushPermission.GRANTED -> "granted"
            InAppPushPermission.DENIED -> "denied"
            InAppPushPermission.NOT_ASKED -> "prompt"
        }
    }

    fun unsupportedActions(): List<String> = listOfNotNull(
        InAppActionType.REQUEST_APP_RATING.wire.takeUnless { canRequestReview },
        InAppActionType.REQUEST_PUSH_PERMISSION.wire.takeUnless { canRequestPushPermission() },
    )

    fun requestPushPermission(done: (result: String) -> Unit) {
        if (!canRequestPushPermission()) return done("unsupported")
        val host = activity()
        val askable = sdkInt >= 33 && !permissionGranted() && host != null &&
            (!prefs.getBoolean(ASKED, false) || rationale(host))
        when {
            pushPermission() == InAppPushPermission.GRANTED -> done("granted")
            askable -> {
                prefs.edit().putBoolean(ASKED, true).apply()
                requestPermission(host!!) { granted -> done(if (granted && notificationsOn()) "granted" else "denied") }
            }
            else -> {
                openNotificationSettings()
                done("denied")
            }
        }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
        start(intent)
    }

    private fun start(intent: Intent): Boolean {
        val host = activity()
        if (host == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (host ?: context).startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            DebugLogger.instance.log(LogLevel.INFO, "In-app: nothing opens ${intent.data ?: intent.action}")
            false
        } catch (e: SecurityException) {
            DebugLogger.instance.log(LogLevel.INFO, "In-app: not allowed to open ${intent.data ?: intent.action}")
            false
        }
    }

    companion object {
        fun playReviewPresent(): Boolean = try {
            Class.forName("com.google.android.play.core.review.ReviewManagerFactory")
            true
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: LinkageError) {
            false
        }
    }
}

private fun declaredPermission(context: Context, permission: String): Boolean = try {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions?.contains(permission) == true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

/** Asks the system; a host that is not a ComponentActivity gets the answer, so it is read again once the host comes back. */
internal fun requestNotificationPermission(host: Activity, done: (Boolean) -> Unit) {
    if (host is ComponentActivity) {
        var launcher: ActivityResultLauncher<String>? = null
        launcher = host.activityResultRegistry.register(
            "grovs_in_app_push_${UUID.randomUUID()}", ActivityResultContracts.RequestPermission(),
        ) { granted ->
            launcher?.unregister()
            done(granted)
        }
        launcher.launch(POST_NOTIFICATIONS)
        return
    }
    ActivityCompat.requestPermissions(host, arrayOf(POST_NOTIFICATIONS), 0x6E0)
    host.application.registerActivityLifecycleCallbacks(object : InAppActivityCallbacks() {
        override fun onActivityResumed(activity: Activity) {
            if (activity !== host) return
            host.application.unregisterActivityLifecycleCallbacks(this)
            done(ContextCompat.checkSelfPermission(host, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        }
    })
}
