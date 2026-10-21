package com.ss.android.ugc.aweme.im.business.chat.feature.input.drawer.`new`

import android.content.Context
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import com.ss.android.ugc.aweme.im.widget.SearchableEditText
import com.ss.android.ugc.aweme.im.sdk.widget.AutoRTLImageView

/** Test-only view factory, with no input logic and no transport methods. */
class InputDrawerUI2 {
    companion object {
        @JvmField var createCalls = 0
        @JvmField var omitInput = false

        @JvmStatic fun LJLJJLL(context: Context): ConstraintLayout {
            createCalls++
            val root = ConstraintLayout(context)
            root.id = com.douai.module.ui.HostProfileFixture.id(context, 0x7f0a577c)
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            root.layoutParams = android.view.ViewGroup.MarginLayoutParams(-1, -2).apply {
                setMargins(dp(8), dp(10), dp(8), dp(10))
            }
            val left = LinearLayout(context).apply { id = com.douai.module.ui.HostProfileFixture.id(context, 0x7f0a6a13) }
            val right = LinearLayout(context).apply { id = com.douai.module.ui.HostProfileFixture.id(context, 0x7f0a9dbe) }
            val body = LinearLayout(context).apply {
                id = com.douai.module.ui.HostProfileFixture.id(context, 0x7f0a0f00)
                gravity = android.view.Gravity.BOTTOM
            }
            body.addView(left, LinearLayout.LayoutParams(-2, -2))
            if (!omitInput) {
                val input = SearchableEditText(context).apply {
                    id = com.douai.module.ui.HostProfileFixture.id(context, 0x7f0a7d27)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setTextSize(16f)
                    setPadding(dp(6), dp(10), dp(2), dp(10))
                    minHeight = dp(44)
                }
                body.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.addView(right, LinearLayout.LayoutParams(-2, -2))
            val send = AutoRTLImageView(context, null).apply {
                id = com.douai.module.ui.HostProfileFixture.id(context, 0x7f0a4058)
                setPadding(dp(6), 0, dp(6), 0)
            }
            body.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))
            root.addView(body, ConstraintLayout.LayoutParams(0, -2).apply {
                startToStart = 0
                endToEnd = 0
                topToTop = 0
                bottomToBottom = 0
            })
            return root
        }
    }
}
