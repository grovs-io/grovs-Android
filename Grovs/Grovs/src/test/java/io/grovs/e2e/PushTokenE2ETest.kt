package io.grovs.e2e

import io.grovs.Grovs
import io.grovs.GrovsMessaging
import io.grovs.handlers.MessagingService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushTokenE2ETest : DrivenBackendTestBase() {

    private val authWithIdentity =
        """{"linksquared":"test-grovs-id","uri_scheme":"testapp","sdk_identifier":"user-1","sdk_attributes":{"plan":"pro"}}"""

    private fun firstAttributesBody(): JSONObject {
        pumpUntil("the attributes to be sent") { backend.count(attributes) >= 1 }
        return JSONObject(backend.seen.first { it.path == attributes }.body)
    }

    @Test
    fun `a token set before configure is applied by configure`() {
        backend.respond(authenticate, authOk)
        backend.respond(attributes, "{}")

        Grovs.pushToken = "fcm-token"
        configure()
        foreground()

        assertEquals("fcm-token", firstAttributesBody().getString("push_token"))
    }

    @Test
    fun `a token before authentication keeps the server identity`() {
        backend.respond(authenticate, authWithIdentity)
        backend.respond(attributes, "{}")

        Grovs.pushToken = "fcm-token"
        configure()
        foreground()

        val body = firstAttributesBody()
        assertEquals("fcm-token", body.getString("push_token"))
        assertEquals("user-1", body.getString("sdk_identifier"))
        assertEquals("pro", body.getJSONObject("sdk_attributes").getString("plan"))
    }

    @Test
    fun `onNewToken sets the push token`() {
        GrovsMessaging.onNewToken("from-messaging")
        assertEquals("from-messaging", Grovs.pushToken)

        MessagingService().onNewToken("from-service")
        assertEquals("from-service", Grovs.pushToken)
    }
}
