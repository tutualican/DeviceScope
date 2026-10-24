package com.alican.devicescope.bench

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU suite: tries Vulkan first (native SPIR-V, embedded at build time), and
 * falls back to OpenGL ES 3.1 compute when the driver refuses Vulkan. The
 * backend that produced the numbers is reported so a score is never mislabeled.
 */
object GpuSuite {

    // Reference constants for the index (documented in the UI):
    //   60 Gop/s integer compute, 60 fps at 1024x1024 with 64 ALU iterations.
    private const val REF_GOPS = 60.0
    private const val REF_FPS = 60.0

    suspend fun run(): GpuBench.GpuResult {
        val vk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            VulkanBench.run()
        }
        if (vk.usable) {
            val parts = ArrayList<Double>()
            vk.computeGops?.let { parts.add(it / REF_GOPS * 1000.0) }
            vk.sceneFps?.let { parts.add(it / REF_FPS * 1000.0) }
            val index = if (parts.isEmpty()) null else Stats.geoMean(parts)
            return GpuBench.GpuResult(
                renderer = vk.device,
                glVersion = "Vulkan ${vk.apiVersion}",
                computeGops = vk.computeGops,
                sceneFps = vk.sceneFps,
                index = index,
                error = vk.error,
                api = "Vulkan",
                fallbackNote = null
            )
        }

        val gles = GpuBench().run()
        return gles.copy(
            fallbackNote = "Vulkan kullanılamadı (${vk.error ?: "destek yok"}); ölçüm OpenGL ES 3.1 ile yapıldı.",
            api = "OpenGL ES"
        )
    }
}

/**
 * OpenGL ES 3.1 fallback path: compute shader over a 16 MiB SSBO plus an
 * offscreen fragment scene. Only used when the Vulkan backend is unavailable,
 * so a device still gets a real GPU measurement instead of a blank result.
 */
class GpuBench {

    data class GpuResult(
        val renderer: String,
        val glVersion: String,
        val computeGops: Double?,
        val sceneFps: Double?,
        val index: Double?,
        val error: String? = null,
        /** "Vulkan" or "OpenGL ES" - which backend produced these numbers. */
        val api: String = "OpenGL ES",
        /** Set when Vulkan was tried first and had to fall back. */
        val fallbackNote: String? = null
    ) {
        val usable: Boolean get() = error == null && index != null
    }

    // Reference constants for the index (documented in the UI):
    //   60 Gop/s integer compute, 60 fps at 1024x1024 with 64 ALU iterations.
    private val refGops = 60.0
    private val refFps = 60.0

