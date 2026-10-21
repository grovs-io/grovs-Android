package io.grovs.inapp

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.google.gson.JsonObject
import io.grovs.inapp.model.InAppDevice
import io.grovs.inapp.model.InAppJson
import io.grovs.inapp.model.InAppOrientation
import io.grovs.inapp.model.InAppPushPermission
import java.util.TimeZone

/**
 * The Android side of [InAppDevice], read again before every decision. Main thread. The parts that
 * cost system calls or a conversion are reused while they cannot have changed.
 */
internal class InAppDeviceFacts(
    private val context: Context,
    private val attributes: () -> Map<String, Any>?,
    private val activity: () -> Activity?,
    private val pushPermission: () -> InAppPushPermission = { notificationsPermission(context) },
    private val unsupportedActions: () -> List<String> = { emptyList() },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val appVersion: String by lazy {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun read(): InAppDevice = InAppDevice(
        platform = "android",
        appVersion = appVersion,
        osVersion = Build.VERSION.RELEASE ?: "",
        language = language(LocaleListCompat.getAdjustedDefault()[0]?.toLanguageTag()),
        firstSeenAt = null,
        utcOffsetMinutes = TimeZone.getDefault().getOffset(now()) / 60_000,
        user = user(),
        pushPermission = recentPushPermission(),
        unsupportedTemplates = emptyList(),
        unsupportedActions = unsupportedActions(),
        orientation = orientation(),
    )

    @Volatile
    private var unreadableLogged = false

    private var userSource: Map<String, Any>? = null
    private var userJson: JsonObject? = null

    private var permission: InAppPushPermission? = null
    private var permissionReadAt = 0L

    /** Every attribute change replaces the map, so the same map gives the same user. */
    private fun user(): JsonObject {
        val source = attributes()
        userJson?.takeIf { source === userSource }?.let { return it }
        val json = convert(source) ?: return unreadable()
        userSource = source
        userJson = json
        return json
    }

    /** Null when unreadable: the host may change its map on another thread, or put a map inside itself. */
    private fun convert(source: Map<String, Any>?): JsonObject? = try {
        InAppJson.fromHost(source.orEmpty()).takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    } catch (e: RuntimeException) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    /** Asking the system on every event would cost calls into it; a second is as fresh as a person can act. */
    private fun recentPushPermission(): InAppPushPermission {
        val at = now()
        permission?.takeIf { at - permissionReadAt in 0 until PERMISSION_FRESH_MS }?.let { return it }
        return pushPermission().also {
            permission = it
            permissionReadAt = at
        }
    }

    private fun unreadable(): JsonObject {
        if (!unreadableLogged) {
            unreadableLogged = true
            InAppLog.error("The user attributes could not be read for in-app messages; using none.")
        }
        return JsonObject()
    }

    private fun orientation(): InAppOrientation =
        if (activity()?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            InAppOrientation.LANDSCAPE
        } else {
            InAppOrientation.PORTRAIT
        }

    companion object {
        const val PERMISSION_FRESH_MS = 1_000L

        /** The primary language only, lowercased: "pt" for "pt-BR". A language tag already says "he", not "iw". */
        fun language(tag: String?): String = tag.orEmpty().split('-', '_').first().lowercase()

        /** Below API 33 there is nothing to ask, only a switch the person may have turned off. */
        fun notificationsPermission(context: Context): InAppPushPermission {
            val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (Build.VERSION.SDK_INT < 33) return if (enabled) InAppPushPermission.GRANTED else InAppPushPermission.DENIED
            val granted = ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") ==
                PackageManager.PERMISSION_GRANTED
            return if (granted && enabled) InAppPushPermission.GRANTED else InAppPushPermission.NOT_ASKED
        }
    }
}
