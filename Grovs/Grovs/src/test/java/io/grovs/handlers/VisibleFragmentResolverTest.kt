package io.grovs.handlers

import io.grovs.e2e.HiddenSiblingFragment
import io.grovs.e2e.NestedFragmentHostTestActivity
import io.grovs.e2e.OffscreenSiblingFragment
import io.grovs.e2e.TestActivity
import io.grovs.e2e.VisibleLeafFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VisibleFragmentResolverTest {

    @Test
    fun `nested hierarchy resolves the visible leaf and ignores hidden and offscreen siblings`() {
        val activity = Robolectric.buildActivity(NestedFragmentHostTestActivity::class.java)
            .create().start().resume().get()

        val container = activity.supportFragmentManager.primaryNavigationFragment!!
        val children = container.childFragmentManager.fragments
        assertTrue(children.filterIsInstance<HiddenSiblingFragment>().single().isHidden)
        assertFalse(children.filterIsInstance<OffscreenSiblingFragment>().single().userVisibleHint)

        val leaf = VisibleFragmentResolver.findVisibleLeaf(activity.supportFragmentManager)

        assertEquals(VisibleLeafFragment::class.java, leaf?.javaClass)
    }

    @Test
    fun `manager without visible fragments resolves null`() {
        val activity = Robolectric.buildActivity(TestActivity::class.java)
            .create().start().resume().get()

        assertNull(VisibleFragmentResolver.findVisibleLeaf(activity.supportFragmentManager))
    }
}
