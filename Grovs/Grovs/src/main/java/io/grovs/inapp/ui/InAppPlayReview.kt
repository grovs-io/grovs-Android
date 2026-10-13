package io.grovs.inapp.ui

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory

/** The only class that names Play review types; loaded only after the app was seen to ship them. */
internal object InAppPlayReview {
    var managerFactory: (Context) -> ReviewManager = { ReviewManagerFactory.create(it) }

    /** `done` runs once the flow ends, shown or not: Play never says whether it showed anything. */
    fun launch(activity: Activity, done: () -> Unit) {
        val manager = managerFactory(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) return@addOnCompleteListener done()
            manager.launchReviewFlow(activity, request.result).addOnCompleteListener { done() }
        }
    }
}
