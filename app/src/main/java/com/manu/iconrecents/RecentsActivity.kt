package com.manu.iconrecents

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Transparent, bottom-anchored popup showing recent app icons.
 *
 * Every operation that can fail is wrapped in try-catch so the app
 * NEVER shows "keeps stopping". Failures log to logcat and gracefully
 * close the popup with a user-visible toast where appropriate.
 */
class RecentsActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "IconRecents/Activity"
    }

    // All views are nullable — if inflation partially fails we won't NPE
    private var rvIcons: RecyclerView? = null
    private var btnClearAll: Button? = null
    private var adapter: AppIconAdapter? = null
    private var currentApps: List<RecentApp> = emptyList()

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1 — Window styling (non-fatal: bad window params shouldn't kill the app)
        try {
            configureWindow()
        } catch (e: Exception) {
            Log.e(TAG, "configureWindow failed — continuing with default window", e)
        }

        // 2 — Layout inflation (fatal: no UI = nothing to show)
        try {
            setContentView(R.layout.activity_recents)
        } catch (e: Exception) {
            Log.e(TAG, "setContentView failed", e)
            showToast("IconRecents: layout error")
            finish()
            return
        }

        // 3 — View binding (fatal: views are needed for all further interactions)
        try {
            bindViews()
        } catch (e: Exception) {
            Log.e(TAG, "bindViews failed", e)
            showToast("IconRecents: view binding error")
            finish()
            return
        }

        // 4 — Data load (non-fatal: shows toast + closes if nothing found)
        loadRecentApps()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Window configuration
    // ─────────────────────────────────────────────────────────────────────────

    private fun configureWindow() {
        supportActionBar?.hide()

        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val lp = window.attributes
        lp.gravity   = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.width     = WindowManager.LayoutParams.MATCH_PARENT
        lp.height    = WindowManager.LayoutParams.WRAP_CONTENT
        lp.dimAmount = 0.55f
        window.attributes = lp
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        rvIcons    = findViewById(R.id.rvIcons)
        btnClearAll = findViewById(R.id.btnClearAll)

        if (rvIcons == null || btnClearAll == null) {
            throw IllegalStateException("Critical views not found in layout")
        }

        val iconAdapter = AppIconAdapter(
            onIconClick      = { app -> launchApp(app.packageName) },
            onIconLongClick  = { app -> forceStopApp(app) }
        )
        adapter = iconAdapter

        rvIcons!!.apply {
            layoutManager = GridLayoutManager(this@RecentsActivity, 4)
            adapter       = iconAdapter
        }

        btnClearAll!!.setOnClickListener { clearAll() }

        // Tap outside panel → dismiss
        findViewById<View>(R.id.rootLayout)?.setOnClickListener { finish() }
        // Tap inside panel → consume (don't dismiss)
        findViewById<View>(R.id.panel)?.setOnClickListener { /* consume */ }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data loading
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadRecentApps() {
        lifecycleScope.launch {
            try {
                // Quick root availability check before the expensive dumpsys call
                val hasRoot = RecentAppsManager.isRootAvailable()
                if (!hasRoot) {
                    Log.w(TAG, "Root not available — cannot fetch recents")
                    showToast("IconRecents: root access required")
                    finish()
                    return@launch
                }

                val apps = RecentAppsManager.getRecentApps(this@RecentsActivity)
                currentApps = apps
                adapter?.submitList(apps)

                when {
                    apps.isEmpty() -> {
                        Log.i(TAG, "No recent apps returned")
                        showToast("No recent apps")
                        finish()
                    }
                    else -> Log.i(TAG, "Loaded ${apps.size} recent app(s)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "loadRecentApps crashed", e)
                showToast("IconRecents: failed to load apps")
                finish()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────────────────────────────

    /** Brings the chosen app to the foreground. */
    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                startActivity(intent)
                Log.d(TAG, "Launched $packageName")
            } else {
                Log.w(TAG, "No launch intent for $packageName")
                showToast(getString(R.string.cannot_launch))
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchApp($packageName) failed", e)
            showToast(getString(R.string.cannot_launch))
        } finally {
            finish()
        }
    }

    /** Force-stops a single app and removes it from the list. */
    private fun forceStopApp(app: RecentApp) {
        lifecycleScope.launch {
            try {
                RecentAppsManager.forceStopApp(app.packageName)
                Log.d(TAG, "Force-stopped ${app.packageName}")
            } catch (e: Exception) {
                Log.e(TAG, "forceStopApp(${app.packageName}) error", e)
            }
            // Always remove from list even if force-stop failed
            val updated = currentApps.filter { it.packageName != app.packageName }
            currentApps = updated
            adapter?.submitList(updated)
            if (updated.isEmpty()) finish()
        }
    }

    /** Force-stops ALL listed apps and dismisses the popup. */
    private fun clearAll() {
        lifecycleScope.launch {
            try {
                RecentAppsManager.clearAllApps(currentApps.map { it.packageName })
                Log.d(TAG, "Cleared all ${currentApps.size} app(s)")
            } catch (e: Exception) {
                Log.e(TAG, "clearAll failed", e)
            } finally {
                finish()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Util
    // ─────────────────────────────────────────────────────────────────────────

    /** Safe toast — catches the unlikely case where show() itself throws. */
    private fun showToast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Toast failed: $msg", e)
        }
    }
}
