package com.manu.iconrecents

import android.content.Context
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed/LSPosed hook entry point.
 *
 * Declared in:
 *   - assets/xposed_init  (classic Xposed / VirtualXposed)
 *   - AndroidManifest meta-data (LSPosed)
 *
 * Strategy:
 *   Hook SystemUI's "show recents" method so that whenever the user triggers
 *   the Recents gesture/button, our RecentsActivity opens instead.
 *
 * Android 15 / LineageOS 22.2 class candidates (tried in order):
 *   1. com.android.systemui.statusbar.phone.CentralSurfacesImpl  (A13+)
 *   2. com.android.systemui.recents.RecentsController             (A12+)
 *   3. com.android.systemui.statusbar.phone.StatusBar             (legacy)
 */
class XposedHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "IconRecents/Xposed"
        private const val TARGET_PKG = "com.android.systemui"
        private const val RECENTS_PKG = "com.manu.iconrecents"
        private const val RECENTS_ACTIVITY = "$RECENTS_PKG.RecentsActivity"

        /**
         * Ordered list of (className, methodName, paramTypes) to try.
         * We stop at the first successful hook.
         */
        private val HOOK_CANDIDATES: List<Triple<String, String, Array<Class<*>>>> = listOf(
            // Android 15 / LineageOS 22.x — primary path
            Triple(
                "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
                "showRecentApps",
                arrayOf(Boolean::class.java)
            ),
            Triple(
                "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
                "toggleRecentApps",
                emptyArray()
            ),
            // Android 13–14 fallback
            Triple(
                "com.android.systemui.recents.RecentsController",
                "showRecentApps",
                arrayOf(Boolean::class.java, Boolean::class.java)
            ),
            // Legacy Android 12 and below
            Triple(
                "com.android.systemui.statusbar.phone.StatusBar",
                "showRecentApps",
                arrayOf(Boolean::class.java)
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IXposedHookLoadPackage
    // ─────────────────────────────────────────────────────────────────────────

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return
        XposedBridge.log("$TAG: targeting $TARGET_PKG")
        hookRecentsInSystemUI(lpparam.classLoader)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hooking logic
    // ─────────────────────────────────────────────────────────────────────────

    private fun hookRecentsInSystemUI(classLoader: ClassLoader) {
        for ((className, methodName, params) in HOOK_CANDIDATES) {
            if (tryHook(classLoader, className, methodName, params)) {
                XposedBridge.log("$TAG: hooked $className#$methodName — success")
                return
            }
        }
        XposedBridge.log("$TAG: no hook candidate matched; RecentsActivity must be launched manually")
    }

    /**
     * Attempts to hook [className]#[methodName] with [params].
     * Returns true on success, false on any throwable.
     */
    private fun tryHook(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        params: Array<Class<*>>
    ): Boolean = try {
        XposedHelpers.findAndHookMethod(
            className,
            classLoader,
            methodName,
            *params,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val context = resolveContext(param.thisObject)
                        launchIconRecents(context)
                        param.result = null   // suppress original recents
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: beforeHookedMethod error — ${t.message}")
                        // Let original recents run as fallback
                    }
                }
            }
        )
        true
    } catch (_: Throwable) {
        false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to retrieve a [Context] from the hooked object.
     * Tries common field names used across SystemUI versions.
     */
    private fun resolveContext(thisObject: Any): Context {
        val fieldNames = listOf("mContext", "mService", "context")
        for (name in fieldNames) {
            try {
                val value = XposedHelpers.getObjectField(thisObject, name)
                if (value is Context) return value
            } catch (_: Throwable) { /* try next */ }
        }
        // Last resort — thisObject itself might be a Context (ContextWrapper subclass)
        if (thisObject is Context) return thisObject
        throw IllegalStateException("Cannot resolve Context from $thisObject")
    }

    private fun launchIconRecents(context: Context) {
        val intent = Intent().apply {
            setClassName(RECENTS_PKG, RECENTS_ACTIVITY)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        context.startActivity(intent)
    }
}
