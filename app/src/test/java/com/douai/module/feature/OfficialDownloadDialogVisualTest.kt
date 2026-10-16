package com.douai.module.feature

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.douai.module.config.LocalOptions
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

/** Exercises the actual Miuix composition and exports the same windows users interact with. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w296dp-h560dp-port-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfficialDownloadDialogVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var session: OfficialDownloadDialog? = null
    private var taskCount = 0

    @Before fun forceTheHostActivityLight() {
        compose.runOnUiThread {
            compose.activity.setTheme(android.R.style.Theme_Material_Light)
            taskCount = DownloadTasks.snapshots().size
        }
    }

    @After fun closeDialog() {
        compose.runOnUiThread {
            session?.close()
            session = null
            assertEquals("Preview navigation must not submit media", taskCount, DownloadTasks.snapshots().size)
        }
    }

    @Test fun smallTopLevelShowsOnlyThreeTypesAndPictureActionsHaveBackAndCancel() {
        open(gallery(12))
        assertTypes(12)
        button(Dialog.BUTTON_NEGATIVE).assertIsDisplayed()
        button(Dialog.BUTTON_NEUTRAL).assertDoesNotExist()
        render("official-types-small")

        choice(0).performClick()
        assertCategory("图片")
        render("official-images-small")
        button(Dialog.BUTTON_NEUTRAL).performClick()
        assertTypes(12)
        button(Dialog.BUTTON_NEGATIVE).performClick()
        compose.runOnIdle { assertFalse(currentDialog().isShowing) }
    }

    @Test @Config(qualifiers = "w296dp-h560dp-port-night-mdpi")
    fun systemNightModeColorsTheMiuixVideoDialogEvenWhenHostThemeIsLight() {
        val work = OfficialDownloadDialogTest.Work().apply {
            video = video("https://media.example/video.mp4")
            music = OfficialDownloadDialogTest.Music().apply {
                play_url = DownloadActionHookTest.UrlFixture("https://media.example/music.mp3")
            }
        }
        open(work)
        compose.runOnIdle {
            assertTrue(ModuleTheme.isDark(compose.activity))
            val hostBackground = TypedValue()
            assertTrue(compose.activity.theme.resolveAttribute(android.R.attr.colorBackground, hostBackground, true))
            assertTrue("Host theme is intentionally light", brightness(hostBackground.data) > 600)
            val labels = labels()
            assertEquals(2, labels.size)
            assertTrue(labels[0].contains("视频"))
            assertTrue(labels[1].contains("音频"))
        }
        choice(0).assertIsDisplayed()
        choice(1).assertIsDisplayed()
        button(Dialog.BUTTON_NEGATIVE).assertIsDisplayed()
        render("official-video-system-dark", requireDark = true)
        button(Dialog.BUTTON_NEGATIVE).performClick()
    }

    @Test fun multipleDynamicVideosCanBeCheckedAndScrolledWithSaveBackAndCancelVisible() {
        open(gallery(14))
        choice(1).performClick()
        assertCategory("动态视频")
        choice(1).performClick()
        assertPicker(14)
        button(Dialog.BUTTON_POSITIVE).performClick()
        compose.runOnIdle { assertTrue(currentDialog().isShowing) }
        choice(0).performClick()
        compose.runOnIdle { assertTrue(currentDialog().listView.isItemChecked(0)) }

        compose.onNodeWithTag("miuix.dialog.choices").performScrollToIndex(13)
        choice(13).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(currentDialog().listView.isItemChecked(0))
            assertTrue(currentDialog().listView.isItemChecked(13))
        }
        assertPickerButtons()
        render("official-dynamic-picker-small")
        button(Dialog.BUTTON_NEUTRAL).performClick()
        assertCategory("动态视频")
        button(Dialog.BUTTON_NEGATIVE).performClick()
    }

    @Test @Config(qualifiers = "w640dp-h360dp-land-notnight-mdpi")
    fun landscapeLivePickerKeepsAllActionsVisibleAndReturnsToItsCategory() {
        open(gallery(6))
        assertTypes(6)
        choice(2).performClick()
        assertCategory("实况")
        choice(1).performClick()
        assertPicker(6)
        choice(0).performClick()
        compose.runOnIdle { assertTrue(currentDialog().listView.isItemChecked(0)) }
        assertPickerButtons()
        render("official-live-picker-landscape")
        button(Dialog.BUTTON_NEUTRAL).performClick()
        assertCategory("实况")
        render("official-live-category-landscape")
        button(Dialog.BUTTON_NEUTRAL).performClick()
        assertTypes(6)
        button(Dialog.BUTTON_NEGATIVE).performClick()
    }

    private fun open(work: OfficialDownloadDialogTest.Work) {
        compose.runOnUiThread {
            session = OfficialDownloadDialog(compose.activity, work,
                LocalOptions(mapOf("download_method" to "official")), { }, { true })
            session!!.show()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(currentDialog().isShowing)
            assertNotNull(currentDialog().window!!.decorView.findViewWithTag<View>("miuix.dialog.root"))
        }
    }

    private fun assertTypes(count: Int) {
        compose.runOnIdle {
            val labels = labels()
            assertEquals("Only media types belong at the top level", 3, labels.size)
            listOf("图片", "动态视频", "实况").forEachIndexed { index, name ->
                assertTrue("Expected $name type with count: ${labels[index]}", labels[index].startsWith(name))
                assertTrue("Expected source count in type: ${labels[index]}", labels[index].contains(count.toString()))
                assertFalse(labels[index].contains("当前"))
                assertFalse(labels[index].contains("全部"))
            }
        }
        (0..2).forEach { choice(it).assertIsDisplayed() }
        compose.onAllNodesWithTag("miuix.dialog.choice.3").assertCountEquals(0)
    }

    private fun assertCategory(type: String) {
        compose.runOnIdle {
            val labels = labels()
            assertEquals(3, labels.size)
            assertTrue("$type current action", labels[0].contains("当前"))
            assertTrue("$type select action", labels[1].contains("选择"))
            assertTrue("$type all action", labels[2].contains("全部"))
        }
        (0..2).forEach { choice(it).assertIsDisplayed() }
        button(Dialog.BUTTON_NEGATIVE).assertIsDisplayed()
        button(Dialog.BUTTON_NEUTRAL).assertIsDisplayed()
    }

    private fun assertPicker(count: Int) {
        compose.runOnIdle { assertEquals(count, currentDialog().listView.count) }
        assertPickerButtons()
    }

    private fun assertPickerButtons() {
        button(Dialog.BUTTON_NEGATIVE).assertIsDisplayed()
        button(Dialog.BUTTON_NEUTRAL).assertIsDisplayed()
        button(Dialog.BUTTON_POSITIVE).assertIsDisplayed()
    }

    private fun choice(index: Int) = compose.onNodeWithTag("miuix.dialog.choice.$index")
    private fun button(which: Int) = compose.onNodeWithTag("miuix.dialog.button.$which")

    private fun currentDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    private fun labels(): List<String> {
        val list = currentDialog().listView
        return (0 until list.count).map { list.adapter.getItem(it).toString() }
    }

    private fun gallery(count: Int): OfficialDownloadDialogTest.Work = OfficialDownloadDialogTest.Work().apply {
        photosCurPos = 0
        images = (0 until count).map { index ->
            OfficialDownloadDialogTest.Image("https://media.example/image-${index + 1}.jpg").apply {
                clip_type = 5
                video = video("https://media.example/motion-${index + 1}.mp4")
            }
        }.toMutableList()
    }

    private fun video(url: String) = DownloadActionHookTest.VideoFixture().apply {
        play_addr = DownloadActionHookTest.UrlFixture(url)
    }

    private fun render(name: String, requireDark: Boolean = false) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val window = currentDialog().window!!
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
                val colors = pixels.toSet()
                assertTrue("Miuix dialog must render its controls", colors.size > 8)
                if (requireDark) {
                    val background = pixels.asSequence().filter { Color.alpha(it) > 200 }
                        .groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                    assertTrue("The rendered Miuix surface must use system dark colors", brightness(background) < 450)
                }
                val folder = System.getenv("DOUAI_CHAT_SCREENSHOTS") ?: return@runOnUiThread
                val directory = File(folder)
                assertTrue("Screenshot destination must be absolute", directory.isAbsolute)
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
