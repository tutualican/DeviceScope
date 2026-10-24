package com.alican.devicescope

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * Queries the real GPU driver strings by creating a throw-away 1x1 offscreen
 * EGL/GLES2 surface. This is the only rootsuz way to learn the actual GPU model
 * (Adreno 730, Mali-G710, Xclipse 920 ...).
 */
object GpuProbe {

    data class GpuInfo(
        val renderer: String?,
        val vendor: String?,
        val glVersion: String?,
        val glslVersion: String?
    )

    fun query(): GpuInfo {
        var display: EGLDisplay? = null
        var surface: EGLSurface? = null
        var context: EGLContext? = null
        var renderer: String? = null
        var vendor: String? = null
        var glVersion: String? = null
        var glsl: String? = null

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return GpuInfo(null, null, null, null)

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return GpuInfo(null, null, null, null)
            }

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                return GpuInfo(null, null, null, null)
            }

            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) return GpuInfo(null, null, null, null)

            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (context == null || context == EGL14.EGL_NO_CONTEXT) return GpuInfo(null, null, null, null)

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return GpuInfo(null, null, null, null)
            }

            renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            vendor = GLES20.glGetString(GLES20.GL_VENDOR)
            glVersion = GLES20.glGetString(GLES20.GL_VERSION)
            glsl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
        } catch (_: Throwable) {
        } finally {
            try {
                if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (context != null) EGL14.eglDestroyContext(display, context)
                    if (surface != null) EGL14.eglDestroySurface(display, surface)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {
            }
        }
        return GpuInfo(renderer, vendor, glVersion, glsl)
    }

    /** Best-effort live/max GPU clock from vendor sysfs nodes. */
    fun gpuClock(): Pair<Long?, Long?> {
        val candidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/kernel/gpu/gpu_freq",
            "/sys/class/devfreq/gpufreq/cur_freq"
        )
        var cur: Long? = null
        for (p in candidates) {
            val raw = Sys.read(p)?.toLongOrNull() ?: continue
            cur = if (raw > 10_000_000L) raw / 1000L else raw / 1000L
            break
        }

        val maxCandidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies"
        )
        var max: Long? = null
        for (p in maxCandidates) {
            val txt = Sys.read(p) ?: continue
            val values = Regex("\\d+").findAll(txt).mapNotNull { it.value.toLongOrNull() }.toList()
            if (values.isNotEmpty()) {
                val v = values.maxOrNull()!!
                max = if (v > 10_000_000L) v / 1000L else v / 1000L
                break
            }
        }
        return cur to max
    }
}
