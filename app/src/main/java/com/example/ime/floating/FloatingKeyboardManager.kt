package com.example.ime.floating

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.example.logger.LogKeeper

/**
 * FloatingKeyboardManager controls the ultra-lightweight system overlay window.
 * Reparents the existing active KeyboardView without duplicating layouts or consuming extra RAM.
 */
class FloatingKeyboardManager(
    private val context: Context,
    private val onSwitchToDockedRequested: (View) -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val displayMetrics = context.resources.displayMetrics

    private var floatingContainer: FloatingKeyboardContainer? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var activeKeyboardView: View? = null

    var isFloatingActive: Boolean = false
        private set

    fun getContentContainer(): android.widget.FrameLayout? = floatingContainer?.contentContainer

    fun toggleFloating(keyboardView: View, onReparentDocked: () -> Unit) {
        if (isFloatingActive) {
            switchToDocked()
        } else {
            showFloating(keyboardView, onReparentDocked)
        }
    }

    fun showFloating(keyboardView: View, onReparentDocked: () -> Unit) {
        if (!Settings.canDrawOverlays(context)) {
            LogKeeper.logEvent("FloatingManager", "SYSTEM_ALERT_WINDOW permission missing, requesting from user")
            Toast.makeText(
                context,
                "Please grant 'Display over other apps' to use the Floating Keyboard",
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                LogKeeper.logError("FloatingManager", "PERMISSION_INTENT_FAIL", e.message ?: "")
            }
            return
        }

        if (isFloatingActive) return

        activeKeyboardView = keyboardView
        onReparentDocked() // Detach from docked container

        val defaultWidth = (displayMetrics.widthPixels * 0.82f).toInt()
        val defaultHeight = (260f * density).toInt()

        val params = WindowManager.LayoutParams(
            defaultWidth,
            defaultHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Anchored in bottom-right corner
            gravity = Gravity.BOTTOM or Gravity.END
            x = (16f * density).toInt()
            y = (24f * density).toInt()
        }
        windowParams = params

        val container = FloatingKeyboardContainer(
            context = context,
            onCloseClicked = {
                dismissFloating()
            },
            onSwitchToMainClicked = {
                switchToDocked()
            },
            onResizeDelta = { dw, dh ->
                handleResize(dw, dh)
            },
            onMoveDelta = { dx, dy ->
                handleMove(dx, dy)
            }
        )

        container.attachKeyboardView(keyboardView)
        try {
            windowManager.addView(container, params)
            floatingContainer = container
            isFloatingActive = true
            LogKeeper.logEvent("FloatingManager", "Floating keyboard presented in bottom-right corner")
        } catch (e: Exception) {
            LogKeeper.logError("FloatingManager", "ADD_WINDOW_FAIL", e.message ?: "")
            // Fallback: restore docked
            container.detachKeyboardView()
            onSwitchToDockedRequested(keyboardView)
        }
    }

    fun switchToDocked() {
        if (!isFloatingActive) return
        val view = activeKeyboardView ?: floatingContainer?.detachKeyboardView()
        dismissFloating()
        view?.let {
            onSwitchToDockedRequested(it)
        }
        LogKeeper.logEvent("FloatingManager", "Floating keyboard switched back to docked main layout")
    }

    fun dismissFloating() {
        if (!isFloatingActive) return
        floatingContainer?.let { container ->
            container.detachKeyboardView()
            try {
                windowManager.removeView(container)
            } catch (e: Exception) {
                LogKeeper.logError("FloatingManager", "REMOVE_WINDOW_FAIL", e.message ?: "")
            }
        }
        floatingContainer = null
        windowParams = null
        isFloatingActive = false
        LogKeeper.logEvent("FloatingManager", "Floating keyboard dismissed")
    }

    private fun handleResize(dw: Int, dh: Int) {
        val params = windowParams ?: return
        val container = floatingContainer ?: return

        val minW = (220f * density).toInt()
        val maxW = (displayMetrics.widthPixels * 0.95f).toInt()
        val minH = (160f * density).toInt()
        val maxH = (displayMetrics.heightPixels * 0.70f).toInt()

        // Dragging resize handle expands down-right or up-left
        params.width = (params.width + dw).coerceIn(minW, maxW)
        params.height = (params.height + dh).coerceIn(minH, maxH)

        try {
            windowManager.updateViewLayout(container, params)
        } catch (e: Exception) {
            LogKeeper.logError("FloatingManager", "RESIZE_UPDATE_FAIL", e.message ?: "")
        }
    }

    private fun handleMove(dx: Int, dy: Int) {
        val params = windowParams ?: return
        val container = floatingContainer ?: return

        // Because gravity is Gravity.END and Gravity.BOTTOM, moving left increases x, moving up increases y
        params.x = (params.x - dx).coerceAtLeast(0)
        params.y = (params.y - dy).coerceAtLeast(0)

        try {
            windowManager.updateViewLayout(container, params)
        } catch (e: Exception) {
            LogKeeper.logError("FloatingManager", "MOVE_UPDATE_FAIL", e.message ?: "")
        }
    }
}
