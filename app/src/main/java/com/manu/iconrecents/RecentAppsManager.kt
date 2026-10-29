package com.manu.iconrecents

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
// RecentAppsManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Handles all root-shell interactions:
 *  - Fetching recent tasks (multi-source, Android 15 compatible)
 *  - Force-stopping individual or all apps
 *
 * ### Parsing strategy (in priority order)
 *
 * Source A — `dumpsys activity recents`
 *   Pattern 1: `realActivity=com.pkg/.Activity`          (Android 14-15, most reliable)
 *   Pattern 2: `cmp=com.pkg/.Activity`                   (inside baseIntent/intent={})
 *   Pattern 3: `* Hist #N: ActivityRecord{...} u0 com.pkg/.Activity`
 *
 * Source B — `dumpsys activity activities`  (fallback if Source A yields < 2 apps)
 *   Pattern:   `ActivityRecord{...} u0 com.pkg/.Activity`
 */
object RecentAppsManager {

    // ── Exclusions ────────────────────────────────────────────────────────────

    private val EXCLUDED_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.lineageos.trebuchet",
        "com.manu.iconrecents"           // ourselves
    )

    private val EXCLUDED_KEYWORDS = listOf(
        "launcher", "trebuchet", "homescreen", "home.screen"
    )

    // ── Regex patterns ────────────────────────────────────────────────────────

    /** realActivity=com.pkg/.Activity  — primary Android 15 format */
    private val REAL_ACTIVITY_RE =
        Regex("""realActivity=([a-zA-Z][a-zA-Z0-9_.]+)/""")

    /** cmp=com.pkg/.Activity  — inside baseIntent / intent={} lines */
    private val CMP_RE =
        Regex("""cmp=([a-zA-Z][a-zA-Z0-9_.]+)/""")

    /**
     * Hist #N: ActivityRecord{hash} u0 com.pkg/.Activity tN
     * Works in both `recents` and `activities` output.
     */
    private val HIST_RECORD_RE =
        Regex("""Hist\s+#\d+:\s+ActivityRecord\{[^}]+}\s+u\d+\s+([a-zA-Z][a-zA-Z0-9_.]+)/""")

    /** Bare ActivityRecord line (no "Hist" prefix) — in `activities` output */
    private val BARE_RECORD_RE =
        Regex("""ActivityRecord\{[^}]+}\s+u\d+\s+([a-zA-Z][a-zA-Z0-9_.]+)/""")

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the list of [RecentApp] objects using multi-source parsing.
     * Runs on [Dispatchers.IO].
     */
    suspend fun getRecentApps(context: Context): List<RecentApp> =
        withContext(Dispatchers.IO) {
            val pkgs = fetchPackages()
            val pm = context.packageManager
            pkgs.mapNotNull { pkg -> resolveApp(pm, pkg) }
        }

    /** Force-stop a single package via root shell. */
    suspend fun forceStopApp(pkg: String) = withContext(Dispatchers.IO) {
        shell("am force-stop $pkg")
    }

    /**
     * Force-stop every package in the list (Clear All).
     * Chains commands in one su session for efficiency.
     */
    suspend fun clearAllApps(packages: List<String>) = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext
        val cmd = packages.joinToString(" ; ") { "am force-stop $it" }
        shell(cmd)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fetching
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchPackages(): List<String> {
        // Source A: dumpsys activity recents  ──────────────────────────────────
        val recentsOutput = shellRead("dumpsys activity recents")
        val fromRecents = parseRecents(recentsOutput)

        // Source B: dumpsys activity activities (fallback / supplement) ─────────
        // Use when recents returns 0 or 1 result (parser may have missed entries)
        val combined: List<String> = if (fromRecents.size >= 2) {
            fromRecents
        } else {
            val activitiesOutput = shellRead("dumpsys activity activities")
            val fromActivities = parseActivities(activitiesOutput)
            // Merge: recents first (ordered by recency), then any extra from activities
            (fromRecents + fromActivities.filter { it !in fromRecents })
        }

        return combined
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parsers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses `dumpsys activity recents` output.
     *
     * Handles all known Android 11-15 line formats in a single pass.
     */
    internal fun parseRecents(output: String): List<String> {
        val result = mutableListOf<String>()

        for (raw in output.lines()) {
            val line = raw.trim()

            // Pattern 1 — realActivity= (Android 14-15, highest confidence)
            REAL_ACTIVITY_RE.find(line)?.groupValues?.get(1)
                ?.takeIf { isEligible(it) }
                ?.let { if (it !in result) result.add(it) }

            // Pattern 2 — cmp= inside intent block
            if (line.contains("cmp=")) {
                CMP_RE.find(line)?.groupValues?.get(1)
                    ?.takeIf { isEligible(it) }
                    ?.let { if (it !in result) result.add(it) }
            }

            // Pattern 3 — Hist ActivityRecord line
            if (line.contains("Hist") && line.contains("ActivityRecord")) {
                HIST_RECORD_RE.find(line)?.groupValues?.get(1)
                    ?.takeIf { isEligible(it) }
                    ?.let { if (it !in result) result.add(it) }
            }
        }

        return result
    }

    /**
     * Parses `dumpsys activity activities` output.
     *
     * Extracts running activities from the task stack.
     * This reflects what is CURRENTLY running (not just the recents history).
     */
    internal fun parseActivities(output: String): List<String> {
        val result = mutableListOf<String>()

        for (raw in output.lines()) {
            val line = raw.trim()
            if (!line.contains("ActivityRecord")) continue

            // Try Hist-prefixed format first, then bare format
            val pkg = HIST_RECORD_RE.find(line)?.groupValues?.get(1)
                ?: BARE_RECORD_RE.find(line)?.groupValues?.get(1)
                ?: continue

            if (isEligible(pkg) && pkg !in result) result.add(pkg)
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun isEligible(pkg: String): Boolean =
        pkg !in EXCLUDED_PACKAGES &&
        EXCLUDED_KEYWORDS.none { pkg.contains(it, ignoreCase = true) }

    private fun resolveApp(pm: PackageManager, pkg: String): RecentApp? = try {
        val info = pm.getApplicationInfo(pkg, 0)
        RecentApp(
            packageName = pkg,
            icon = pm.getApplicationIcon(info),
            label = pm.getApplicationLabel(info).toString()
        )
    } catch (_: PackageManager.NameNotFoundException) {
        null   // App uninstalled since last run
    }

    /**
     * Runs [command] via `su -c` and returns stdout.
     * Times out after [timeoutSec] seconds to prevent UI hangs.
     */
    private fun shellRead(command: String, timeoutSec: Long = 10L): String = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        // Read stderr concurrently so it doesn't block stdout
        val stderrThread = Thread { proc.errorStream.bufferedReader().readText() }
        stderrThread.isDaemon = true
        stderrThread.start()

        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        proc.destroy()
        output
    } catch (_: Exception) {
        ""
    }

    /**
     * Runs [command] via `su -c` (fire-and-forget, output discarded).
     */
    private fun shell(command: String) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            proc.waitFor(10L, TimeUnit.SECONDS)
            proc.destroy()
        } catch (_: Exception) { /* ignore */ }
    }
}
