package io.grovs.e2e

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.grovs.Grovs

/**
 * Test activity for E2E tests.
 * Integrates with Grovs SDK for lifecycle and deeplink handling.
 * Extends AppCompatActivity to implement LifecycleOwner, which is required
 * for the SDK's deeplink handling.
 */
class TestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set AppCompat theme before calling super.onCreate to avoid theme issues in Robolectric tests
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { Grovs.onNewIntent(it, this) }
    }

    override fun onStart() {
        super.onStart()
        Grovs.onStart(this)
    }
}
