package io.grovs.inapp.ui

import io.grovs.inapp.InAppCancellable
import io.grovs.inapp.InAppOutcome
import io.grovs.inapp.model.InAppAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppQuestionSessionTest {
    private class Timer(val delay: Long, val work: () -> Unit) : InAppCancellable {
        var cancelled = false
        override fun cancel() { cancelled = true }
        fun fire() { if (!cancelled) work() }
    }

    private val outcomes = mutableListOf<InAppOutcome>()
    private val answers = mutableListOf<InAppAnswer>()
    private val comments = mutableListOf<String>()
    private val timers = mutableListOf<Timer>()
    private val order = mutableListOf<String>()
    private var redraws = 0
    /** When set, the host closes the message as soon as it is told this. */
    private var closeAtAnswer = false
    private var closeAtComment = false
    private lateinit var session: InAppMessageSession

    private fun question(comment: String = """{"split":3,"low":"What went wrong?","high":"What did you like?"}""",
                         thanks: String = "\"Thank you!\"", type: String = "stars",
                         options: String = "[]") =
        """{"content":{"question":{"id":"q1","type":"$type","options":$options,"comment":$comment,"thanks":$thanks,"review_from":4}}}"""

    private fun start(changes: String = question()): InAppMessageSession {
        val campaign = InAppUiFixtures.campaign(changes)
        val presentation = InAppUiFixtures.presentation(campaign).copy(
            onAnswer = { answers += it; order += "answer"; if (closeAtAnswer) session.leave("app") },
            onComment = { comments += it; order += "comment"; if (closeAtComment) session.leave("app") },
        )
        session = InAppMessageSession(
            presentation,
            schedule = { delay, work -> Timer(delay, work).also { timers += it } },
        ) { outcomes += it; order += "outcome" }
        redraws = 0
        session.redraw = { redraws++ }
        return session
    }

    @Test
    fun oneTapAnswersAndASecondDoesNot() {
        start().answer(InAppAnswer.Stars(4))
        session.answer(InAppAnswer.Stars(2))
        assertEquals(listOf<InAppAnswer>(InAppAnswer.Stars(4)), answers)
    }

    @Test
    fun theCommentAskedFollowsTheSplit() {
        start().answer(InAppAnswer.Stars(4))
        assertEquals("What did you like?", (session.step as InAppStep.Comment).prompt)
        start().answer(InAppAnswer.Stars(3))
        assertEquals("What went wrong?", (session.step as InAppStep.Comment).prompt)
        start(question(comment = """{"split":null,"low":"Tell us more","high":"ignored"}""")).answer(InAppAnswer.Stars(5))
        assertEquals("Tell us more", (session.step as InAppStep.Comment).prompt)
        assertEquals(1, redraws)
    }

    @Test
    fun withNoCommentAndNoThanksAnAnswerClosesAtOnceAfterItIsTold() {
        start(question(comment = "null", thanks = "null")).answer(InAppAnswer.Stars(5))
        assertEquals(listOf("answer", "outcome"), order)
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(5), null), outcomes.single())
    }

    @Test
    fun anEmptyPromptAsksNothing() {
        start(question(comment = """{"split":null,"low":"","high":null}""")).answer(InAppAnswer.Stars(5))
        assertTrue(session.step is InAppStep.Thanks)
    }

    @Test
    fun aChoiceAnswerIsTheOptionId() {
        start(question(type = "choice", comment = "null",
            options = """[{"id":"o1","text":"Yes"},{"id":"o2","text":"No"}]""")).answer(InAppAnswer.Option("o2"))
        assertEquals(InAppAnswer.Option("o2"), answers.single())
        assertEquals(InAppStep.Thanks(InAppAnswer.Option("o2"), null), session.step)
    }

    @Test
    fun sendTrimsTheCommentAndKeepsFiveHundredUnits() {
        start().answer(InAppAnswer.Stars(5))
        session.draft("  " + "x".repeat(600) + "  ")
        assertEquals(500, (session.step as InAppStep.Comment).text.length)
        session.send()
        assertEquals("x".repeat(498), comments.single())
        assertEquals(listOf("answer", "comment"), order)
    }

    @Test
    fun anEmptySendReportsNoComment() {
        start().answer(InAppAnswer.Stars(5))
        session.draft("   \n ")
        session.send()
        assertTrue(comments.isEmpty())
        assertEquals(InAppStep.Thanks(InAppAnswer.Stars(5), null), session.step)
    }

    @Test
    fun skipSendsNoComment() {
        start().answer(InAppAnswer.Stars(1))
        session.draft("typed but skipped")
        session.skip()
        assertTrue(comments.isEmpty())
        assertTrue(session.step is InAppStep.Thanks)
    }

    @Test
    fun thanksAreShownForTwoSecondsThenTheMessageCloses() {
        start(question(comment = "null")).answer(InAppAnswer.Stars(5))
        assertEquals(InAppMessageSession.THANKS_MILLIS, timers.single().delay)
        assertTrue(outcomes.isEmpty())
        timers.single().fire()
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(5), null), outcomes.single())
    }

    @Test
    fun thanksFollowTheCommentAndCarryIt() {
        start().answer(InAppAnswer.Stars(5))
        session.draft("Great")
        session.send()
        assertEquals(InAppStep.Thanks(InAppAnswer.Stars(5), "Great"), session.step)
        timers.single().fire()
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(5), "Great"), outcomes.single())
    }

    @Test
    fun aHostClosingAtSendStillGetsTheCommentOnce() {
        closeAtComment = true
        start().answer(InAppAnswer.Stars(5))
        session.draft("Great")
        session.send()
        assertEquals(listOf("Great"), comments)
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(5), "Great"), outcomes.single())
        assertTrue(timers.isEmpty())
    }

    @Test
    fun aHostClosingAtTheAnswerStopsTheQuestion() {
        closeAtAnswer = true
        start().answer(InAppAnswer.Stars(5))
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(5), null), outcomes.single())
        assertTrue(session.step is InAppStep.Message)
    }

    @Test
    fun closingBeforeTheAnswerIsADismissalWithItsReason() {
        start().leave("backdrop")
        assertEquals(InAppOutcome.Dismissed("backdrop"), outcomes.single())
        session.answer(InAppAnswer.Stars(5))
        assertTrue(answers.isEmpty())
    }

    @Test
    fun closingDuringTheCommentKeepsTheAnswerAndSendsNoComment() {
        start().answer(InAppAnswer.Stars(2))
        session.draft("half typed")
        session.leave("app")
        assertEquals(InAppOutcome.Answered(InAppAnswer.Stars(2), null), outcomes.single())
        assertTrue(comments.isEmpty())
    }

    @Test
    fun closingDuringTheThanksKeepsTheAnswerAndStopsTheWait() {
        start(question(comment = "null")).answer(InAppAnswer.Stars(5))
        session.leave("close_button")
        assertTrue(timers.single().cancelled)
        timers.single().fire()
        assertEquals(1, outcomes.size)
    }

    @Test
    fun clipKeepsShortTextAsItIs() {
        assertEquals("hello", InAppCommentLimit.clip("hello"))
        assertEquals("x".repeat(500), InAppCommentLimit.clip("x".repeat(500)))
        assertEquals("x".repeat(500), InAppCommentLimit.clip("x".repeat(501)))
    }

    @Test
    fun clipNeverSplitsASurrogatePair() {
        val text = "x".repeat(499) + "😀" + "tail"
        val clipped = InAppCommentLimit.clip(text)
        assertEquals(499, clipped.length)
        assertTrue(clipped.none { Character.isSurrogate(it) })
    }

    @Test
    fun clipNeverSplitsACombiningSequence() {
        val text = "x".repeat(499) + "é" + "tail"
        assertEquals(499, InAppCommentLimit.clip(text).length)
    }

    @Test
    fun draftOutsideTheCommentStepIsIgnored() {
        start().draft("nothing asked yet")
        assertTrue(session.step is InAppStep.Message)
        assertNull(session.comment)
    }
}
