package com.douai.module.feature

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.douai.module.ui.ModuleTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w296dp-h560dp-port-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThirdPartyDownloadDialogVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var session: ThirdPartyDownloadDialog? = null
    private var saves = 0
    private var cancels = 0

    @Before fun forceHostLightTheme() {
        compose.runOnUiThread { compose.activity.setTheme(android.R.style.Theme_Material_Light) }
    }

    @After fun close() {
        compose.runOnUiThread {
            session?.close()
            session = null
            assertEquals("Preview navigation must not save media", 0, saves)
        }
    }

    @Test fun smallTypeMenuAndScrollablePicturePickerUseRealMiuixControlsAndRetainSelections() {
        open(14, 3)
        compose.runOnIdle {
            assertEquals(listOf("图片（14）", "视频（1）", "动态视频（3）", "音频（1）", "文件（1）"), labels())
        }
        button(Dialog.BUTTON_NEGATIVE).assertIsDisplayed()
        render("third-party-types-small")
        choice(0).performClick()
        category("图片")
        choice(0).performClick()
        pickerButtons()
        button(Dialog.BUTTON_POSITIVE).performClick()
        compose.runOnIdle { assertTrue(dialog().isShowing); assertEquals(0, saves) }
        choice(0).assertIsDisplayed().performClick()
        compose.onNodeWithTag("miuix.dialog.choices").performScrollToIndex(13)
        choice(13).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(dialog().listView.isItemChecked(0))
            assertTrue(dialog().listView.isItemChecked(13))
            assertEquals("data.images · 第 15 项", labels()[13])
            assertEquals(0, cancels)
        }
        pickerButtons()
        render("third-party-images-picker-small")
        button(Dialog.BUTTON_NEUTRAL).performClick()
        category("图片")
        button(Dialog.BUTTON_NEUTRAL).performClick()
        compose.runOnIdle { assertEquals(5, labels().size); assertEquals(0, cancels) }
        button(Dialog.BUTTON_NEGATIVE).performClick()
        compose.runOnIdle { assertEquals(1, cancels); assertFalse(dialog().isShowing) }
    }

    @Test @Config(qualifiers = "w296dp-h560dp-port-night-mdpi")
    fun systemDarkModeOverridesTheLightHostForSingleAudioCategory() {
        open(3, 2)
        choice(3).performClick()
        compose.runOnIdle {
            assertTrue(ModuleTheme.isDark(compose.activity))
            val background = TypedValue()
            assertTrue(compose.activity.theme.resolveAttribute(android.R.attr.colorBackground, background, true))
            assertTrue(brightness(background.data) > 600)
            assertEquals(listOf("保存音频 · data.audio · 第 1 项"), labels())
        }
        choice(0).assertIsDisplayed()
        button(Dialog.BUTTON_NEGATIVE).assertIsDisplayed()
        button(Dialog.BUTTON_NEUTRAL).assertIsDisplayed()
        render("third-party-audio-system-dark", requireDark = true)
        button(Dialog.BUTTON_NEUTRAL).performClick()
        compose.runOnIdle { assertEquals(0, cancels); assertEquals(5, labels().size) }
        button(Dialog.BUTTON_NEGATIVE).performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-notnight-mdpi")
    fun landscapeMotionPickerShowsFieldSourcesAndKeepsSaveBackAndCancelVisible() {
        open(3, 12)
        compose.onNodeWithTag("miuix.dialog.choices").performScrollToIndex(2)
        choice(2).assertIsDisplayed().performClick()
        category("动态视频")
        choice(0).performClick()
        compose.onNodeWithTag("miuix.dialog.choices").performScrollToIndex(11)
        choice(11).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(dialog().listView.isItemChecked(11))
            assertEquals("data.live_video · 第 12 项", labels()[11])
        }
        pickerButtons()
        render("third-party-motion-picker-landscape")
        button(Dialog.BUTTON_NEUTRAL).performClick()
        category("动态视频")
        render("third-party-motion-category-landscape")
        button(Dialog.BUTTON_NEGATIVE).performClick()
        compose.runOnIdle { assertEquals(1, cancels); assertEquals(0, saves) }
    }

    private fun open(images: Int, motion: Int) {
        val plan = ThirdPartyDownloadDialogTest.planFixture(images, motion)
        compose.runOnUiThread {
            session = ThirdPartyDownloadDialog(compose.activity, plan, { saves++ }, { cancels++ }, { true })
            session!!.show()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotNull(dialog().window!!.decorView.findViewWithTag<View>("miuix.dialog.root"))
        }
    }

    private fun category(name: String) {
        compose.runOnIdle { assertEquals(listOf("选择${name}保存", "保存全部$name"), labels()) }
        choice(0).assertIsDisplayed()
        choice(1).assertIsDisplayed()
        button(Dialog.BUTTON_NEGATIVE).assertIsDisplayed()
        button(Dialog.BUTTON_NEUTRAL).assertIsDisplayed()
    }

    private fun pickerButtons() {
        button(Dialog.BUTTON_NEGATIVE).assertIsDisplayed()
        button(Dialog.BUTTON_NEUTRAL).assertIsDisplayed()
        button(Dialog.BUTTON_POSITIVE).assertIsDisplayed()
    }

    private fun choice(index: Int) = compose.onNodeWithTag("miuix.dialog.choice.$index")
    private fun button(which: Int) = compose.onNodeWithTag("miuix.dialog.button.$which")
    private fun dialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog
    private fun labels(): List<String> = (0 until dialog().listView.count)
        .map { dialog().listView.adapter.getItem(it).toString() }

    private fun render(name: String, requireDark: Boolean = false) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val window = dialog().window!!
            val decor = window.decorView
            val width = window.attributes.width.takeIf { it > 0 } ?: decor.width
            val height = window.attributes.height.takeIf { it > 0 } ?: decor.height
            val metrics = compose.activity.resources.displayMetrics
            assertTrue(width in 1..metrics.widthPixels)
            assertTrue(height in 1..metrics.heightPixels)
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                assertTrue("Miuix third-party dialog must render content", pixels.toSet().size > 8)
                if (requireDark) {
                    val surface = pixels.asSequence().filter { Color.alpha(it) > 200 }
                        .groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                    assertTrue("The Miuix surface must follow system dark mode", brightness(surface) < 450)
                }
                val folder = System.getenv("DOUAI_CHAT_SCREENSHOTS") ?: return@runOnUiThread
                val directory = File(folder)
                assertTrue(directory.isAbsolute)
                assertTrue(directory.isDirectory || directory.mkdirs())
                File(directory, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        compose.waitForIdle()
    }

    private fun brightness(color: Int) = Color.red(color) + Color.green(color) + Color.blue(color)
}
