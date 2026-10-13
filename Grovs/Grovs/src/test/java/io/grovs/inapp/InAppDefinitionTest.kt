package io.grovs.inapp

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.grovs.inapp.model.InAppAnswer
import io.grovs.inapp.model.InAppCampaign
import io.grovs.inapp.model.InAppCondition
import io.grovs.inapp.model.InAppDefinition
import io.grovs.inapp.model.InAppOrientation
import io.grovs.inapp.model.InAppQuestionType
import io.grovs.inapp.model.InAppTime
import io.grovs.inapp.model.InAppTemplate
import io.grovs.inapp.model.InAppTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppDefinitionTest {
    private val options = """[{"id":"a","text":"Search"},{"id":"b","text":"A friend"}]"""

    private fun obj(text: String) = InAppShared.obj(text)

    private fun campaign(changes: String): JsonObject = InAppShared.merge(InAppShared.baseCampaign(), obj(changes))

    private fun read(changes: String): InAppCampaign? = InAppDefinition.readCampaign(campaign(changes))

    private fun question(changes: String = "{}"): JsonObject = InAppShared.merge(
        obj("""{"id":"q1","type":"stars","options":[],"comment":null,"thanks":null,"review_from":null}"""), obj(changes),
    )

    private fun readQuestion(question: JsonElement, content: String = "{}", template: String = "interstitial"): InAppCampaign? {
        val changed = obj(content).also { it.add("question", question) }
        val over = JsonObject().apply {
            addProperty("template", template)
            add("content", changed)
        }
        return InAppDefinition.readCampaign(InAppShared.merge(InAppShared.baseCampaign(), over))
    }

    @Test
    fun theBaseCampaignReads() {
        val read = read("{}")!!
        assertEquals("c1", read.id)
        assertEquals(InAppTemplate.INTERSTITIAL, read.template)
        assertEquals(1, read.revision)
        assertEquals(listOf<InAppTrigger>(InAppTrigger.Event("cart_viewed", emptyList())), read.triggers)
        assertEquals(1788220800000L, read.createdAt)
        assertTrue(read.content.dismissible)
        assertEquals(InAppOrientation.ANY, read.content.orientation)
        assertFalse(read.reserved)
    }

    @Test
    fun timestampsMustBeRealCalendarTimesInTheContractForm() {
        for (written in listOf(
            "2026-02-30T00:00:00Z", "2026-13-01T00:00:00Z", "2026-09-01T24:00:00Z", "2026-09-01T00:00:60Z",
            "2026-09-01T00:00:00z", "2026-09-01T00:00:00.1234Z", "2026-09-01T00:00:00Z\nx", "2026-09-01T00:00:00Z\n",
            "2026-09-01", "٢026-09-01T00:00:00Z",
        )) {
            assertNull(written, InAppDefinition.readTimestamp(JsonPrimitive(written)))
        }
        assertNull(InAppDefinition.readTimestamp(JsonPrimitive(1788220800000L)))
        assertEquals(1835395200000L, InAppDefinition.readTimestamp(JsonPrimitive("2028-02-29T00:00:00Z")))
        assertEquals(1788220800500L, InAppDefinition.readTimestamp(JsonPrimitive("2026-09-01T00:00:00.5Z")))
        assertEquals(1788220800050L, InAppDefinition.readTimestamp(JsonPrimitive("2026-09-01T00:00:00.05Z")))
    }

    @Test
    fun timestampsRoundTripThroughTheCalendarMath() {
        val cases = mapOf(
            "2028-02-29T12:34:56Z" to 1835440496000L,
            "2100-03-01T00:00:00Z" to 4107542400000L,
            "2026-09-30T23:59:59Z" to 1790812799000L,
        )
        for ((written, millis) in cases) {
            assertEquals(written, millis, InAppDefinition.readTimestamp(JsonPrimitive(written)))
            assertEquals(written, InAppTime.dayOf(millis, 0), written.substring(0, 10))
        }
        assertNull(InAppDefinition.readTimestamp(JsonPrimitive("2100-02-29T00:00:00Z")))
        assertNull(InAppDefinition.readTimestamp(JsonPrimitive("2026-09-31T00:00:00Z")))
        assertNull(InAppDefinition.readTimestamp(JsonPrimitive("2026-04-31T00:00:00Z")))
    }

    @Test
    fun unknownFieldsAreIgnoredAndWholeFloatsRead() {
        assertNotNull(read("""{"brand_new":{"x":1},"content":{"sparkle":true},"format":1.0,"priority":2.0}"""))
    }

    @Test
    fun malformedCampaignsAreUnsupported() {
        val broken = listOf(
            """{"format":"1"}""", """{"id":""}""", """{"priority":1.5}""", """{"template":"popup"}""",
            """{"delay_seconds":-1}""", """{"delivery_mode":""}""", """{"created_at":"yesterday"}""",
            """{"triggers":[]}""", """{"triggers":[{"type":"app_open","name":null}]}""",
            """{"triggers":[{"type":"screen_view","filters":[]}]}""", """{"triggers":[{"type":"event"}]}""",
            """{"conditions":[{"type":"platform","op":"gt","value":"ios"}]}""",
            """{"conditions":[{"type":"app_version","op":"eq","value":""}]}""",
            """{"conditions":[{"type":"event_count","name":"x","op":"between","value":[1]}]}""",
            """{"conditions":[{"type":"user_attribute","key":"plan","op":"eq","value":[1]}]}""",
            """{"conditions":[{"type":"first_seen","op":"within_days","value":0}]}""",
            """{"conditions":[{"type":"weather"}]}""",
            """{"caps":{"session":0}}""", """{"caps":{"ignore_global":null}}""",
            """{"schedule":{"days":[]}}""", """{"schedule":{"days":[0]}}""", """{"schedule":{"from":"9:00","to":"17:00"}}""",
            """{"schedule":{"from":"09:00","to":null}}""", """{"schedule":{"end_at":"soon"}}""",
            """{"content":{"dismissible":"no"}}""", """{"content":{"orientation":"sideways"}}""",
            """{"content":{"title":5}}""", """{"content":{"buttons":null}}""",
            """{"content":{"buttons":[{"id":"b1","text":"Go","action":{"type":"open_url"}}]}}""",
            """{"content":{"tap_action":{"type":"key_value","payload":[]}}}""",
            """{"content":{"dismissible":false}}""",
        )
        for (changes in broken) assertNull(changes, read(changes))
    }

    @Test
    fun reservedFieldsMarkTheCampaign() {
        assertTrue(read("""{"variants":[]}""")!!.reserved)
        assertTrue(read("""{"control_group":{}}""")!!.reserved)
        assertFalse(read("""{"conversion":null}""")!!.reserved)
    }

    @Test
    fun conditionsReadTheirValues() {
        val read = read(
            """{"conditions":[{"type":"first_seen","op":"within_days","value":7},
               {"type":"user_attribute","key":"plan","op":"is_set"},
               {"type":"os_version","op":"between","value":["14","16"]}]}""",
        )!!
        assertEquals(InAppCondition.FirstSeenWithinDays(7), read.conditions[0])
        assertEquals(InAppCondition.UserAttribute("plan", "is_set", JsonNull.INSTANCE), read.conditions[1])
        assertEquals(InAppCondition.OsVersion("between", JsonParser.parseString("[\"14\",\"16\"]")), read.conditions[2])
    }

    @Test
    fun aQuestionOnAnAlertOrAPictureOnlyTemplateIsUnsupported() {
        for (template in InAppTemplate.values()) {
            if (template == InAppTemplate.HTML) continue
            val drawn = template != InAppTemplate.ALERT && !template.isImageOnly
            assertEquals(template.wire, drawn, readQuestion(question(), template = template.wire) != null)
            assertEquals(template.wire, drawn, readQuestion(question(), """{"dismissible":false}""", template.wire) != null)
            assertNotNull(template.wire, readQuestion(JsonNull.INSTANCE, template = template.wire))
        }
    }

    @Test
    fun readsEachKindOfQuestion() {
        val stars = readQuestion(
            question("""{"comment":{"split":3,"low":"What went wrong?","high":"What did you like?"},"thanks":"Thank you","review_from":4}"""),
        )!!.content.question!!
        assertEquals("q1", stars.id)
        assertEquals(InAppQuestionType.STARS, stars.type)
        assertTrue(stars.options.isEmpty())
        assertEquals(3, stars.comment!!.split)
        assertEquals("What went wrong?", stars.comment!!.low)
        assertEquals("What did you like?", stars.comment!!.high)
        assertEquals("Thank you", stars.thanks)
        assertEquals(4, stars.reviewFrom)

        val plain = readQuestion(question())!!.content.question!!
        assertNull(plain.comment)
        assertNull(plain.thanks)
        assertNull(plain.reviewFrom)

        val choice = readQuestion(question("""{"type":"choice","options":$options}"""))!!.content.question!!
        assertEquals(InAppQuestionType.CHOICE, choice.type)
        assertEquals(listOf("a", "b"), choice.options.map { it.id })
        assertEquals(listOf("Search", "A friend"), choice.options.map { it.text })
    }

    @Test
    fun aQuestionIsSomethingToTapInAMessageNobodyCanDismiss() {
        assertNotNull(readQuestion(question(), """{"dismissible":false}"""))
        assertNull(readQuestion(JsonNull.INSTANCE, """{"dismissible":false}"""))
    }

    @Test
    fun aMalformedQuestionIsUnsupported() {
        val six = (1..6).joinToString(",", "[", "]") { """{"id":"o$it","text":"Option $it"}""" }
        val malformed = listOf(
            "unknown type" to """{"type":"slider"}""",
            "no type" to """{"type":null}""",
            "no id" to """{"id":""}""",
            "choice with no option" to """{"type":"choice"}""",
            "choice with one option" to """{"type":"choice","options":[{"id":"a","text":"Search"}]}""",
            "choice with six options" to """{"type":"choice","options":$six}""",
            "an option without text" to """{"type":"choice","options":[{"id":"a","text":"Search"},{"id":"b"}]}""",
            "stars with options" to """{"options":$options}""",
            "nps is no longer a kind" to """{"type":"nps"}""",
            "options that are no list" to """{"options":"a"}""",
            "a comment that is no object" to """{"comment":"Why?"}""",
            "a split that is no number" to """{"comment":{"split":"3","low":"Why?","high":null}}""",
            "a prompt that is no text" to """{"comment":{"split":null,"low":5,"high":null}}""",
            "thanks that is no text" to """{"thanks":5}""",
            "review_from that is no number" to """{"review_from":"4"}""",
        )
        for ((name, changes) in malformed) assertNull(name, readQuestion(question(changes)))
        assertNull(readQuestion(JsonPrimitive("stars")))
        assertNull(readQuestion(JsonParser.parseString("[{}]")))
        val button = """{"buttons":[{"id":"b1","text":"Go","action":{"type":"close"}}]}"""
        assertNull(readQuestion(question(), button))
        assertNull(readQuestion(question(), """{"tap_action":{"type":"close"}}"""))
    }

    @Test
    fun thePromptFollowsTheSplit() {
        fun asked(comment: String, answer: InAppAnswer, type: String = "stars"): String? {
            val changes = if (type == "choice") """{"type":"choice","options":$options,"comment":$comment}""" else """{"comment":$comment}"""
            return readQuestion(question(changes))!!.content.question!!.prompt(answer)
        }
        val both = """{"split":3,"low":"Low","high":"High"}"""
        assertEquals("Low", asked(both, InAppAnswer.Stars(2)))
        assertEquals("Low", asked(both, InAppAnswer.Stars(3)))
        assertEquals("High", asked(both, InAppAnswer.Stars(4)))
        assertEquals("Low", asked("""{"split":null,"low":"Low","high":"High"}""", InAppAnswer.Stars(5)))
        assertNull(asked("""{"split":3,"low":"Low","high":null}""", InAppAnswer.Stars(4)))
        assertNull(asked("""{"split":3,"low":null,"high":"High"}""", InAppAnswer.Stars(3)))
        assertNull(asked("""{"split":3,"low":"","high":"High"}""", InAppAnswer.Stars(3)))
        assertNull(asked("null", InAppAnswer.Stars(3)))
        assertEquals("More", asked("""{"split":null,"low":"More","high":null}""", InAppAnswer.Option("a"), "choice"))
        assertEquals(4, InAppAnswer.Stars(4).value)
        assertEquals("a", InAppAnswer.Option("a").value)
    }

    @Test
    fun htmlParsingCases() {
        val cases = InAppShared.json("cases.json").getAsJsonArray("html_parsing")
        assertTrue(cases.size() > 0)
        for (item in cases) {
            val case = item.asJsonObject
            val given = case.getAsJsonObject("content")
            val raw = InAppShared.merge(InAppShared.baseCampaign(), JsonObject().apply {
                addProperty("template", "html")
                add("content", given)
            })
            // The case gives html and layout alone: one it leaves out must stay out.
            val content = raw.getAsJsonObject("content")
            for (key in listOf("html", "layout")) if (!given.has(key)) content.remove(key)
            val read = InAppDefinition.readCampaign(raw) != null
            assertEquals(case.get("name").asString, case.get("expect").asString, if (read) "valid" else "unsupported")
        }
    }

    @Test
    fun htmlContentDrawsNoQuestionAndKeepsDismissible() {
        val page = read("""{"template":"html","content":{"html":"<p>Hi</p>","layout":"full","dismissible":false}}""")!!
        assertEquals("<p>Hi</p>", page.content.page!!.html)
        assertFalse(page.content.dismissible)
        assertNull(read("""{"template":"html","content":{"html":"<p>Hi</p>","layout":"full","question":{}}}"""))
        assertNull(InAppDefinition.readPage(obj("""{"html":5,"layout":"full"}""")))
    }

    @Test
    fun noFieldOfAnyTypeMakesTheReaderThrow() {
        val shapes = listOf("null", "1", "1.5", "-1", "\"x\"", "\"\"", "true", "[]", "{}", "[null]", "[{}]", "1e400", "[1,2]")
        val base = InAppShared.baseCampaign()
        val paths = base.keySet().map { listOf(it) } +
            listOf("content", "caps", "schedule").flatMap { key -> base.getAsJsonObject(key).keySet().map { listOf(key, it) } } +
            listOf(listOf("content", "question"), listOf("content", "orientation"), listOf("content", "html"))
        for (path in paths) for (shape in shapes) {
            val raw = base.deepCopy()
            val parent = if (path.size == 1) raw else raw.getAsJsonObject(path[0])
            parent.add(path.last(), JsonParser.parseString(shape))
            InAppDefinition.readCampaign(raw)
            raw.addProperty("template", "html")
            InAppDefinition.readCampaign(raw)
        }
        for (shape in shapes) {
            InAppDefinition.readCampaign(campaign("""{"triggers":[{"type":"event","name":"x","filters":[$shape]}]}"""))
            InAppDefinition.readCampaign(campaign("""{"conditions":[{"type":"user_attribute","key":"k","op":"between","value":$shape}]}"""))
            InAppDefinition.readCampaign(campaign("""{"content":{"question":{"id":"q","type":"choice","options":[$shape,$shape]}}}"""))
            InAppDefinition.readCampaign(JsonParser.parseString(shape))
        }
    }
}
