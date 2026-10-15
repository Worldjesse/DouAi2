package com.douai.module.ui

import android.content.ComponentName
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import com.douai.module.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class ModuleWindowThemeTest {
    @Test
    @Config(qualifiers = "notnight")
    fun lightStartupAndRelayMatchTheModulePage() = assertManifestTheme(false)

    @Test
    @Config(qualifiers = "night")
    fun darkStartupAndRelayMatchTheModulePage() = assertManifestTheme(true)

    private fun assertManifestTheme(dark: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val background = (if (dark) darkColorScheme() else lightColorScheme()).surface.toArgb()
        for (activity in listOf(MainActivity::class.java, HostRestartActivity::class.java)) {
            val info = app.packageManager.getActivityInfo(ComponentName(app, activity), 0)
            // Android recreates these module-owned activities when system uiMode changes.
            assertEquals(0, info.configChanges and android.content.pm.ActivityInfo.CONFIG_UI_MODE)
            val context = ContextThemeWrapper(app, info.themeResource)
            val attributes = context.obtainStyledAttributes(intArrayOf(
                android.R.attr.windowBackground,
                android.R.attr.statusBarColor,
                android.R.attr.navigationBarColor,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar,
                android.R.attr.isLightTheme,
                android.R.attr.colorBackground,
            ))
            try {
                assertEquals(background, (attributes.getDrawable(0) as ColorDrawable).color)
                assertEquals(background, attributes.getColor(1, 0))
                assertEquals(background, attributes.getColor(2, 0))
                assertEquals(!dark, attributes.getBoolean(3, !dark))
                assertEquals(!dark, attributes.getBoolean(4, !dark))
                assertEquals(!dark, attributes.getBoolean(5, !dark))
                assertEquals(background, attributes.getColor(6, 0))
            } finally {
                attributes.recycle()
            }
        }
    }
}
