package io.grovs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.grovs.TestAssertions.assertEqualsWithContext
import io.grovs.TestAssertions.assertNotNullWithContext
import io.grovs.TestAssertions.assertTrueWithContext
import io.grovs.TestAssertions.assertFalseWithContext
import io.grovs.handlers.GrovsContext
import io.grovs.settings.GrovsSettings
import io.grovs.storage.LocalCache
import io.grovs.utils.InstantCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Core tests for storage classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StorageTest {

    private lateinit var context: Context
    private lateinit var localCache: LocalCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("grovs_storage", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        localCache = LocalCache(context)
    }

    @Test
    fun `LocalCache numberOfOpens persists across new LocalCache instances`() {
        localCache.numberOfOpens = 5

        assertEqualsWithContext(
            5,
            localCache.numberOfOpens,
            "numberOfOpens",
            "after setting to 5"
        )

        val newCache = LocalCache(context)
        assertEqualsWithContext(
            5,
            newCache.numberOfOpens,
            "numberOfOpens",
            "after creating new LocalCache instance (verifying persistence)"
        )
    }

    @Test
    fun `LocalCache resignTimestamp and lastStartTimestamp persist across new LocalCache instances`() {
        val timestamp = InstantCompat.ofEpochMilli(1702656000000L)

        localCache.resignTimestamp = timestamp
        localCache.lastStartTimestamp = timestamp

        val newCache = LocalCache(context)
        assertEqualsWithContext(
            timestamp.epochMillis,
            newCache.resignTimestamp!!.epochMillis,
            "resignTimestamp.epochMillis",
            "after creating new LocalCache instance (verifying persistence)"
        )
        assertEqualsWithContext(
            timestamp.epochMillis,
            newCache.lastStartTimestamp!!.epochMillis,
            "lastStartTimestamp.epochMillis",
            "after creating new LocalCache instance (verifying persistence)"
        )
    }

    @Test
    fun `GrovsSettings has debugLevel ERROR, useTestEnvironment false, sdkEnabled true by default`() {
        val settings = GrovsSettings()

        assertEqualsWithContext(
            io.grovs.model.LogLevel.ERROR,
            settings.debugLevel,
            "debugLevel",
            "when GrovsSettings newly constructed"
        )
        assertFalseWithContext(
            settings.useTestEnvironment,
            "useTestEnvironment",
            "when GrovsSettings newly constructed"
        )
        assertTrueWithContext(
            settings.sdkEnabled,
            "sdkEnabled",
            "when GrovsSettings newly constructed"
        )
    }

    @Test
    fun `GrovsContext stores grovsId, identifier, pushToken, attributes, and lastSeen properties`() {
        val grovsContext = GrovsContext()

        grovsContext.grovsId = "grovs-id"
        grovsContext.identifier = "user-id"
        grovsContext.pushToken = "token"
        grovsContext.attributes = mapOf("key" to "value")
        grovsContext.lastSeen = InstantCompat.now()

        assertEqualsWithContext(
            "grovs-id",
            grovsContext.grovsId,
            "grovsId",
            "after setting all properties"
        )
        assertEqualsWithContext(
            "user-id",
            grovsContext.identifier,
            "identifier",
            "after setting all properties"
        )
        assertEqualsWithContext(
            "token",
            grovsContext.pushToken,
            "pushToken",
            "after setting all properties"
        )
        assertEqualsWithContext(
            mapOf("key" to "value"),
            grovsContext.attributes,
            "attributes",
            "after setting all properties"
        )
        assertNotNullWithContext(
            grovsContext.lastSeen,
            "lastSeen",
            "after setting all properties"
        )
        assertNotNullWithContext(
            grovsContext.settings,
            "settings",
            "after construction (default settings)"
        )
    }

    @Test
    fun `GrovsContext getAppDetails returns non-null and getUserAgent returns non-empty string`() {
        val grovsContext = GrovsContext()

        assertNotNullWithContext(
            grovsContext.getAppDetails(context),
            "getAppDetails result",
            "when called with valid context"
        )
        assertTrueWithContext(
            grovsContext.getUserAgent(context).isNotEmpty(),
            "getUserAgent().isNotEmpty()",
            "when called with valid context"
        )
    }
}
