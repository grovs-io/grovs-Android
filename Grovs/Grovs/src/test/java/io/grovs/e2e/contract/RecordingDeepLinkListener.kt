package io.grovs.e2e.contract

import android.os.Looper
import io.grovs.GrovsDeeplinkListener
import io.grovs.model.DeeplinkDetails
import java.util.concurrent.CopyOnWriteArrayList

internal class RecordingDeepLinkListener : GrovsDeeplinkListener {
    val delivered = CopyOnWriteArrayList<DeeplinkDetails>()
    val onMain = CopyOnWriteArrayList<Boolean>()

    override fun onDeeplinkReceived(deeplinkDetails: DeeplinkDetails) {
        onMain += Looper.myLooper() == Looper.getMainLooper()
        delivered += deeplinkDetails
    }
}
