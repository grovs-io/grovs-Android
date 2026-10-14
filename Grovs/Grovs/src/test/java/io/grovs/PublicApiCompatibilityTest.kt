package io.grovs

import android.app.Application
import io.grovs.handlers.GrovsContext
import io.grovs.settings.GrovsSettings
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicApiCompatibilityTest {

    private val booleanType = Boolean::class.javaPrimitiveType!!
    private val intType = Int::class.javaPrimitiveType!!

    @Test
    fun `legacy configure methods and default bridges remain present`() {
        val companionClass = Grovs.Companion.javaClass
        val legacyParameters = arrayOf(
            Application::class.java,
            String::class.java,
            booleanType,
            String::class.java,
        )

        assertNotNull(companionClass.getDeclaredMethod("configure", *legacyParameters))
        assertNotNull(Grovs::class.java.getDeclaredMethod("configure", *legacyParameters))

        assertNotNull(
            companionClass.getDeclaredMethod(
                "configure\$default",
                companionClass,
                *legacyParameters,
                intType,
                Any::class.java,
            )
        )
        assertNotNull(
            Grovs::class.java.getDeclaredMethod(
                "configure\$default",
                Grovs::class.java,
                *legacyParameters,
                intType,
                Any::class.java,
            )
        )
    }

    @Test
    fun `explicit five parameter configure overloads remain present`() {
        val parameters = arrayOf(
            Application::class.java,
            String::class.java,
            booleanType,
            String::class.java,
            booleanType,
        )

        assertNotNull(Grovs.Companion.javaClass.getDeclaredMethod("configure", *parameters))
        assertNotNull(Grovs::class.java.getDeclaredMethod("configure", *parameters))
    }

    @Test
    fun `six parameter configure overload with clipboard domains is present`() {
        val parameters = arrayOf(
            Application::class.java,
            String::class.java,
            booleanType,
            String::class.java,
            booleanType,
            List::class.java,
        )

        assertNotNull(Grovs.Companion.javaClass.getDeclaredMethod("configure", *parameters))
        assertNotNull(Grovs::class.java.getDeclaredMethod("configure", *parameters))
    }

    @Test
    fun `seven parameter configure overload with enabled is present`() {
        val parameters = arrayOf(
            Application::class.java,
            String::class.java,
            booleanType,
            String::class.java,
            booleanType,
            java.util.List::class.java,
            booleanType,
        )

        assertNotNull(Grovs.Companion.javaClass.getDeclaredMethod("configure", *parameters))
        assertNotNull(Grovs::class.java.getDeclaredMethod("configure", *parameters))
    }

    @Test
    fun `settings and context keep their public constructors and consent accessors`() {
        // sdkEnabled is now a view of the internal consent controller; its accessors must not change.
        assertNotNull(GrovsSettings::class.java.getConstructor())
        assertEquals(booleanType, GrovsSettings::class.java.getMethod("getSdkEnabled").returnType)
        assertNotNull(GrovsSettings::class.java.getMethod("setSdkEnabled", booleanType))

        assertNotNull(GrovsContext::class.java.getConstructor())
        assertNotNull(GrovsContext::class.java.getConstructor(CoroutineDispatcher::class.java))
        assertNotNull(
            GrovsContext::class.java.getConstructor(
                CoroutineDispatcher::class.java,
                intType,
                Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
            )
        )
        assertEquals(GrovsSettings::class.java, GrovsContext::class.java.getMethod("getSettings").returnType)
    }

    @Test
    fun `push campaign api is present`() {
        val companion = Grovs.Companion.javaClass
        assertNotNull(companion.getDeclaredMethod("isGrovsNotification", Map::class.java))
        assertNotNull(companion.getDeclaredMethod("isGrovsNotification", android.content.Intent::class.java))
        assertNotNull(companion.getDeclaredMethod("handleNotificationOpen", android.content.Intent::class.java))
        assertNotNull(companion.getDeclaredMethod("getPushToken"))
        assertNotNull(companion.getDeclaredMethod("setPushToken", String::class.java))

        val messaging = GrovsMessaging::class.java
        val remoteMessage = com.google.firebase.messaging.RemoteMessage::class.java
        assertNotNull(messaging.getDeclaredMethod("handleMessage", android.content.Context::class.java, remoteMessage))
        assertNotNull(messaging.getDeclaredMethod("onNewToken", String::class.java))
        assertNotNull(messaging.getDeclaredMethod("setShowForegroundNotifications", booleanType))

        val legacy = Class.forName("io.grovs.handlers.MessagingServiceKt")
        assertNotNull(
            legacy.getDeclaredMethod(
                "handleGrovsNotification",
                com.google.firebase.messaging.FirebaseMessagingService::class.java,
                remoteMessage,
            )
        )
    }

    @Test
    fun `in-app message api is present`() {
        val companion = Grovs.Companion.javaClass
        val listener = GrovsInAppListener::class.java
        assertNotNull(companion.getDeclaredMethod("setInAppListener", listener))
        assertNotNull(companion.getDeclaredMethod("pauseInAppMessages"))
        assertNotNull(companion.getDeclaredMethod("resumeInAppMessages"))
        assertNotNull(companion.getDeclaredMethod("discardInAppMessages"))
        assertNotNull(companion.getDeclaredMethod("discardInAppMessages", booleanType))
        assertNotNull(companion.getDeclaredMethod("setInAppExcludedScreens", List::class.java))
        assertNotNull(companion.getDeclaredMethod("setInAppDebugLogging", booleanType))

        assertNotNull(listener.getDeclaredMethod("shouldShowInAppMessage", String::class.java))
        assertNotNull(listener.getDeclaredMethod("onInAppMessageShown", String::class.java))
        assertNotNull(listener.getDeclaredMethod("onInAppMessageClicked", String::class.java, String::class.java, Map::class.java))
        assertNotNull(listener.getDeclaredMethod("onInAppMessageDismissed", String::class.java, String::class.java))
        assertNotNull(listener.getDeclaredMethod("onInAppMessageAnswered", String::class.java, Any::class.java, String::class.java))
        assertNotNull(listener.getDeclaredMethod("onInAppMessageFailed", String::class.java, String::class.java))

        // Java apps extend the adapter, since JVM 1.8 interface defaults are not Java defaults.
        val adapter = GrovsInAppListenerAdapter::class.java
        assertTrue(listener.isAssignableFrom(adapter))
        assertFalse(java.lang.reflect.Modifier.isFinal(adapter.modifiers))
        assertNotNull(adapter.getConstructor())
    }
}
