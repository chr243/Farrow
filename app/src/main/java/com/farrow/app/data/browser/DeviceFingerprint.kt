package com.farrow.app.data.browser

import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.webkit.WebSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Real hardware/software characteristics of this phone, sent to the bridge so the browser profile matches the device. */
@Serializable
data class DeviceFingerprintInfo(
    val model: String,
    val manufacturer: String,
    val brand: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val cpuCores: Int,
    val cpuHardware: String?,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
    val density: Float,
    val refreshRate: Float,
    val glRenderer: String?,
    val glVendor: String?,
    val glVersion: String?,
    val locale: String,
    val timezone: String,
    val webViewUserAgent: String?,
)

@Singleton
class DeviceFingerprint @Inject constructor(@ApplicationContext private val context: Context) {

    @Volatile private var cached: DeviceFingerprintInfo? = null

    /** Collects the fingerprint once (GL probing creates a tiny offscreen EGL context). Call off the main thread. */
    fun get(): DeviceFingerprintInfo = cached ?: collect().also { cached = it }

    private fun collect(): DeviceFingerprintInfo {
        val (w, h, dpi, density, refresh) = screen()
        val gl = glInfo()
        return DeviceFingerprintInfo(
            model = Build.MODEL.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuHardware = cpuHardware(),
            screenWidthPx = w,
            screenHeightPx = h,
            densityDpi = dpi,
            density = density,
            refreshRate = refresh,
            glRenderer = gl[0],
            glVendor = gl[1],
            glVersion = gl[2],
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            webViewUserAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull(),
        )
    }

    private data class Screen(val w: Int, val h: Int, val dpi: Int, val density: Float, val refresh: Float)

    private fun screen(): Screen {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display: Display? = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = context.resources.displayMetrics
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        if (display != null) {
            // Physical resolution of the current mode (not reduced by system bars).
            val mode = display.mode
            w = mode.physicalWidth
            h = mode.physicalHeight
        }
        val refresh = display?.refreshRate ?: 60f
        val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT
        return Screen(minOf(w, h), maxOf(w, h), dpi, metrics.density, refresh)
    }

    private fun cpuHardware(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }
            if (soc.isNotEmpty()) return soc.joinToString(" ")
        }
        return runCatching {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Hardware", ignoreCase = true) || it.startsWith("model name", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
        }.getOrNull() ?: Build.HARDWARE
    }

    /** Returns [renderer, vendor, version] from an offscreen 1x1 pbuffer GLES2 context. */
    private fun glInfo(): Array<String?> {
        val out = arrayOfNulls<String>(3)
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return out
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return out
        try {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return out
            val config = configs[0] ?: return out
            val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) return out
            val surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                if (surface != null && surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, ctx)) {
                    out[0] = GLES20.glGetString(GLES20.GL_RENDERER)
                    out[1] = GLES20.glGetString(GLES20.GL_VENDOR)
                    out[2] = GLES20.glGetString(GLES20.GL_VERSION)
                }
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, ctx)
            }
        } catch (_: Throwable) {
            // GL probing is best-effort.
        } finally {
            EGL14.eglTerminate(display)
        }
        return out
    }
}
