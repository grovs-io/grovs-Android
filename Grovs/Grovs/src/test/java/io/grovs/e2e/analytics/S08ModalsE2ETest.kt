package io.grovs.e2e.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.grovs.Grovs
import io.grovs.e2e.E2ETestUtils
import io.grovs.e2e.ScreenTrackingTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Setup 08 — Dialogs & modals (DialogFragment / BottomSheetDialogFragment) over content.
 *
 * Contract: [io.grovs.handlers.VisibleFragmentResolver] skips DialogFragment leaves, so a modal is
 * never auto-tracked as a screen (regardless of primaryNavigationFragment). The resolver resolves
 * back to the underlying content, which is deduped, so showing a modal emits nothing. Record a modal
 * as a screen explicitly with Grovs.trackScreenView(...) — see the "manual trackScreenView" test.
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class S08ModalsE2ETest : ScreenTrackingTestBase() {

    private fun show(dialog: DialogFragment, activity: FragmentActivity, tag: String) {
        dialog.show(activity.supportFragmentManager, tag)
        activity.supportFragmentManager.executePendingTransactions()
        E2ETestUtils.processMainLooper()
    }

    private fun dismiss(dialog: DialogFragment, activity: FragmentActivity) {
        dialog.dismiss()
        activity.supportFragmentManager.executePendingTransactions()
        E2ETestUtils.processMainLooper()
    }

    private fun buildPrimary(): ActivityController<S08PrimaryHostActivity> =
        Robolectric.buildActivity(S08PrimaryHostActivity::class.java).create().start().resume()

    private fun buildPlain(): ActivityController<S08PlainHostActivity> =
        Robolectric.buildActivity(S08PlainHostActivity::class.java).create().start().resume()

    // tests

    // #1 Content shown (both host variants) -> [S08ContentFragment]
    @Test
    fun `01 content shown on primary-nav host emits content`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        buildPrimary()
        val actual = drainScreenNames()
        println("S08#1(primaryNav host) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    @Test
    fun `01b content shown on plain host emits content`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        buildPlain()
        val actual = drainScreenNames()
        println("S08#1(plain host) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #2 InfoDialog over content, content IS primaryNav -> resolver keeps content -> dialog ignored.
    // #4 dismiss -> content does NOT re-emit.
    @Test
    fun `02 04 info dialog over primary-nav content is ignored and content does not re-emit`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPrimary()
        settleAutomaticScreenResolution() // content emits first
        val dialog = S08InfoDialogFragment()
        show(dialog, controller.get(), "info")
        settleAutomaticScreenResolution()
        dismiss(dialog, controller.get())

        val actual = drainScreenNames()
        println("S08#2/#4(primaryNav) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #3 InfoDialog over plain (non-primaryNav) content -> dialog skipped, content stays.
    // #4 dismiss -> content does NOT re-emit.
    @Test
    fun `03 04 info dialog over plain content is not auto-tracked, dismiss does not re-emit`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution() // content emits first
        val dialog = S08InfoDialogFragment()
        show(dialog, controller.get(), "info")
        settleAutomaticScreenResolution()
        dismiss(dialog, controller.get())

        val actual = drainScreenNames()
        println("S08#3/#4(plain) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #5 BottomSheet over primaryNav content -> ignored. #7 expand/collapse -> nothing. #8 dismiss -> no re-emit.
    @Test
    fun `05 07 08 bottom sheet over primary-nav content ignored, state change and dismiss emit nothing`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPrimary()
        settleAutomaticScreenResolution()
        val sheet = S08MenuBottomSheetFragment()
        show(sheet, controller.get(), "menu")
        settleAutomaticScreenResolution()
        E2ETestUtils.processMainLooper() // #7 expand/collapse: no lifecycle callback fires
        settleAutomaticScreenResolution()
        dismiss(sheet, controller.get())

        val actual = drainScreenNames()
        println("S08#5/#7/#8(primaryNav) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #6 BottomSheet over plain content -> skipped, content stays. #7 state change -> nothing. #8 dismiss -> no re-emit.
    @Test
    fun `06 07 08 bottom sheet over plain content is not auto-tracked, state change and dismiss emit nothing`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution()
        val sheet = S08MenuBottomSheetFragment()
        show(sheet, controller.get(), "menu")
        settleAutomaticScreenResolution()
        E2ETestUtils.processMainLooper() // #7 expand/collapse: no lifecycle callback fires
        settleAutomaticScreenResolution()
        dismiss(sheet, controller.get())

        val actual = drainScreenNames()
        println("S08#6/#7/#8(plain) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #9 Stacked modals (dialog over dialog) -> both skipped, content stays.
    @Test
    fun `09 stacked dialogs on plain host are not auto-tracked`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution()
        val d1 = S08InfoDialogFragment()
        show(d1, controller.get(), "d1")
        settleAutomaticScreenResolution()
        val d2 = S08SecondDialogFragment()
        show(d2, controller.get(), "d2")
        settleAutomaticScreenResolution()

        val actual = drainScreenNames()
        println("S08#9(plain, stacked) actual=$actual")
        assertEquals(
            listOf("S08ContentFragment"),
            actual,
        )
    }

    // #10 Dialog hosting a child fragment -> whole dialog subtree skipped, content stays.
    @Test
    fun `10 dialog hosting a child fragment is not auto-tracked on plain host`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution()
        val dialog = S08DialogWithChildFragment()
        show(dialog, controller.get(), "childHost")
        settleAutomaticScreenResolution()

        val actual = drainScreenNames()
        println("S08#10(plain, dialog-with-child) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #11 Rotation while a dialog is shown -> recreate content + dialog and resume.
    @Test
    fun `11 rotation with dialog shown re-resolves after recreate`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution()
        val dialog = S08InfoDialogFragment()
        show(dialog, controller.get(), "info")
        settleAutomaticScreenResolution()

        controller.recreate()
        val actual = drainScreenNames()
        println("S08#11(plain, rotation) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #12 Background/foreground with dialog shown.
    @Test
    fun `12 background then foreground with dialog shown`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution()
        val dialog = S08InfoDialogFragment()
        show(dialog, controller.get(), "info")
        settleAutomaticScreenResolution()

        controller.pause().stop()
        settleAutomaticScreenResolution()
        controller.start().resume()

        val actual = drainScreenNames()
        println("S08#12(plain, bg/fg) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #13 Process-death restore with dialog shown -> not reproducible in Robolectric (recreate()
    // models a config change, not a real process kill + cold rebuild from savedInstanceState).

    // #14 Full-screen DialogFragment (STYLE_NO_FRAME) that acts like a screen.
    @Test
    fun `14 full-screen dialog is not auto-tracked - use trackScreenView for modal-as-screen`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution()
        val dialog = S08FullScreenDialogFragment()
        show(dialog, controller.get(), "fullscreen")
        settleAutomaticScreenResolution()

        val actual = drainScreenNames()
        println("S08#14(plain, full-screen dialog) actual=$actual")
        // Even a STYLE_NO_FRAME full-screen dialog is a DialogFragment -> skipped -> not auto-tracked.
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #14b The SAME full-screen dialog over primaryNav content is NOT tracked (proves no special casing).
    @Test
    fun `14b full-screen dialog over primary-nav content is ignored`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPrimary()
        settleAutomaticScreenResolution()
        val dialog = S08FullScreenDialogFragment()
        show(dialog, controller.get(), "fullscreen")
        settleAutomaticScreenResolution()

        val actual = drainScreenNames()
        println("S08#14b(primaryNav, full-screen dialog) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #15 Navigate the content underneath, then show a dialog.
    @Test
    fun `15 navigate content then show dialog on plain host`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution()
        val activity = controller.get()
        val second = S08SecondContentFragment()
        activity.supportFragmentManager.beginTransaction()
            .replace(activity.contentRootId, second, "content2")
            .commitNow()
        E2ETestUtils.processMainLooper()
        settleAutomaticScreenResolution()

        val dialog = S08InfoDialogFragment()
        show(dialog, activity, "info")
        settleAutomaticScreenResolution()

        val actual = drainScreenNames()
        println("S08#15(plain, navigate-then-dialog) actual=$actual")
        assertEquals(
            listOf("S08ContentFragment", "S08SecondContentFragment"),
            actual,
        )
    }

    // #16 Show dialog, dismiss, show again with the same tag -> dedup interplay.
    @Test
    fun `16 show dismiss show same dialog dedups the second show`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution()
        val first = S08InfoDialogFragment()
        show(first, controller.get(), "info")
        settleAutomaticScreenResolution()
        dismiss(first, controller.get())
        settleAutomaticScreenResolution()
        val second = S08InfoDialogFragment()
        show(second, controller.get(), "info")
        settleAutomaticScreenResolution()

        val actual = drainScreenNames()
        println("S08#16(plain, show/dismiss/show) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #17 Cancel via back (onCancel) vs dismiss() -> same (no) emission on the way out.
    @Test
    fun `17 cancel via back behaves like dismiss - neither re-emits content`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution()
        val dialog = S08InfoDialogFragment()
        show(dialog, controller.get(), "info")
        settleAutomaticScreenResolution()
        dialog.requireDialog().cancel() // equivalent to the back button -> onCancel -> dismiss
        controller.get().supportFragmentManager.executePendingTransactions()
        E2ETestUtils.processMainLooper()

        val actual = drainScreenNames()
        println("S08#17(plain, cancel-via-back) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }

    // #18 Manual trackScreenView as the recommended pattern for modals-as-screens.
    @Test
    fun `18 manual trackScreenView reports the modal as a screen`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        buildPrimary()
        settleAutomaticScreenResolution()
        Grovs.trackScreenView("InfoDialog")

        val actual = drainScreenNames()
        println("S08#18(manual trackScreenView) actual=$actual")
        assertEquals(listOf("S08ContentFragment", "InfoDialog"), actual)
    }

    // #19 Two distinct bottom sheets in sequence on plain host -> both skipped, content stays.
    @Test
    fun `19 two bottom sheets in sequence are not auto-tracked on plain host`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        val controller = buildPlain()
        settleAutomaticScreenResolution()
        val menu = S08MenuBottomSheetFragment()
        show(menu, controller.get(), "menu")
        settleAutomaticScreenResolution()
        dismiss(menu, controller.get())
        settleAutomaticScreenResolution()
        val share = S08ShareBottomSheetFragment()
        show(share, controller.get(), "share")
        settleAutomaticScreenResolution()

        val actual = drainScreenNames()
        println("S08#19(plain, two sheets) actual=$actual")
        assertEquals(
            listOf("S08ContentFragment"),
            actual,
        )
    }

    // #20 Modal shown during the Activity resume race (show inside onResume) -> callbacks coalesce
    // into one resolution, and since the modal is skipped, the underlying content is the screen.
    @Test
    fun `20 modal shown during resume race resolves to the content screen`() = runTest {
        configure()
        E2ETestUtils.getAuthenticationJob()?.join()

        Robolectric.buildActivity(S08ModalOnResumeActivity::class.java).create().start().resume()

        val actual = drainScreenNames()
        println("S08#20(modal-in-onResume race) actual=$actual")
        assertEquals(listOf("S08ContentFragment"), actual)
    }
}

// fixtures (all S08-prefixed)

/** Content fragment with a real, VISIBLE view so it is an eligible resolver leaf. */
open class S08ContentFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}

class S08SecondContentFragment : S08ContentFragment()

/** Base host that adds [S08ContentFragment]; subclasses decide whether it is the primary-nav fragment. */
open class S08HostActivity : AppCompatActivity() {
    open val setAsPrimaryNav: Boolean = true
    var contentRootId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply { id = View.generateViewId() }
        contentRootId = root.id
        setContentView(root)
        if (savedInstanceState == null) {
            val fragment = S08ContentFragment()
            val tx = supportFragmentManager.beginTransaction().add(root.id, fragment, "content")
            if (setAsPrimaryNav) tx.setPrimaryNavigationFragment(fragment)
            tx.commitNow()
        }
    }
}

class S08PrimaryHostActivity : S08HostActivity() {
    override val setAsPrimaryNav: Boolean = true
}

class S08PlainHostActivity : S08HostActivity() {
    override val setAsPrimaryNav: Boolean = false
}

/** Plain host (no primary-nav) that shows a dialog from inside onResume, to exercise the resume race. */
class S08ModalOnResumeActivity : AppCompatActivity() {
    private var shown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)
        if (savedInstanceState == null) {
            val fragment = S08ContentFragment()
            supportFragmentManager.beginTransaction().add(root.id, fragment, "content").commitNow()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!shown) {
            shown = true
            S08InfoDialogFragment().show(supportFragmentManager, "info")
            supportFragmentManager.executePendingTransactions()
        }
    }
}

open class S08InfoDialogFragment : DialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}

class S08SecondDialogFragment : S08InfoDialogFragment()

/** Full-screen dialog (STYLE_NO_FRAME) that visually acts like a normal screen. */
class S08FullScreenDialogFragment : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}

/** A dialog that hosts its own child fragment, so the resolver must descend into the dialog's childFM. */
class S08DialogWithChildFragment : DialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (savedInstanceState != null) return
        childFragmentManager.beginTransaction()
            .add(view.id, S08DialogChildLeafFragment(), "child-leaf")
            .commitNow()
    }
}

class S08DialogChildLeafFragment : S08ContentFragment()

open class S08MenuBottomSheetFragment : BottomSheetDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}

class S08ShareBottomSheetFragment : BottomSheetDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }
}
