package com.manu.iconrecents

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// Data model
// ─────────────────────────────────────────────────────────────────────────────

data class RecentApp(
    val packageName: String,
    val icon: Drawable,
    val label: String
)

// ─────────────────────────────────────────────────────────────────────────────
// RecentAppsManager  — fully crash-safe
// ─────────────────────────────────────────────────────────────────────────────

object RecentAppsManager {

    private const val TAG = "IconRecents/Manager"

    // ── Exclusions ────────────────────────────────────────────────────────────

    private val EXCLUDED_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.lineageos.trebuchet",
        "com.manu.iconrecents"
    )
    private val EXCLUDED_KEYWORDS = listOf("launcher", "trebuchet", "homescreen")

    // ── Regex patterns ────────────────────────────────────────────────────────

    private val REAL_ACTIVITY_RE  = Regex("""realActivity=([a-zA-Z][a-zA-Z0-9_.]+)/""")
    private val CMP_RE            = Regex("""cmp=([a-zA-Z][a-zA-Z0-9_.]+)/""")
    private val HIST_RECORD_RE    =
        Regex("""Hist\s+#\d+:\s+ActivityRecord\{[^}]+}\s+u\d+\s+([a-zA-Z][a-zA-Z0-9_.]+)/""")
    private val BARE_RECORD_RE    =
        Regex("""ActivityRecord\{[^}]+}\s+u\d+\s+([a-zA-Z][a-zA-Z0-9_.]+)/""")

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a list of [RecentApp] objects.
     * NEVER throws — returns emptyList() on any failure.
     */
    suspend fun getRecentApps(context: Context): List<RecentApp> =
        withContext(Dispatchers.IO) {
            try {
                val pkgs = fetchPackages()
                if (pkgs.isEmpty()) {
                    Log.w(TAG, "No recent packages found — root may be unavailable or output was empty")
                    return@withContext emptyList()
                }
                val pm = context.packageManager
                pkgs.mapNotNull { pkg -> resolveApp(pm, pkg) }
            } catch (e: Exception) {
                Log.e(TAG, "getRecentApps crashed", e)
                emptyList()
            }
        }

    /** Force-stop one package. Never throws. */
    suspend fun forceStopApp(pkg: String) = withContext(Dispatchers.IO) {
        try {
            shell("am force-stop $pkg")
        } catch (e: Exception) {
            Log.e(TAG, "forceStopApp($pkg) failed", e)
        }
    }

    /** Force-stop all packages. Never throws. */
    suspend fun clearAllApps(packages: List<String>) = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext
        try {
            val cmd = packages.joinToString(" ; ") { "am force-stop $it" }
            shell(cmd)
        } catch (e: Exception) {
            Log.e(TAG, "clearAllApps failed", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Root availability check
    // ─────────────────────────────────────────────────────────────────────────

    /** Quick sanity-check: does `su` respond? */
    fun isRootAvailable(): Boolean = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor(3L, TimeUnit.SECONDS)
        proc.destroy()
        output.contains("uid=0").also { ok ->
            Log.d(TAG, "Root check → available=$ok (output='${output.trim()}')")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Root check failed: ${e.message}")
        false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fetching
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchPackages(): List<String> {
        // Source A: dumpsys activity recents
        val fromRecents = try {
            val out = shellRead("dumpsys activity recents")
            parseRecents(out)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPackages/recents failed", e)
            emptyList()
        }

        if (fromRecents.size >= 2) return fromRecents

        // Source B: dumpsys activity activities (fallback)
        val fromActivities = try {
            val out = shellRead("dumpsys activity activities")
            parseActivities(out)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPackages/activities failed", e)
            emptyList()
        }

        return (fromRecents + fromActivities.filter { it !in fromRecents })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parsers — each wrapped independently
    // ─────────────────────────────────────────────────────────────────────────

    internal fun parseRecents(output: String): List<String> {
        if (output.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        return try {
            for (raw in output.lines()) {
                val line = raw.trim()
                runCatching {
                    REAL_ACTIVITY_RE.find(line)?.groupValues?.get(1)
                        ?.takeIf { isEligible(it) }
                        ?.let { if (it !in result) result.add(it) }
                }
                runCatching {
                    if (line.contains("cmp=")) {
                        CMP_RE.find(line)?.groupValues?.get(1)
                            ?.takeIf { isEligible(it) }
                            ?.let { if (it !in result) result.add(it) }
                    }
                }
                runCatching {
                    if (line.contains("Hist") && line.contains("ActivityRecord")) {
                        HIST_RECORD_RE.find(line)?.groupValues?.get(1)
                            ?.takeIf { isEligible(it) }
                            ?.let { if (it !in result) result.add(it) }
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "parseRecents crashed", e)
            result   // return whatever we collected before the crash
        }
    }

    internal fun parseActivities(output: String): List<String> {
        if (output.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        return try {
            for (raw in output.lines()) {
                val line = raw.trim()
                if (!line.contains("ActivityRecord")) continue
                runCatching {
                    val pkg = HIST_RECORD_RE.find(line)?.groupValues?.get(1)
                        ?: BARE_RECORD_RE.find(line)?.groupValues?.get(1)
                        ?: return@runCatching
                    if (isEligible(pkg) && pkg !in result) result.add(pkg)
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "parseActivities crashed", e)
            result
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun isEligible(pkg: String): Boolean = try {
        pkg !in EXCLUDED_PACKAGES &&
        EXCLUDED_KEYWORDS.none { pkg.contains(it, ignoreCase = true) }
    } catch (_: Exception) {
        false
    }

    private fun resolveApp(pm: PackageManager, pkg: String): RecentApp? = try {
        val info = pm.getApplicationInfo(pkg, 0)
        RecentApp(
            packageName = pkg,
            icon        = pm.getApplicationIcon(info),
            label       = pm.getApplicationLabel(info).toString()
        )
    } catch (e: PackageManager.NameNotFoundException) {
        Log.d(TAG, "resolveApp: $pkg not installed, skipping")
        null
    } catch (e: Exception) {
        Log.e(TAG, "resolveApp($pkg) failed", e)
        null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shell execution  — both methods NEVER throw to callers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs [command] via `su -c` and returns stdout.
     * Returns empty string on any failure.
     */
    private fun shellRead(command: String, timeoutSec: Long = 10L): String {
        Log.d(TAG, "shellRead: $command")
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

            // Drain stderr in background so it never blocks stdout reading
            val stderrThread = Thread {
                try { proc.errorStream.bufferedReader().readText() } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.start() }

            val output = try {
                proc.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                Log.e(TAG, "shellRead: stdout read failed", e)
                ""
            }

            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) Log.w(TAG, "shellRead: timeout after ${timeoutSec}s for '$command'")

            try { proc.destroy() } catch (_: Exception) {}

            output
        } catch (e: Exception) {
            Log.e(TAG, "shellRead('$command') exec failed: ${e.message}")
            ""
        }
    }

    /**
     * Runs [command] via `su -c` — output discarded.
     * Never throws.
     */
    private fun shell(command: String) {
        Log.d(TAG, "shell: $command")
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            Thread { try { proc.errorStream.bufferedReader().readText() } catch (_: Exception) {} }
                .also { it.isDaemon = true; it.start() }
            proc.waitFor(10L, TimeUnit.SECONDS)
            try { proc.destroy() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "shell('$command') failed: ${e.message}")
        }
    }
}
