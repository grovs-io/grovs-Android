package io.grovs.e2e

import io.grovs.Grovs
import io.grovs.handlers.GrovsManager
import io.grovs.service.GrovsService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttributeResyncE2ETest : DrivenBackendTestBase() {

    @Test
    fun `an unacknowledged attribute update is re-offered on the next foreground`() {
        backend.respond(authenticate, authOk)
        configure()
        pumpUntil("authentication") {
            manager().authenticationState == GrovsManager.AuthenticationState.AUTHENTICATED
        }

        backend.failWithStatus(attributes, code = 500, body = """{"error":"down"}""")
        backend.phase = "failing"
        Grovs.pushToken = "push-1"
        advanceUntil("the attribute update to spend its budget") {
            backend.count(attributes, "failing") >= GrovsService.MAX_ATTEMPTS.toInt()
        }

        backend.respond(attributes, "{}")
        backend.phase = "recovered"
        foreground()

        advanceUntil("the unacknowledged value to be re-offered") {
            backend.count(attributes, "recovered") >= 1
        }

        assertEquals(
            "push-1",
            JSONObject(backend.seen.last { it.path == attributes }.body).getString("push_token"),
        )
    }
}
