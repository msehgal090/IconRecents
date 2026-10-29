package com.manu.iconrecents

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed / LSPosed hook entry point.
 *
 * Declared in:
 *   assets/xposed_init         — classic Xposed / VirtualXposed
 *   AndroidManifest meta-data  — LSPosed
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * HOW THE RECENTS TRIGGER FLOWS ON ANDROID 15 / LINEAGEOS 22.2
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  3-button navigation:
 *    KEYCODE_APP_SWITCH
 *      → PhoneWindowManager (android process)
 *      → StatusBarManagerService.toggleRecentApps()
 *      → CentralSurfacesImpl.toggleRecentApps()   ← HOOK A
 *      → (optional) RecentsController.*            ← HOOK B
 *
 *  Gesture navigation (swipe-up-hold):
 *    Launcher / Trebuchet handles overview directly
 *      → com.android.quickstep.RecentsActivity.onCreate()  ← HOOK C
 *      OR
 *      → GestureNavContract / OverviewProxyService         ← HOOK D
 *
 * We apply EVERY hook that succeeds (not just the first), so all paths
 * are covered simultaneously.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class XposedHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "IconRecents/Xposed"
        private const val RECENTS_PKG = "com.manu.iconrecents"
        private const val RECENTS_ACTIVITY = "$RECENTS_PKG.RecentsActivity"

        // ── HOOK A + B: SystemUI candidates ──────────────────────────────────
        // Each entry: (className, methodName, vararg paramTypes)
        // We try ALL entries and attach whichever hooks resolve successfully.
        private val SYSTEMUI_HOOKS = listOf(

            // ── Android 13-15 primary (LineageOS 22.x) ───────────────────────
            Triple("com.android.systemui.statusbar.phone.CentralSurfacesImpl",
                   "toggleRecentApps",
                   emptyArray<Class<*>>()),

            Triple("com.android.systemui.statusbar.phone.CentralSurfacesImpl",
                   "showRecentApps",
                   arrayOf<Class<*>>(Boolean::class.java)),

            Triple("com.android.systemui.statusbar.phone.CentralSurfacesImpl",
                   "hideRecentApps",
                   arrayOf<Class<*>>(Boolean::class.java, Boolean::class.java)),

            // ── RecentsController (alternative path, some builds) ─────────────
            Triple("com.android.systemui.recents.RecentsController",
                   "toggleOverview",
                   emptyArray<Class<*>>()),

            Triple("com.android.systemui.recents.RecentsController",
                   "showOverview",
                   emptyArray<Class<*>>()),

            // ── OverviewProxyService — gesture nav bridge in SystemUI ──────────
            Triple("com.android.systemui.recents.OverviewProxyService",
                   "startOverview",
                   emptyArray<Class<*>>()),

            // ── Legacy StatusBar (Android 12 and some custom ROMs) ────────────
            Triple("com.android.systemui.statusbar.phone.StatusBar",
                   "toggleRecentApps",
                   emptyArray<Class<*>>()),

            Triple("com.android.systemui.statusbar.phone.StatusBar",
                   "showRecentApps",
                   arrayOf<Class<*>>(Boolean::class.java)),
        )

        // ── HOOK C: Launcher / Quickstep RecentsActivity ──────────────────────
        // Used for gesture-nav recents (swipe-up-hold)
        private val LAUNCHER_RECENTS_CLASSES = listOf(
            "com.android.quickstep.RecentsActivity",
            "com.android.launcher3.recents.RecentsActivity",
            "com.android.launcher3.taskbar.TaskbarRecentsController",
        )

        // Known package names for the launcher process
        val LAUNCHER_PACKAGES = setOf(
            "com.android.launcher3",
            "com.android.launcher",
            "com.lineageos.trebuchet",
            "com.google.android.apps.nexuslauncher",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when {
            lpparam.packageName == "com.android.systemui" ->
                hookSystemUI(lpparam)

            lpparam.packageName in LAUNCHER_PACKAGES ->
                hookLauncher(lpparam)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HOOK A + B — SystemUI
    // ─────────────────────────────────────────────────────────────────────────

    private fun hookSystemUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG: loaded into com.android.systemui — attaching hooks")
        var attached = 0

        for ((cls, method, params) in SYSTEMUI_HOOKS) {
            val ok = tryHook(
                classLoader = lpparam.classLoader,
                className = cls,
                methodName = method,
                params = params,
                hook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ctx = resolveContext(param.thisObject) ?: run {
                            XposedBridge.log("$TAG: context null for $cls#$method — skipping")
                            return
                        }
                        XposedBridge.log("$TAG: intercepted $cls#$method — launching IconRecents")
                        launchIconRecents(ctx)
                        param.result = null  // suppress original recents
                    }
                }
            )
            if (ok) {
                attached++
                XposedBridge.log("$TAG: ✅ hooked $cls#$method")
            }
        }

        XposedBridge.log("$TAG: SystemUI — $attached hook(s) attached")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HOOK C — Launcher RecentsActivity (gesture nav)
    // ─────────────────────────────────────────────────────────────────────────

    private fun hookLauncher(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("$TAG: loaded into ${lpparam.packageName} — attaching launcher hooks")
        var attached = 0

        for (cls in LAUNCHER_RECENTS_CLASSES) {
            val ok = tryHook(
                classLoader = lpparam.classLoader,
                className = cls,
                methodName = "onCreate",
                params = arrayOf(android.os.Bundle::class.java),
                hook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // afterHookedMethod: Activity is now alive and can finish itself
                        val activity = param.thisObject as? android.app.Activity ?: return
                        XposedBridge.log("$TAG: intercepted $cls#onCreate — launching IconRecents")
                        launchIconRecents(activity)
                        // Slight delay so our activity has time to start before this one finishes
                        Handler(Looper.getMainLooper()).postDelayed({
                            try { activity.finishAndRemoveTask() } catch (_: Throwable) {}
                        }, 80)
                    }
                }
            )
            if (ok) {
                attached++
                XposedBridge.log("$TAG: ✅ hooked $cls#onCreate")
            }
        }

        XposedBridge.log("$TAG: Launcher — $attached hook(s) attached")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core hook helper — attaches one hook, returns true on success
    // ─────────────────────────────────────────────────────────────────────────

    private fun tryHook(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        params: Array<Class<*>>,
        hook: XC_MethodHook
    ): Boolean = try {
        XposedHelpers.findAndHookMethod(className, classLoader, methodName, *params, hook)
        true
    } catch (_: Throwable) {
        false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Context resolution — tries multiple strategies across SystemUI versions
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveContext(obj: Any): Context? {
        // 1. Common field names used across SystemUI versions
        for (fieldName in listOf("mContext", "context", "mService", "mStatusBar")) {
            try {
                val v = XposedHelpers.getObjectField(obj, fieldName)
                if (v is Context) return v
            } catch (_: Throwable) { /* try next */ }
        }

        // 2. Common getter methods
        for (methodName in listOf("getContext", "getApplicationContext", "requireContext")) {
            try {
                val v = obj.javaClass.getMethod(methodName).invoke(obj)
                if (v is Context) return v
            } catch (_: Throwable) { /* try next */ }
        }

        // 3. Direct cast (e.g., if obj extends ContextWrapper)
        if (obj is Context) return obj

        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch IconRecents
    // ─────────────────────────────────────────────────────────────────────────

    private fun launchIconRecents(context: Context) {
        try {
            val intent = Intent().apply {
                setClassName(RECENTS_PKG, RECENTS_ACTIVITY)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK        or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP      or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: startActivity failed — ${t.message}")
        }
    }
}
