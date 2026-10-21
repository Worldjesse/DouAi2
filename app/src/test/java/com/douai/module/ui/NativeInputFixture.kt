package com.douai.module.ui

import com.ss.android.ugc.aweme.im.business.chat.feature.input.drawer.`new`.InputDrawerUI2

/** Java-accessible fixture control for the host's reserved-word package. */
object NativeInputFixture {
    @JvmStatic fun reset() {
        InputDrawerUI2.omitInput = false
        InputDrawerUI2.createCalls = 0
        X.`0UGJ`.reverseColor = false
        X.`0sMT`.reverseColor = false
    }
    @JvmStatic fun setOmitInput(omit: Boolean) { InputDrawerUI2.omitInput = omit }
    @JvmStatic fun createCalls(): Int = InputDrawerUI2.createCalls
    @JvmStatic fun setReverseColor(enabled: Boolean) { X.`0UGJ`.reverseColor = enabled; X.`0sMT`.reverseColor = enabled }
}
