package com.example.ime.floating

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R
import com.example.logger.LogKeeper

/**
 * FloatingKeyboardContainer hosts the keyboard in a compact floating window.
 * Directly below the keyboard buttons, in the bottom-right corner, sits a dedicated 3-button row:
 * [ Close ] [ Switch ] [ Resize ] in exact left-to-right order.
 */
class FloatingKeyboardContainer(
    context: Context,
    private val onCloseClicked: () -> Unit,
    private val onSwitchToMainClicked: () -> Unit,
    private val onResizeDelta: (deltaW: Int, deltaH: Int) -> Unit,
    private val onMoveDelta: (deltaX: Int, deltaY: Int) -> Unit
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    val contentContainer: FrameLayout

    init {
        orientation = VERTICAL
        // Floating card styling: rounded corners, deep translucent background, 1dp subtle border
        val bgDrawable = GradientDrawable().apply {
            setColor(0xFF1E293B.toInt()) // Midnight slate background
            cornerRadius = 14f * density
            setStroke((1f * density).toInt(), 0x33FFFFFF)
        }
        background = bgDrawable
        elevation = 8f * density
        setPadding(
            (4f * density).toInt(),
            (4f * density).toInt(),
            (4f * density).toInt(),
            (2f * density).toInt()
        )

        // 1. Content Area (reparented KeyboardView + modals)
        contentContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        addView(contentContainer)

        // 2. Bottom-Right 3-Button Control Row
        // In exact order Left to Right: Close (✕), Switch (⛶), Resize (⤡)
        val bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (34f * density).toInt()).apply {
                setMargins(0, (2f * density).toInt(), (4f * density).toInt(), (2f * density).toInt())
            }
        }

        // Drag-to-move listener on the empty space of bottomBar
        var lastMoveX = 0f
        var lastMoveY = 0f
        bottomBar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastMoveX = event.rawX
                    lastMoveY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastMoveX).toInt()
                    val dy = (event.rawY - lastMoveY).toInt()
                    lastMoveX = event.rawX
                    lastMoveY = event.rawY
                    onMoveDelta(dx, dy)
                    true
                }
                else -> false
            }
        }

        val btnPadding = (6f * density).toInt()
        val btnSize = (32f * density).toInt()

        // 1. Close Button (✕)
        val btnClose = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(0xFFEF4444.toInt()) // Red accent
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                marginEnd = (8f * density).toInt()
            }
            contentDescription = "Close floating keyboard"
            setOnClickListener {
                LogKeeper.logEvent("FloatingContainer", "Close clicked")
                onCloseClicked()
            }
        }

        // 2. Switch Button (⛶) -> Move Back to Main Docked Layout
        val btnSwitch = ImageView(context).apply {
            setImageResource(R.drawable.sym_keyboard_stop_onehanded_rounded)
            setColorFilter(Color.WHITE)
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                marginEnd = (8f * density).toInt()
            }
            contentDescription = "Switch back to main layout"
            setOnClickListener {
                LogKeeper.logEvent("FloatingContainer", "Switch to main layout clicked")
                onSwitchToMainClicked()
            }
        }

        // 3. Resize Button (⤡) -> Drag handle to resize
        var lastResizeX = 0f
        var lastResizeY = 0f
        val btnResize = TextView(context).apply {
            text = "⤡"
            textSize = 17f
            setTextColor(0xFF38BDF8.toInt()) // Cyan accent
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(btnSize, btnSize)
            contentDescription = "Resize floating keyboard drag handle"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastResizeX = event.rawX
                        lastResizeY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - lastResizeX).toInt()
                        val dy = (event.rawY - lastResizeY).toInt()
                        lastResizeX = event.rawX
                        lastResizeY = event.rawY
                        onResizeDelta(dx, dy)
                        true
                    }
                    else -> false
                }
            }
        }

        bottomBar.addView(btnClose)
        bottomBar.addView(btnSwitch)
        bottomBar.addView(btnResize)

        addView(bottomBar)
    }

    fun attachKeyboardView(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        contentContainer.removeAllViews()
        contentContainer.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun detachKeyboardView(): View? {
        val child = if (contentContainer.childCount > 0) contentContainer.getChildAt(0) else null
        contentContainer.removeAllViews()
        return child
    }
}
