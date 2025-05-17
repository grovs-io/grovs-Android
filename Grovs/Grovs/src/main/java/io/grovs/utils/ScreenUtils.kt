package io.grovs.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Size
import android.util.SizeF
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.ceil

object ScreenUtils {

    @Volatile
    private var cachedResolution: Pair<String,String>? = null

    fun getScreenResolution(context: Context): Pair<String,String>? {
        cachedResolution?.let { return it }

        synchronized(this) {
            cachedResolution?.let { return it } // double-checked locking

            val resolution = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = context.getSystemService(WindowManager::class.java)
                val metrics = wm.currentWindowMetrics.bounds
                val density = context.resources.displayMetrics.density
                Pair("${ceil(metrics.width() / density).toInt()}", "${ceil(metrics.height() / density).toInt()}")
            } else {
                val displayMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                (context as? Activity)?.windowManager?.defaultDisplay?.getRealMetrics(displayMetrics)?.let {
                    Pair("${ceil(displayMetrics.widthPixels / displayMetrics.density).toInt()}", "${ceil(displayMetrics.heightPixels / displayMetrics.density).toInt()}")
                }
            }

            cachedResolution = resolution
            return resolution
        }
    }
}