package com.manu.iconrecents

import android.content.Intent
import android.os.Bundle
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
 * Main UI — a transparent, bottom-anchored popup with a 4-column icon grid.
 *
 * Behaviour:
 *  - Tap outside panel  → dismiss
 *  - Tap icon           → bring app to front
 *  - Long-press icon    → force-stop and remove from list
 *  - "Clear All" button → force-stop all and dismiss
 */
class RecentsActivity : AppCompatActivity() {

    private lateinit var rvIcons: RecyclerView
    private lateinit var btnClearAll: Button
    private lateinit var adapter: AppIconAdapter

    /** Live snapshot of apps currently in the list */
    private var currentApps: List<RecentApp> = emptyList()

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        setContentView(R.layout.activity_recents)
        bindViews()
        loadRecentApps()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Window configuration — bottom-sheet popup, transparent background
    // ─────────────────────────────────────────────────────────────────────────

    private fun configureWindow() {
        supportActionBar?.hide()

        window.apply {
            // Transparent + dim behind
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            // Pin to bottom, full width, wrap height
            val lp = attributes
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.dimAmount = 0.55f
            attributes = lp
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // View binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        rvIcons = findViewById(R.id.rvIcons)
        btnClearAll = findViewById(R.id.btnClearAll)

        // 4-column grid of icons
        adapter = AppIconAdapter(
            onIconClick = { app -> launchApp(app.packageName) },
            onIconLongClick = { app -> forceStopApp(app) }
        )
        rvIcons.layoutManager = GridLayoutManager(this, 4)
        rvIcons.adapter = adapter

        btnClearAll.setOnClickListener { clearAll() }

        // Tap the transparent area outside the panel → dismiss
        findViewById<View>(R.id.rootLayout).setOnClickListener { finish() }

        // Prevent panel clicks from propagating to the transparent root
        findViewById<View>(R.id.panel).setOnClickListener { /* consume */ }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data loading
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadRecentApps() {
        lifecycleScope.launch {
            val apps = RecentAppsManager.getRecentApps(this@RecentsActivity)
            currentApps = apps
            adapter.submitList(apps)

            if (apps.isEmpty()) {
                Toast.makeText(
                    this@RecentsActivity,
                    getString(R.string.no_recent_apps),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Launches (or brings to front) the given package.
     */
    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, getString(R.string.cannot_launch), Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /**
     * Force-stops a single app and removes it from the list.
     */
    private fun forceStopApp(app: RecentApp) {
        lifecycleScope.launch {
            RecentAppsManager.forceStopApp(app.packageName)
            val updated = currentApps.filter { it.packageName != app.packageName }
            currentApps = updated
            adapter.submitList(updated)

            if (updated.isEmpty()) finish()
        }
    }

    /**
     * Force-stops ALL listed apps and dismisses the popup.
     */
    private fun clearAll() {
        lifecycleScope.launch {
            RecentAppsManager.clearAllApps(currentApps.map { it.packageName })
            finish()
        }
    }
}
