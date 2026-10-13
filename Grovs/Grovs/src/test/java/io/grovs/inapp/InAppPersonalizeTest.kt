package io.grovs.inapp

import com.google.gson.JsonObject
import io.grovs.inapp.model.InAppDefinition
import io.grovs.inapp.model.InAppEvent
import io.grovs.inapp.model.InAppEventType
import io.grovs.inapp.model.InAppLinkRule
import io.grovs.inapp.model.InAppPersonalize
import io.grovs.inapp.model.InAppReason
import io.grovs.inapp.model.InAppRendered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppPersonalizeTest {
    private val none = JsonObject()
    private fun obj(text: String) = InAppShared.obj(text)

    @Test
    fun fieldsTakeTheValueOrTheFallback() {
        val user = obj("""{"first_name":"Ana","age":31,"score":31.0,"nick":"","vip":true}""")
        assertEquals("Hi Ana", InAppPersonalize.fill("Hi {{ user.first_name }}", user, none))
        assertEquals("Hi Ana", InAppPersonalize.fill("Hi {{user.first_name}}", user, none))
        assertEquals("31 31 true", InAppPersonalize.fill("{{ user.age }} {{ user.score }} {{ user.vip }}", user, none))
        assertEquals("Hi friend", InAppPersonalize.fill("Hi {{ user.nick | default: \"friend\" }}", user, none))
        assertEquals("Hi friend", InAppPersonalize.fill("Hi {{ user.missing | default: \"friend\" }}", user, none))
        assertEquals("Hi ", InAppPersonalize.fill("Hi {{ user.missing | default: \"\" }}", user, none))
        assertEquals("Shoes", InAppPersonalize.fill("{{ event.item }}", none, obj("""{"item":"Shoes"}""")))
    }

    @Test
    fun aMissingValueWithNoFallbackOrStrayBracesFail() {
        assertNull(InAppPersonalize.fill("Hi {{ user.missing }}", none, none))
        assertNull(InAppPersonalize.fill("Hi {{ user.list }}", obj("""{"list":[1]}"""), none))
        assertNull(InAppPersonalize.fill("Hi {{ user.first-name }}", obj("""{"first-name":"Ana"}"""), none))
        assertNull(InAppPersonalize.fill("Hi {{ name }}", none, none))
        assertNull(InAppPersonalize.fill("Hi {{", none, none))
        assertEquals("Hi }}", InAppPersonalize.fill("Hi }}", none, none))
    }

    @Test
    fun aValueBroughtInIsNotFilledAgain() {
        assertEquals("{{ user.secret }}", InAppPersonalize.fill("{{ user.name }}", obj("""{"name":"{{ user.secret }}"}"""), none))
        assertEquals("Hi 😀 Ana!", InAppPersonalize.fill("Hi 😀 {{ user.name }}!", obj("""{"name":"Ana"}"""), none))
    }

    @Test
    fun linkValuesArePercentEncoded() {
        assertEquals(
            "https://acme.example/p?n=a%20b%26%C3%A9",
            InAppPersonalize.fill("https://acme.example/p?n={{ user.name }}", obj("""{"name":"a b&é"}"""), none, encode = true),
        )
    }

    @Test
    fun unsafeLinksAreJudgedOnTheTemplate() {
        assertTrue(InAppPersonalize.unsafe("https://{{ user.host }}/x", "https://evil.example/x"))
        assertTrue(InAppPersonalize.unsafe("{{ event.url }}", "https://acme.example"))
        assertTrue(InAppPersonalize.unsafe("{{ user.scheme }}://x", "https://x"))
        assertTrue(InAppPersonalize.unsafe("javascript:{{ user.x }}", "javascript:alert(1)"))
        assertTrue(InAppPersonalize.unsafe("DATA:text/html,x", "DATA:text/html,x"))
        assertTrue(InAppPersonalize.unsafe("/relative", "/relative"))
        assertFalse(InAppPersonalize.unsafe("https://acme.example/{{ user.p }}", "https://acme.example/p"))
        assertFalse(InAppPersonalize.unsafe("myapp://open", "myapp://open"))
        assertFalse(InAppPersonalize.unsafe("mailto:{{ user.mail }}", "mailto:a%40b"))
    }

    @Test
    fun renderReportsPersonalizationBeforeUnsafeLinks() {
        fun render(content: String): InAppPersonalize.Outcome {
            val raw = InAppShared.merge(InAppShared.baseCampaign(), obj(content))
            val campaign = InAppDefinition.readCampaign(raw)!!
            return InAppPersonalize.render(campaign, InAppEvent(InAppEventType.EVENT, "cart_viewed"), obj("""{"name":"Ana"}"""))
        }
        val button = { url: String -> """{"id":"b1","text":"Go {{ user.name }}","action":{"type":"open_url","url":"$url"}}""" }
        assertEquals(
            InAppPersonalize.Outcome.Rendered(InAppRendered("Hi Ana", "Welcome", listOf("Go Ana"), listOf("https://acme.example/Ana"))),
            render("""{"content":{"title":"Hi {{ user.name }}","buttons":[${button("https://acme.example/{{ user.name }}")}]}}"""),
        )
        assertEquals(
            InAppPersonalize.Outcome.Skipped(InAppReason.PERSONALIZATION),
            render("""{"content":{"title":"Hi {{ user.age }}","buttons":[${button("{{ user.host }}")}]}}"""),
        )
        assertEquals(
            InAppPersonalize.Outcome.Skipped(InAppReason.UNSAFE_LINK),
            render("""{"content":{"buttons":[${button("{{ user.name }}")}]}}"""),
        )
        assertEquals(
            InAppPersonalize.Outcome.Rendered(InAppRendered(null, "Welcome", emptyList(), emptyList())),
            render("""{"content":{"title":null}}"""),
        )
    }

    @Test
    fun linkRuleCases() {
        val cases = InAppShared.json("cases.json").getAsJsonArray("link_rule")
        assertTrue(cases.size() > 0)
        for (item in cases) {
            val case = item.asJsonObject
            val allowed = InAppLinkRule.allowed(case.get("input").asString) != null
            assertEquals(case.get("name").asString, case.get("expect").asString, if (allowed) "allowed" else "refused")
        }
    }

    @Test
    fun linkRuleReturnsTheNormalisedLink() {
        assertEquals("https://acme.example/path", InAppLinkRule.allowed(" HTTPS://acme.example/path\u0007"))
        assertEquals("myapp://open", InAppLinkRule.allowed("MyApp://open"))
        assertEquals("#top", InAppLinkRule.allowed("#top"))
        assertNull(InAppLinkRule.allowed("https:// acme.example"))
        assertEquals("https:// acme.example", InAppLinkRule.allowed("https:// acme.example"))
    }
}
