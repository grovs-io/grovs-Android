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

/** The Android side of [InAppDevice], read again before every decision. */
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
        pushPermission = pushPermission(),
        unsupportedTemplates = emptyList(),
        unsupportedActions = unsupportedActions(),
        orientation = orientation(),
    )

    @Volatile
    private var unreadableLogged = false

    /** The host may change its map on another thread, or put a map inside itself. */
    private fun user(): JsonObject = try {
        InAppJson.fromHost(attributes().orEmpty()).takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    } catch (e: RuntimeException) {
        unreadable()
    } catch (e: StackOverflowError) {
        unreadable()
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