    suspend fun run(): GpuResult = withContext(Dispatchers.Default) {
        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                return@withContext GpuResult("—", "—", null, null, null, "EGL başlatılamadı")
            }
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
                return@withContext GpuResult("—", "—", null, null, null, "EGL başlatılamadı")
            }

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x0040,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                return@withContext GpuResult("—", "—", null, null, null, "ES 3.x EGL yapılandırması yok")
            }

            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                return@withContext GpuResult("—", "—", null, null, null, "ES 3 bağlamı oluşturulamadı")
            }

            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                return@withContext GpuResult("—", "—", null, null, null, "Pbuffer oluşturulamadı")
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return@withContext GpuResult("—", "—", null, null, null, "Bağlam aktifleştirilemedi")
            }

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "—"
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "—"
            val es31 = glVersion.contains("3.1") || glVersion.contains("3.2")

            val computeGops = if (es31) measureCompute() else null
            val sceneFps = measureScene()

            val parts = ArrayList<Double>()
            if (computeGops != null && computeGops > 0) parts.add(computeGops / refGops * 1000.0)
            if (sceneFps != null && sceneFps > 0) parts.add(sceneFps / refFps * 1000.0)
            val index = if (parts.isEmpty()) null else Stats.geoMean(parts)

            val error = when {
                index == null -> "GPU ölçümü tamamlanamadı (sürücü compute/fragment hattını reddetti)"
                computeGops == null -> "ES 3.1 compute desteklenmiyor; yalnızca sahne testi ölçüldü"
                else -> null
            }

            GpuResult(renderer, glVersion, computeGops, sceneFps, index, error)
        } catch (t: Throwable) {
            GpuResult("—", "—", null, null, null, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            try {
                if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    context?.let { EGL14.eglDestroyContext(display, it) }
                    surface?.let { EGL14.eglDestroySurface(display, it) }
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------ compute

    private fun measureCompute(): Double? {
        val shader = """
            #version 310 es
            layout(local_size_x = 256) in;
            layout(std430, binding = 0) buffer Data { uint data[]; };
            uniform uint uRounds;
            void main() {
                uint idx = gl_GlobalInvocationID.x;
                uint v = data[idx] + idx;
                for (uint i = 0u; i < uRounds; i++) {
                    v ^= v << 13;
                    v ^= v >> 17;
                    v ^= v << 5;
                    v = v * 2654435761u + idx;
                    v ^= (v >> 11);
                }
                data[idx] = v;
            }
        """.trimIndent()

        val program = buildComputeProgram(shader) ?: return null

        val elements = 4 * 1024 * 1024          // 16 MiB of u32
        val groups = elements / 256
        val rounds = 96                        // per-invocation loop count

        val bufferHandle = IntArray(1)
        GLES20.glGenBuffers(1, bufferHandle, 0)
        if (bufferHandle[0] == 0) return null

        val data = ByteBuffer.allocateDirect(elements * 4)
            .order(ByteOrder.nativeOrder())
        for (i in 0 until elements) data.putInt(i * 2654435761.toInt())
        data.position(0)

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufferHandle[0])
        GLES20.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER,
            elements * 4,
            data,
            GLES20.GL_DYNAMIC_DRAW
        )
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bufferHandle[0])

        GLES20.glUseProgram(program)
        val loc = GLES20.glGetUniformLocation(program, "uRounds")
        GLES30.glUniform1ui(loc, rounds)

        // Warm-up dispatch, discarded.
        GLES31.glDispatchCompute(groups, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glFinish()

        val samples = ArrayList<Double>()
        repeat(3) {
            val t0 = System.nanoTime()
            GLES31.glDispatchCompute(groups, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            GLES20.glFinish()
            val dt = (System.nanoTime() - t0) / 1e9
            if (dt > 0) {
                // ~6 integer ops per loop iteration
                val ops = elements.toDouble() * rounds.toDouble() * 6.0
                samples.add(ops / dt / 1e9)
            }
        }

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        GLES20.glDeleteBuffers(1, bufferHandle, 0)
        GLES20.glDeleteProgram(program)

        return if (samples.isEmpty()) null else Stats.median(samples)
    }

    private fun buildComputeProgram(source: String): Int? {
        val shader = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        if (shader == 0) return null
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteShader(shader)
            return null
        }
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            GLES20.glDeleteShader(shader)
            return null
        }
        GLES20.glAttachShader(program, shader)
        GLES20.glLinkProgram(program)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(shader)
        if (status[0] == 0) {
            GLES20.glDeleteProgram(program)
            return null
        }
        return program
    }

    // -------------------------------------------------------------------- scene

    private fun measureScene(): Double? {
        val size = 1024
        val vs = """
            #version 300 es
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()

        val fs = """
            #version 300 es
            precision highp float;
            uniform float uT;
            out vec4 outColor;
            void main() {
                vec2 p = gl_FragCoord.xy / 512.0;
                float acc = 0.0;
                for (int i = 0; i < 64; i++) {
                    float fi = float(i) + 1.0;
                    acc += sin(p.x * fi + uT) * cos(p.y * fi - uT) * 0.5;
                }
                outColor = vec4(vec3(acc * 0.5 + 0.5), 1.0);
            }
        """.trimIndent()

        val vsh = compile(GLES20.GL_VERTEX_SHADER, vs) ?: return null
        val fsh = compile(GLES20.GL_FRAGMENT_SHADER, fs) ?: return null
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vsh)
        GLES20.glAttachShader(program, fsh)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vsh)
        GLES20.glDeleteShader(fsh)
        if (status[0] == 0) {
            GLES20.glDeleteProgram(program)
            return null
        }

        val fbo = IntArray(1)
        val tex = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            size, size, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex[0], 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            GLES20.glDeleteTextures(1, tex, 0)
            GLES20.glDeleteProgram(program)
            return null
        }
        GLES20.glViewport(0, 0, size, size)
        GLES20.glUseProgram(program)
        val loc = GLES20.glGetUniformLocation(program, "uT")
        val dummy = FloatBuffer.allocate(1)

        // warm-up
        repeat(3) {
            GLES20.glUniform1f(loc, it.toFloat())
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            GLES20.glFinish()
        }

        val samples = ArrayList<Double>()
        val frames = 24
        repeat(3) {
            val t0 = System.nanoTime()
            for (f in 0 until frames) {
                GLES20.glUniform1f(loc, f.toFloat())
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            }
            GLES20.glFinish()
            val dt = (System.nanoTime() - t0) / 1e9
            if (dt > 0) samples.add(frames / dt)
        }
        dummy.clear()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fbo, 0)
        GLES20.glDeleteTextures(1, tex, 0)
        GLES20.glDeleteProgram(program)

        return if (samples.isEmpty()) null else Stats.median(samples)
    }

    private fun compile(type: Int, source: String): Int? {
        val s = GLES20.glCreateShader(type)
        if (s == 0) return null
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteShader(s)
            return null
        }
        return s
    }
}