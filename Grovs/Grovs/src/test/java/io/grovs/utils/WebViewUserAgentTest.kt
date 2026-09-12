package io.grovs.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebViewUserAgentTest {

    private val context = RuntimeEnvironment.getApplication()
    private var now = 0L
    private var builds = 0

    @Before
    fun setUp() {
        WebViewUtils.resetForTests()
        WebViewUtils.elapsedMs = { now }
    }

    @After
    fun tearDown() {
        WebViewUtils.resetForTests()
        WebViewUtils.elapsedMs = WebViewUtils.defaultElapsedMs
        WebViewUtils.buildUserAgent = WebViewUtils.defaultBuildUserAgent
    }

    @Test
    fun `a working WebView is built once`() {
        WebViewUtils.buildUserAgent = { builds++; "real-agent" }
        repeat(5) { assertEquals("real-agent", WebViewUtils.getUserAgent(context)) }
        assertEquals(1, builds)
    }

    @Test
    fun `a failing WebView is not rebuilt on every call`() {
        WebViewUtils.buildUserAgent = { builds++; throw IllegalStateException("no WebView package") }
        repeat(5) { WebViewUtils.getUserAgent(context) }
        assertEquals("one try, then the fallback", 1, builds)
    }

    @Test
    fun `a failing WebView is tried again after five minutes`() {
        WebViewUtils.buildUserAgent = { builds++; throw IllegalStateException("updating") }
        WebViewUtils.getUserAgent(context)
        now += 300_000
        WebViewUtils.buildUserAgent = { builds++; "real-agent" }

        assertEquals("real-agent", WebViewUtils.getUserAgent(context))
        assertEquals(2, builds)
    }
}
