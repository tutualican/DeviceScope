package com.alican.devicescope.bench

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.security.MessageDigest

/**
 * Environment fingerprint recorded with every validation run.
 *
 * A benchmark result is only meaningful next to the conditions it was produced
 * under: a device in its "performance" governor scores differently from one that
 * is thermally throttled. Nothing here changes a score; it is evidence.
 */
object AntiCheat {

    data class Fingerprint(
        val packageName: String,
        val signaturePrefix: String,
        val debuggable: Boolean,
        val emulator: Boolean,
        val emulatorReasons: List<String>,
        val rooted: Boolean,
        val rootReasons: List<String>,
        val governors: String,
        val powerSaveMode: Boolean,
        val thermalStatus: String,
        val sustainedModeSupported: Boolean,
        val buildTags: String,
        val securityPatch: String
    )

    fun collect(context: Context): Fingerprint {
        val app: ApplicationInfo = context.applicationInfo
        val debuggable = (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val emulatorReasons = ArrayList<String>()
        if (Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")) {
            emulatorReasons.add("fingerprint=${Build.FINGERPRINT}")
        }
        if (Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu")) {
            emulatorReasons.add("hardware=${Build.HARDWARE}")
        }
        if (Build.MODEL.contains("sdk", ignoreCase = true) && Build.MANUFACTURER.contains("unknown", ignoreCase = true)) {
            emulatorReasons.add("model=${Build.MODEL}")
        }
        if (Build.PRODUCT.contains("sdk") || Build.PRODUCT.contains("emulator")) {
            emulatorReasons.add("product=${Build.PRODUCT}")
        }

        val rootReasons = ArrayList<String>()
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/data/adb/magisk", "/data/adb/modules"
        )
        for (p in suPaths) {
            if (File(p).exists()) rootReasons.add(p)
        }
        val tags = Build.TAGS ?: ""
        if (tags.contains("test-keys")) rootReasons.add("build tags contain test-keys")

        val cores = Validate.cpuInfo()
        val governors = if (cores.isEmpty()) {
            "okunamadı"
        } else {
            cores.groupBy { it.governor }.entries.joinToString(", ") { "${it.key}×${it.value.size}" }
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "bilinmiyor"
            }
        } else {
            "API < 29"
        }

        return Fingerprint(
            packageName = context.packageName,
            signaturePrefix = signaturePrefix(context),
            debuggable = debuggable,
            emulator = emulatorReasons.isNotEmpty(),
            emulatorReasons = emulatorReasons,
            rooted = rootReasons.isNotEmpty(),
            rootReasons = rootReasons,
            governors = governors,
            powerSaveMode = pm?.isPowerSaveMode ?: false,
            thermalStatus = thermal,
            sustainedModeSupported = pm?.isSustainedPerformanceModeSupported ?: false,
            buildTags = tags,
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Build.VERSION.SECURITY_PATCH ?: "?"
            } else "?"
        )
    }

    /**
     * First bytes of the signing certificate hash. Two builds with the same
     * package but a different signer produce different values, which is what the
     * repackaging test needs (a repackaged APK cannot keep the signature).
     */
    fun signaturePrefix(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        )
        val signers = info.signingInfo?.apkContentsSigners ?: return "—"
        if (signers.isEmpty()) return "—"
        val digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())
        digest.take(6).joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        "—"
    }
}
