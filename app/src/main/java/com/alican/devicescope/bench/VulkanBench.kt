package com.alican.devicescope.bench

/**
 * Bridge to the Vulkan measurement path (app/src/main/cpp/gpu_vk.cpp).
 *
 * The native side creates a real VkInstance/device, runs a compute dispatch over
 * a 16 MiB SSBO and renders a 1024x1024 offscreen scene with an ALU-heavy
 * fragment shader. Shaders are compiled to SPIR-V at build time by the NDK's
 * glslc and embedded in the library, so what runs on the GPU is exactly what was
 * validated with spirv-val.
 *
 * If the driver refuses anything, [run] returns supported=false with the reason
 * and the caller falls back to the OpenGL ES path.
 */
object VulkanBench {

    data class VkResult(
        val supported: Boolean,
        val device: String,
        val apiVersion: String,
        val computeGops: Double?,
        val sceneFps: Double?,
        val error: String?
    ) {
        val usable: Boolean get() = supported && (computeGops != null || sceneFps != null)
    }

    /**
     * Per-frame GPU timings plus a pixel-correctness verdict, used by the
     * validation screen. [frameNs] comes from GPU timestamp queries around each
     * of the 120 rendered frames, not from CPU wall time.
     */
    data class VkDetail(
        val ok: Boolean,
        val device: String,
        val apiVersion: String,
        val frameNs: List<Long>,
        val pixelOk: Boolean,
        val maxPixelError: Double,
        val note: String?
    )

    fun detail(): VkDetail {
        if (!Native.available()) {
            return VkDetail(false, "—", "—", emptyList(), false, 0.0, "Ölçüm kütüphanesi yüklenemedi")
        }
        val raw = try {
            nDetail()
        } catch (t: Throwable) {
            return VkDetail(false, "—", "—", emptyList(), false, 0.0, "${t.javaClass.simpleName}: ${t.message}")
        } ?: return VkDetail(false, "—", "—", emptyList(), false, 0.0, "Vulkan çağrısı boş döndü")

        if (raw.size < 7) {
            return VkDetail(false, "—", "—", emptyList(), false, 0.0, "Beklenmeyen Vulkan yanıtı")
        }
        val frames = raw[3].split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
        return VkDetail(
            ok = raw[0] == "1" && frames.isNotEmpty(),
            device = raw[1],
            apiVersion = raw[2],
            frameNs = frames,
            pixelOk = raw[4] == "1",
            maxPixelError = raw[5].toDoubleOrNull() ?: 0.0,
            note = raw[6].takeIf { it.isNotBlank() }
        )
    }

    fun run(): VkResult {
        if (!Native.available()) {
            return VkResult(false, "—", "—", null, null, "Ölçüm kütüphanesi yüklenemedi")
        }
        val raw = try {
            nRun()
        } catch (t: Throwable) {
            return VkResult(false, "—", "—", null, null, "${t.javaClass.simpleName}: ${t.message}")
        } ?: return VkResult(false, "—", "—", null, null, "Vulkan çağrısı boş döndü")

        if (raw.size < 6) {
            return VkResult(false, "—", "—", null, null, "Beklenmeyen Vulkan yanıtı")
        }
        val supported = raw[0] == "1"
        val compute = raw[3].toDoubleOrNull()
        val scene = raw[4].toDoubleOrNull()
        return VkResult(
            supported = supported,
            device = raw[1],
            apiVersion = raw[2],
            computeGops = compute?.takeIf { it > 0.0 },
            sceneFps = scene?.takeIf { it > 0.0 },
            error = raw[5].takeIf { it.isNotBlank() }
        )
    }

    private external fun nRun(): Array<String>?
    private external fun nDetail(): Array<String>?
}
