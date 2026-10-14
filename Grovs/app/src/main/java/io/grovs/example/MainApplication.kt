package io.grovs.example

import android.app.Application
import android.os.Build
import android.util.Log
import io.grovs.Grovs
import io.grovs.GrovsInAppListener
import io.grovs.model.LogLevel

class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        Grovs.setInAppListener(SampleInAppListener)
        Grovs.setInAppExcludedScreens(listOf("SecondActivity"))
        Grovs.setInAppDebugLogging(true)

        // TODO: Replace with your own API Key
        val API_KEY = "grovst_06e36086dad3e934289560e3ca59527282030868f8c844629516c6e6c67bbf1f"
        Grovs.configure(application = this, apiKey = API_KEY, useTestEnvironment = true)
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

private object SampleInAppListener : GrovsInAppListener {
    private const val TAG = "GrovsInApp"

    override fun onInAppMessageShown(campaignId: String) {
        Log.d(TAG, "shown $campaignId")
    }

    override fun onInAppMessageClicked(campaignId: String, buttonId: String?, payload: Map<String, Any?>?) {
        Log.d(TAG, "clicked $campaignId button=$buttonId payload=$payload")
    }

    override fun onInAppMessageDismissed(campaignId: String, reason: String) {
        Log.d(TAG, "dismissed $campaignId reason=$reason")
    }

    override fun onInAppMessageAnswered(campaignId: String, value: Any, comment: String?) {
        Log.d(TAG, "answered $campaignId value=$value comment=$comment")
    }

    override fun onInAppMessageFailed(campaignId: String, message: String) {
        Log.d(TAG, "failed $campaignId: $message")
    }
}
