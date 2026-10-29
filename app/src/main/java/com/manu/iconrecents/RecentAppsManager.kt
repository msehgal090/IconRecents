package com.manu.iconrecents

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Represents a single app from the recents stack.
 */
data class RecentApp(
    val packageName: String,
    val icon: Drawable,
    val label: String
)

/**
 * Handles all root-shell interactions:
 *  - Fetching recent tasks via `dumpsys activity recents`
 *  - Force-stopping individual or all apps
 */
object RecentAppsManager {

    /** Packages that should never appear in our recents list */
    private val EXCLUDED_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.manu.iconrecents"   // ourselves
    )

    /** Keywords that identify a launcher/home package */
    private val LAUNCHER_KEYWORDS = listOf("launcher", "homescreen", "trebuchet")

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a list of [RecentApp] objects by:
     * 1. Running `su -c dumpsys activity recents` in the background.
     * 2. Parsing package names from the output.
     * 3. Loading icons from [PackageManager].
     */
    suspend fun getRecentApps(context: Context): List<RecentApp> = withContext(Dispatchers.IO) {
        val packageNames = fetchRecentPackages()
        val pm = context.packageManager

        packageNames.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val icon = pm.getApplicationIcon(info)
                val label = pm.getApplicationLabel(info).toString()
                RecentApp(pkg, icon, label)
            } catch (_: PackageManager.NameNotFoundException) {
                null // App may have been uninstalled; skip it
            }
        }
    }

    /**
     * Force-stops a single package via root shell.
     */
    suspend fun forceStopApp(packageName: String) = withContext(Dispatchers.IO) {
        runShell("am force-stop $packageName")
    }

    /**
     * Force-stops every package in the list (for "Clear All").
     */
    suspend fun clearAllApps(packages: List<String>) = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext
        // Chain all commands in one su session for efficiency
        val cmd = packages.joinToString(" && ") { "am force-stop $it" }
        runShell(cmd)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchRecentPackages(): List<String> = try {
        val output = runShellWithOutput("dumpsys activity recents")
        parseRecentPackages(output)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Parses `dumpsys activity recents` output.
     *
     * Each recent task block contains a line like:
     *   baseIntent=Intent { ... cmp=com.example.app/.MainActivity }
     *
     * We extract the package from `cmp=<package>/<activity>`.
     */
    internal fun parseRecentPackages(dumpsysOutput: String): List<String> {
        val packages = mutableListOf<String>()
        // Matches: cmp=com.example.app/
        val cmpRegex = Regex("""cmp=([a-zA-Z][a-zA-Z0-9_.]+)/""")

        for (line in dumpsysOutput.lines()) {
            if (!line.contains("baseIntent=") || !line.contains("cmp=")) continue

            val match = cmpRegex.find(line) ?: continue
            val pkg = match.groupValues[1]

            if (pkg !in EXCLUDED_PACKAGES &&
                pkg !in packages &&
                !isLauncherPackage(pkg)
            ) {
                packages.add(pkg)
            }
        }
        return packages
    }

    private fun isLauncherPackage(pkg: String): Boolean =
        LAUNCHER_KEYWORDS.any { pkg.contains(it, ignoreCase = true) }

    /**
     * Runs a command via `su -c` and returns stdout.
     */
    private fun runShellWithOutput(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    /**
     * Runs a command via `su -c` and discards stdout (fire-and-forget).
     */
    private fun runShell(command: String) {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        process.waitFor()
    }
}
