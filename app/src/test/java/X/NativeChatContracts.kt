package X

/** Test-only JVM names for host classes whose obfuscated names are not Java identifiers. */
class `0s7o` {
    companion object {
        @JvmStatic fun LIZ(): Int = 0x7f0d1954
    }
}

class `0sBR` {
    companion object {
        @JvmStatic fun LIZIZ(): Int = 0xfff8f8f8.toInt()
    }
}

class `1aLM`(@JvmField val LIZ: Int)

class `1bWb` {
    companion object {
        @JvmStatic fun LIZ(self: Boolean): `1aLM` = `1aLM`(
            if (self) 0x7f083ab4 else 0x7f083ab1
        )
    }
}

class `0ryb` {
    companion object {
        @JvmStatic fun LIZ(context: android.content.Context): android.graphics.drawable.Drawable =
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * context.resources.displayMetrics.density
                setColor(android.graphics.Color.WHITE)
            }
    }
}

class `0qn3` {
    companion object {
        @JvmStatic fun LJII(drawable: android.graphics.drawable.GradientDrawable) {
            drawable.setColor(0x0d161823)
        }
    }
}

class `0sSQ` {
    companion object {
        @JvmStatic fun LIZJ(context: android.content.Context): android.widget.FrameLayout =
            android.widget.FrameLayout(context)
    }
}

class `0sWV` {
    companion object {
        @JvmStatic fun LIZIZ(parent: android.view.ViewGroup): androidx.constraintlayout.widget.ConstraintLayout {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            val root = androidx.constraintlayout.widget.ConstraintLayout(context)
            root.minimumWidth = (84 * density).toInt()
            root.setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            root.layoutParams = android.view.ViewGroup.MarginLayoutParams(-2, (32 * density).toInt())
            root.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(android.graphics.Color.WHITE)
            }
            val text = com.bytedance.ies.dmt.ui.widget.DmtTextView(context).apply {
                id = 0x7f0a6012
                textSize = 14f
                setTextColor(0xff161823.toInt())
            }
            root.addView(text, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(-2, -2).apply {
                startToStart = 0; endToEnd = 0; topToTop = 0; bottomToBottom = 0
            })
            for (id in intArrayOf(0x7f0a464c, 0x7f0a5158, 0x7f0ab99c)) {
                root.addView(android.view.View(context).apply { this.id = id },
                    androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(1, 1))
            }
            return root
        }
    }
}

class `0sNm` {
    companion object {
        @JvmStatic fun LIZ(context: android.content.Context, offset: Int, forceDoubleMargin: Boolean): Int {
            val metrics = context.resources.displayMetrics
            val margin = (56 * metrics.density).toInt()
            return minOf(metrics.widthPixels - margin * 2 + offset, (300 * metrics.density).toInt())
        }
    }
}

class `0sMT` {
    companion object {
        @JvmField var reverseColor = false
        @JvmStatic fun LJIIJ(): Boolean = reverseColor
    }
}
