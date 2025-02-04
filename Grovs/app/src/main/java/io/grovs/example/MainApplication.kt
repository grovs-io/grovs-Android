package io.grovs.example

import android.app.Application
import android.os.Build
import io.grovs.Grovs
import io.grovs.model.LogLevel

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // TODO: Replace with your own API Key
        val API_KEY = "grovst_06e36086dad3e934289560e3ca59527282030868f8c844629516c6e6c67bbf1f"
        Grovs.configure(this, API_KEY, useTestEnvironment = true)
        //Grovs.useTestEnvironment = true

        //Optionally, you can adjust the debug level for logging:
        Grovs.setDebug(LogLevel.INFO)

        Grovs.identifier = getDeviceInfo()
        Grovs.attributes = mapOf("param1" to "value1", "param2" to 123, "param3" to true)
    }

    fun getDeviceInfo(): String {
        val model = Build.MODEL        // Phone model, e.g., "Pixel 5"
        val manufacturer = Build.MANUFACTURER  // Manufacturer, e.g., "Google"
        return "@$manufacturer $model"
    }
}