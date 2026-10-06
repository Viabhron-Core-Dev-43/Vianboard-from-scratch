package com.example.ime.onehanded

import android.content.Context
import android.graphics.Color
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.R
import com.example.logger.LogKeeper

/**
 * OneHandedContainer wraps the active keyboard view in an ergonomic one-handed layout.
 * When enabled, the keyboard is compressed (~75% width) and docked to the left or right,
 * with a dedicated sidebar providing:
 * - Side switch (‹ / ›)
 * - Full-screen expand (⛶)
 * - Resize adjustment
 */
class OneHandedContainer(context: Context) : FrameLayout(context) {

    enum class Side {
        LEFT,
        RIGHT
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val density = resources.displayMetrics.density

    var currentSide: Side = Side.RIGHT
        private set
    var isOneHandedActive: Boolean = false
        private set

    var onStateChanged: ((isActive: Boolean, side: Side) -> Unit)? = null

    private val rootLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private val sidebar = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xFF1E293B.toInt()) // Slate 800 background
        layoutParams = LinearLayout.LayoutParams((72f * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
    }

    private val btnSwitchSide = ImageView(context).apply {
        val pad = (16f * density).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams((60f * density).toInt(), (60f * density).toInt()).apply {
            setMargins(0, (12f * density).toInt(), 0, (12f * density).toInt())
        }
        setColorFilter(Color.WHITE)
        contentDescription = context.getString(R.string.label_switch_onehanded_key)
        setOnClickListener {
            toggleSide()
        }
    }

    private val btnFullscreen = ImageView(context).apply {
        val pad = (16f * density).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams((60f * density).toInt(), (60f * density).toInt()).apply {
            setMargins(0, (12f * density).toInt(), 0, (12f * density).toInt())
        }
        setImageResource(R.drawable.sym_keyboard_stop_onehanded_rounded)
        setColorFilter(Color.WHITE)
        contentDescription = context.getString(R.string.label_stop_onehanded_mode_key)
        setOnClickListener {
            setOneHanded(false)
        }
    }

    private var currentSidebarWidthDp = 72f

    private val btnResize = ImageView(context).apply {
        val pad = (16f * density).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams((60f * density).toInt(), (60f * density).toInt()).apply {
            setMargins(0, (12f * density).toInt(), 0, (12f * density).toInt())
        }
        setImageResource(R.drawable.ic_resize)
        setColorFilter(Color.WHITE)
        contentDescription = context.getString(R.string.label_resize_onehanded_key)
        setOnClickListener {
            cycleSidebarWidth()
        }
    }

    private var keyboardView: View? = null

    init {
        // Read persisted preferences (Default: Right hand)
        val savedSide = prefs.getString("one_handed_side", "RIGHT")
        currentSide = if (savedSide == "LEFT") Side.LEFT else Side.RIGHT
        isOneHandedActive = prefs.getBoolean("one_handed_active", false)

        sidebar.addView(btnSwitchSide)
        sidebar.addView(btnFullscreen)
        sidebar.addView(btnResize)
        addView(rootLayout)

        updateSidebarIcons()
    }

    private fun cycleSidebarWidth() {
        currentSidebarWidthDp = when (currentSidebarWidthDp) {
            72f -> 92f
            92f -> 112f
            else -> 72f
        }
        sidebar.layoutParams = LinearLayout.LayoutParams((currentSidebarWidthDp * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
        sidebar.requestLayout()
        LogKeeper.logEvent("OneHandedContainer", "Resized sidebar width to ${currentSidebarWidthDp}dp")
    }

    fun attachKeyboardView(view: View) {
        if (keyboardView == view) return
        keyboardView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        keyboardView = view
        rebuildLayout()
    }

    fun detachKeyboardView(): View? {
        val view = keyboardView
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        keyboardView = null
        return view
    }

    fun toggleOneHanded() {
        setOneHanded(!isOneHandedActive)
    }

    fun setOneHanded(active: Boolean) {
        if (isOneHandedActive == active) return
        isOneHandedActive = active
        prefs.edit().putBoolean("one_handed_active", active).apply()
        LogKeeper.logEvent("OneHandedContainer", "One-handed mode set to $active (side=$currentSide)")
        rebuildLayout()
        onStateChanged?.invoke(isOneHandedActive, currentSide)
    }

    fun toggleSide() {
        currentSide = if (currentSide == Side.LEFT) Side.RIGHT else Side.LEFT
        prefs.edit().putString("one_handed_side", currentSide.name).apply()
        LogKeeper.logEvent("OneHandedContainer", "One-handed side toggled to $currentSide")
        updateSidebarIcons()
        rebuildLayout()
        onStateChanged?.invoke(isOneHandedActive, currentSide)
    }

    private fun updateSidebarIcons() {
        // When docked on Right, button points Left (‹) to move left; when on Left, points Right (›)
        if (currentSide == Side.RIGHT) {
            btnSwitchSide.setImageResource(R.drawable.ic_chevron_left)
        } else {
            btnSwitchSide.setImageResource(R.drawable.ic_chevron_right)
        }
    }

    private fun rebuildLayout() {
        rootLayout.removeAllViews()
        val kb = keyboardView ?: return

        (kb.parent as? ViewGroup)?.removeView(kb)

        if (!isOneHandedActive) {
            kb.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rootLayout.addView(kb)
        } else {
            kb.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )

            if (currentSide == Side.RIGHT) {
                // Sidebar on left, Keyboard on right
                rootLayout.addView(sidebar)
                rootLayout.addView(kb)
            } else {
                // Keyboard on left, Sidebar on right
                rootLayout.addView(kb)
                rootLayout.addView(sidebar)
            }
        }
    }
}
