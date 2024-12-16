package io.grovs.utils

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import io.grovs.R

fun Fragment.showProgressBar(tag:String? = null, infoText:String? = null) {
    hideProgressBar(tag)

    val layoutInflater = LayoutInflater.from(context!!)
    val view = layoutInflater.inflate(R.layout.loading_view, view as ViewGroup?)
    val infoTextView = view.findViewById<TextView>(R.id.loadingInfoTextView)
    infoText?.let {
        infoTextView.visibility = View.VISIBLE
        infoTextView.text = it
    } ?: run {
        infoTextView.visibility = View.INVISIBLE
    }
    val childView = view?.findViewById<ConstraintLayout>(R.id.progressBarContainer)
    childView?.tag = tag
}

fun Fragment.hideProgressBar(tag:String? = null) {
    val view = view?.findViewById<ConstraintLayout>(R.id.progressBarContainer)
    tag?.let {
        if (view?.tag == tag) {
            (view?.parent as ViewGroup?)?.removeView(view)
        }
    } ?: run {
        (view?.parent as ViewGroup?)?.removeView(view)
    }
}