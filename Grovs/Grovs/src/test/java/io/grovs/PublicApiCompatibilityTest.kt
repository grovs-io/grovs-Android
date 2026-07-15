package io.grovs

import android.app.Application
import org.junit.Assert.assertNotNull
import org.junit.Test

class PublicApiCompatibilityTest {

    private val booleanType = Boolean::class.javaPrimitiveType!!
    private val intType = Int::class.javaPrimitiveType!!

    @Test
    fun `legacy configure methods and default bridges remain present`() {
        val companionClass = Grovs.Companion.javaClass
        val legacyParameters = arrayOf(
            Application::class.java,
            String::class.java,
            booleanType,
            String::class.java,
        )

        assertNotNull(companionClass.getDeclaredMethod("configure", *legacyParameters))
        assertNotNull(Grovs::class.java.getDeclaredMethod("configure", *legacyParameters))

        assertNotNull(
            companionClass.getDeclaredMethod(
                "configure\$default",
                companionClass,
                *legacyParameters,
                intType,
                Any::class.java,
            )
        )
        assertNotNull(
            Grovs::class.java.getDeclaredMethod(
                "configure\$default",
                Grovs::class.java,
                *legacyParameters,
                intType,
                Any::class.java,
            )
        )
    }

    @Test
    fun `explicit five parameter configure overloads remain present`() {
        val parameters = arrayOf(
            Application::class.java,
            String::class.java,
            booleanType,
            String::class.java,
            booleanType,
        )

        assertNotNull(Grovs.Companion.javaClass.getDeclaredMethod("configure", *parameters))
        assertNotNull(Grovs::class.java.getDeclaredMethod("configure", *parameters))
    }
}
