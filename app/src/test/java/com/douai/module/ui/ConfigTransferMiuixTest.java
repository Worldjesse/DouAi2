package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.compose.ui.test.ActionsKt;
import androidx.compose.ui.test.AssertionsKt;
import androidx.compose.ui.test.FiltersKt;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import com.douai.module.ui.miuix.MiuixFormControls;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w296dp-h560dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ConfigTransferMiuixTest {
    @Rule public final AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> compose =
            AndroidComposeTestRule_androidKt.createAndroidComposeRule(ComponentActivity.class, EmptyCoroutineContext.INSTANCE);
    private ConfigTransferDialog page;
    private boolean selected;

    @After public void close() {
        compose.runOnUiThread(() -> { if (page != null) { page.close(); page.dialog().dismiss(); } return Unit.INSTANCE; });
    }
    @Test public void narrowBackupPageCanScrollToItsExportActionAndClose() { verify(); }
    @Test @Config(sdk = 28, qualifiers = "w640dp-h360dp-land-mdpi")
    public void landscapeBackupPageCanScrollToItsExportActionAndClose() { verify(); }
    @Test @Config(sdk = 28, qualifiers = "w296dp-h560dp-night-mdpi")
    public void darkNarrowBackupPageCanScrollToItsExportActionAndClose() { verify(true); }

    private void verify() { verify(false); }

    private void verify(boolean night) {
        compose.runOnUiThread(() -> {
            page = new ConfigTransferDialog(compose.getActivity(), new ConfigTransferDialogTest.MemoryStore(), true,
                    () -> { }, new ConfigTransferDialog.Files() {
                        public java.io.InputStream read(android.net.Uri uri) { throw new AssertionError(); }
                        public java.io.OutputStream write(android.net.Uri uri) { throw new AssertionError(); }
                    }, (activity, export, name, callback) -> { selected = export; return () -> { }; }, Runnable::run);
            page.dialog().show();
            return Unit.INSTANCE;
        });
        compose.waitForIdle();
        compose.runOnUiThread(() -> {
            View decor = page.dialog().getWindow().getDecorView();
            assertEquals(night, ModuleTheme.isDark(decor.getContext()));
            TextView note = decor.findViewWithTag("config.backup.note");
            assertEquals("Backup text must remain legible in the system theme", night,
                    Color.luminance(note.getCurrentTextColor()) > 0.35f);
            CheckBox checkbox = decor.findViewWithTag("config.backup.secrets");
            assertFalse(checkbox.isChecked());
            ScrollView scroll = decor.findViewWithTag("config.backup.scroll");
            View export = MiuixFormControls.viewFor(decor.findViewWithTag("config.backup.export"));
            scroll.scrollTo(0, export.getTop());
            assertTrue(scroll.getWidth() <= decor.getWidth());
            assertTrue(scroll.getHeight() > 0);
            return Unit.INSTANCE;
        });
        compose.waitForIdle();
        AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("config.backup.export"), false));
        ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("config.backup.export"), false));
        assertTrue(selected);
        compose.runOnUiThread(() -> {
            View decor = page.dialog().getWindow().getDecorView();
            String location = System.getenv("DOUAI_CHAT_SCREENSHOTS");
            if (location != null && decor.getWidth() > 0 && decor.getHeight() > 0) {
                Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    decor.draw(new Canvas(bitmap));
                    File folder = new File(location);
                    assertTrue(folder.isDirectory() || folder.mkdirs());
                    try (FileOutputStream out = new FileOutputStream(new File(folder,
                            "config-backup-" + (night ? "night-" : "light-")
                                    + decor.getWidth() + "x" + decor.getHeight() + ".png"))) {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
                    } catch (Exception failure) { throw new AssertionError(failure); }
                } finally { bitmap.recycle(); }
            }
            page.dialog().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            return Unit.INSTANCE;
        });
        compose.waitForIdle();
        assertFalse(page.dialog().isShowing());
    }
}
