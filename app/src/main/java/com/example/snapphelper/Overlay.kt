package com.example.snapphelper

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

// Text box drawn over the screen by the accessibility service. It never takes touches.
class Overlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var tv: TextView? = null
    private var added = false

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun isShown(): Boolean = added

    private fun makeView(): TextView {
        val v = TextView(ctx)
        v.setTextColor(Color.WHITE)
        v.textSize = 15f
        v.setPadding(dp(14), dp(10), dp(14), dp(10))
        v.layoutDirection = View.LAYOUT_DIRECTION_RTL
        v.textDirection = View.TEXT_DIRECTION_RTL
        v.gravity = Gravity.START
        v.setLineSpacing(0f, 1.15f)
        return v
    }

    private fun params(): WindowManager.LayoutParams {
        val w = ctx.resources.displayMetrics.widthPixels - dp(24)
        val p = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = dp(70)
        return p
    }

    fun show(text: String, level: Int) {
        try {
            var v = tv
            if (v == null) {
                v = makeView()
                tv = v
            }
            v.text = text
            val bg = GradientDrawable()
            bg.cornerRadius = dp(14).toFloat()
            bg.setColor(
                when (level) {
                    1 -> Color.parseColor("#EB146B3A")
                    2 -> Color.parseColor("#EB8A6A00")
                    3 -> Color.parseColor("#EB9B1C1C")
                    else -> Color.parseColor("#EB1F3A5F")
                }
            )
            v.background = bg
            if (!added) {
                wm.addView(v, params())
                added = true
            }
        } catch (e: Throwable) {
        }
    }

    fun hide() {
        try {
            val v = tv
            if (added && v != null) wm.removeView(v)
        } catch (e: Throwable) {
        }
        added = false
    }
}
