package io.grovs.utils

import android.opengl.EGL14
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.opengles.GL10

data class GlInfo(
    val vendor: String?,
    val renderer: String?,
    val version: String?
)

object GlUtils {

    @Volatile
    private var cachedGlInfo: GlInfo? = null

    suspend fun getGlInfoOffscreen(): GlInfo = cachedGlInfo ?: withContext(Dispatchers.Default) {
        synchronized(this@GlUtils) {
            cachedGlInfo ?: run {
                // 1. Get EGL display
                val egl = EGLContext.getEGL() as EGL10
                val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
                val versionArray = IntArray(2)
                egl.eglInitialize(display, versionArray)

                // 2. Choose EGL config
                val configAttr = intArrayOf(
                    EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                egl.eglChooseConfig(display, configAttr, configs, 1, numConfigs)
                val config = configs[0]

                // 3. Create a context
                val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
                val context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs)

                // 4. Create an offscreen surface (1x1 pbuffer)
                val surfaceAttribs = intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE)
                val surface = egl.eglCreatePbufferSurface(display, config, surfaceAttribs)

                // 5. Make current
                egl.eglMakeCurrent(display, surface, surface, context)

                // 6. Get GL strings
                val gl = context.gl as GL10
                val vendor = gl.glGetString(GL10.GL_VENDOR)
                val renderer = gl.glGetString(GL10.GL_RENDERER)
                val glVersion = gl.glGetString(GL10.GL_VERSION)

                // 7. Cleanup
                egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                egl.eglDestroySurface(display, surface)
                egl.eglDestroyContext(display, context)
                egl.eglTerminate(display)

                // 8. Cache and return result
                GlInfo(vendor, renderer, glVersion).also {
                    cachedGlInfo = it
                }
            }
        }
    }
}