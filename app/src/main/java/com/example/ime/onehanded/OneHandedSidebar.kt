package com.example.ime.onehanded

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.R

/**
 * OneHandedSidebar provides ergonomic side controls when one-handed mode is active:
 * - Switch side (‹ / ›) to swap between left and right hands.
 * - Fullscreen expand (⛶) to restore standard full-width layout.
 */
class OneHandedSidebar(
    context: Context,
    private val onToggleSideRequested: () -> Unit,
    private val onExitRequested: () -> Unit
) : LinearLayout(context) {

    enum class Side {
        LEFT,
        RIGHT
    }

    private val density = resources.displayMetrics.density

    private val btnSwitchSide = ImageView(context).apply {
        val pad = (16f * density).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LayoutParams((60f * density).toInt(), (60f * density).toInt()).apply {
            setMargins(0, (12f * density).toInt(), 0, (12f * density).toInt())
        }
        setColorFilter(Color.WHITE)
        contentDescription = context.getString(R.string.label_switch_onehanded_key)
        setOnClickListener {
            onToggleSideRequested()
        }
    }

    private val btnFullscreen = ImageView(context).apply {
        val pad = (16f * density).toInt()
        setPadding(pad, pad, pad, pad)
        layoutParams = LayoutParams((60f * density).toInt(), (60f * density).toInt()).apply {
            setMargins(0, (12f * density).toInt(), 0, (12f * density).toInt())
        }
        setImageResource(R.drawable.sym_keyboard_stop_onehanded_rounded)
        setColorFilter(Color.WHITE)
        contentDescription = context.getString(R.string.label_stop_onehanded_mode_key)
        setOnClickListener {
            onExitRequested()
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xFF1E293B.toInt()) // Slate 800 background
        layoutParams = FrameLayout.LayoutParams((72f * density).toInt(), FrameLayout.LayoutParams.MATCH_PARENT)
        visibility = GONE

        addView(btnSwitchSide)
        addView(btnFullscreen)
    }

    fun updateSide(side: Side) {
        if (side == Side.RIGHT) {
            btnSwitchSide.setImageResource(R.drawable.ic_chevron_left)
            (layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.START
        } else {
            btnSwitchSide.setImageResource(R.drawable.ic_chevron_right)
            (layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.END
        }
    }
}
