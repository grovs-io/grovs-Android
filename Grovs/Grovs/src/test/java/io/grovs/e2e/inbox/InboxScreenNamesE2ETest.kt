package io.grovs.e2e.inbox

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import io.grovs.e2e.ScreenTrackingTestBase
import io.grovs.e2e.TestFragment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class NotificationsMainFragment : TestFragment()
class NotificationsListFragment : TestFragment()
class NotificationDetailsFragment : TestFragment()
class AutoDisplayedNotificationFragment : TestFragment()

class NamedFragmentHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { id = View.generateViewId() }
        setContentView(root)
        val type = Class.forName(intent.getStringExtra(FRAGMENT)!!)
        val fragment = type.getDeclaredConstructor().newInstance() as Fragment
        supportFragmentManager.beginTransaction().add(root.id, fragment).setPrimaryNavigationFragment(fragment).commitNow()
    }

    companion object { const val FRAGMENT = "fragment" }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InboxScreenNamesE2ETest : ScreenTrackingTestBase() {

    private fun tracked(fragment: Class<out Fragment>): List<String> {
        configure()
        val intent = android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), NamedFragmentHostActivity::class.java)
            .putExtra(NamedFragmentHostActivity.FRAGMENT, fragment.name)
        Robolectric.buildActivity(NamedFragmentHostActivity::class.java, intent).setup()
        return drainScreenNames()
    }

    @Test fun `B6 NotificationsMainFragment is tracked`() {
        val names = tracked(NotificationsMainFragment::class.java)
        assertTrue(names.toString(), "NotificationsMainFragment" in names)
    }

    @Test fun `B6 NotificationsListFragment is tracked`() {
        val names = tracked(NotificationsListFragment::class.java)
        assertTrue(names.toString(), "NotificationsListFragment" in names)
    }

    @Test fun `B6 NotificationDetailsFragment is tracked`() {
        val names = tracked(NotificationDetailsFragment::class.java)
        assertTrue(names.toString(), "NotificationDetailsFragment" in names)
    }

    @Test fun `B6 AutoDisplayedNotificationFragment is tracked`() {
        val names = tracked(AutoDisplayedNotificationFragment::class.java)
        assertTrue(names.toString(), "AutoDisplayedNotificationFragment" in names)
    }
}
