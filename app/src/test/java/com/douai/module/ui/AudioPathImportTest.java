package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AudioPathImportTest {
    private static final String PATH_KEY = "comment_voice_path";
    private static final String ORIGINAL_PATH = "/storage/emulated/0/Music/original.mp3";
    private static final byte[] AUDIO = {0x52, 0x49, 0x46, 0x46, 0x01, 0x02, 0x03, 0x04};

    private RegistryActivity activity;
    private ModuleSettingsScreen screen;
    private MemoryStore store;
    private View home;

    @Before public void createScreen() {
        activity = Robolectric.buildActivity(RegistryActivity.class).setup().get();
        store = new MemoryStore();
        store.putString(PATH_KEY, ORIGINAL_PATH);
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
    }

    @After public void closeScreen() {
        screen.close();
        activity.finish();
    }

    @Test public void cancellingAfterAsynchronousImportKeepsTheOriginalPathAndDeletesTheDraft() throws Exception {
        Map<String, Object> saved = new HashMap<>(store.values);
        AlertDialog editor = openAudioEditor();
        EditText input = pathInput(editor);
        selectAudio(editor, Uri.parse("content://audio/cancel.wav"), new ByteArrayInputStream(AUDIO));
        await(() -> !ORIGINAL_PATH.equals(input.getText().toString()), "Audio import did not update the draft path");
        assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertTrue(findSelectButton(editor.getWindow().getDecorView()).isEnabled());
        File imported = new File(input.getText().toString());
        assertTrue(imported.isFile());
        assertArrayEquals(AUDIO, Files.readAllBytes(imported.toPath()));
        assertEquals("Importing a file must leave configuration unchanged until Save", saved, store.values);

        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(editor.isShowing());
        assertEquals(saved, store.values);
        assertEquals(ORIGINAL_PATH, store.getString(PATH_KEY, ""));
        assertFalse("Cancelling must remove the unused imported audio", imported.exists());
    }

    @Test public void savingAfterAsynchronousImportCommitsTheDraftAndRetainsItsAudio() throws Exception {
        Map<String, Object> saved = new HashMap<>(store.values);
        AlertDialog editor = openAudioEditor();
        EditText input = pathInput(editor);
        selectAudio(editor, Uri.parse("content://audio/save.wav"), new ByteArrayInputStream(AUDIO));
        await(() -> !ORIGINAL_PATH.equals(input.getText().toString()), "Audio import did not update the draft path");
        assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertTrue(findSelectButton(editor.getWindow().getDecorView()).isEnabled());
        File imported = new File(input.getText().toString());
        assertTrue(imported.isFile());
        assertEquals(saved, store.values);

        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(editor.isShowing());
        assertEquals(imported.getAbsolutePath(), store.getString(PATH_KEY, ""));
        assertTrue("Saved audio must remain available to the replacement feature", imported.isFile());
        assertArrayEquals(AUDIO, Files.readAllBytes(imported.toPath()));
        screen.close();
        assertTrue("Closing settings must preserve the saved audio", imported.isFile());
    }

    @Test public void cancellingTheSystemPickerRestoresSavingWithoutChangingTheDraft() {
        Map<String, Object> saved = new HashMap<>(store.values);
        AlertDialog editor = openAudioEditor();
        launchPicker(editor);
        activity.registry.dispatchResult(activity.registry.code, Activity.RESULT_CANCELED, null);
        await(() -> editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),
                "Cancelling the document picker must restore Save");
        assertTrue(findSelectButton(editor.getWindow().getDecorView()).isEnabled());
        assertEquals(ORIGINAL_PATH, pathInput(editor).getText().toString());
        assertEquals(saved, store.values);
        pathInput(editor).setText("/storage/emulated/0/Music/manual.mp3");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(editor.isShowing());
        assertEquals("/storage/emulated/0/Music/manual.mp3", store.getString(PATH_KEY, ""));
    }

    @Test public void closingSettingsDuringImportRejectsTheLateResultAndDeletesItsAudio() {
        Map<String, Object> saved = new HashMap<>(store.values);
        Set<String> existing = importedFiles();
        AlertDialog editor = openAudioEditor();
        EditText input = pathInput(editor);
        BlockingAudio inputStream = new BlockingAudio();
        try {
            selectAudio(editor, Uri.parse("content://audio/late.wav"), inputStream);
            await(() -> inputStream.reading.getCount() == 0, "The asynchronous audio import did not start");
            assertFalse("Save stays disabled throughout the asynchronous copy",
                    editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            Set<String> files = importedFiles();
            files.removeAll(existing);
            assertEquals("Exactly one imported file should be in flight", 1, files.size());
            File pending = new File(files.iterator().next());
            assertTrue(pending.exists());
            screen.close();
            assertFalse(editor.isShowing());
            inputStream.release.countDown();

            await(() -> !pending.exists(), "A late import must delete its audio after settings close");
            assertEquals(ORIGINAL_PATH, input.getText().toString());
            assertEquals(saved, store.values);
            assertEquals(ORIGINAL_PATH, store.getString(PATH_KEY, ""));
        } finally {
            inputStream.release.countDown();
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void settingsRemainAliveWhileSystemAudioPickerIsOpen() {
        RegistryActivity hostActivity = Robolectric.buildActivity(RegistryActivity.class).setup().get();
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        HostImagePicker picker = null;
        try {
            controller.setSettingsPageTargets(RegistryActivity.class, ScrollView.class);
            controller.onActivityResumed(hostActivity);
            ShadowLooper.idleMainLooper();
            View entry = hostActivity.findViewById(android.R.id.content)
                    .findViewWithTag("DouAi.HostSettingsEntry");
            assertNotNull("The host fixture must expose the module settings entry", entry);
            assertTrue(entry.performClick());
            Dialog settings = ShadowDialog.getLatestDialog();
            assertTrue(settings.isShowing());

            picker = HostAudioPicker.launch(hostActivity, ignored -> { });
            assertNotNull(picker);
            assertTrue(HostImagePicker.isPending(hostActivity));
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, hostActivity.registry.intent.getAction());
            assertArrayEquals(new String[]{"audio/*"},
                    hostActivity.registry.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
            controller.onActivityPaused(hostActivity);
            assertTrue("The audio document picker must preserve its parent settings", settings.isShowing());

            hostActivity.registry.dispatchResult(hostActivity.registry.code, Activity.RESULT_CANCELED, null);
            ShadowLooper.idleMainLooper();
            assertFalse(HostImagePicker.isPending(hostActivity));
            assertTrue(settings.isShowing());
            controller.onActivityResumed(hostActivity);
            controller.onActivityPaused(hostActivity);
            assertFalse("A subsequent ordinary pause closes settings after picker cancellation", settings.isShowing());
        } finally {
            if (picker != null) picker.close();
            controller.close();
            hostActivity.finish();
        }
    }

    private AlertDialog openAudioEditor() {
        View comment = home.findViewWithTag("settings.category.COMMENT");
        assertNotNull(comment);
        assertTrue(comment.performClick());
        Dialog category = ShadowDialog.getLatestDialog();
        View path = category.getWindow().getDecorView().findViewWithTag("settings.option.评论语音文件");
        assertNotNull(path);
        assertTrue(path.performClick());
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(editor.isShowing());
        assertEquals(ORIGINAL_PATH, pathInput(editor).getText().toString());
        return editor;
    }

    private void selectAudio(AlertDialog editor, Uri source, InputStream inputStream) {
        ShadowContentResolver resolver = Shadow.extract(activity.getApplicationContext().getContentResolver());
        resolver.registerInputStream(source, inputStream);
        launchPicker(editor);
        activity.registry.dispatchResult(activity.registry.code, Activity.RESULT_OK, new Intent().setData(source));
    }

    private void launchPicker(AlertDialog editor) {
        Button select = findSelectButton(editor.getWindow().getDecorView());
        assertNotNull("选择音频文件", select);
        assertTrue(select.performClick());
        assertFalse("Save is unavailable while selection or import is pending",
                editor.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertFalse("A second picker cannot replace an in-flight selection", select.isEnabled());
        assertNotNull("The editor must launch the real AndroidX document contract", activity.registry.intent);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, activity.registry.intent.getAction());
        assertArrayEquals(new String[]{"audio/*"},
                activity.registry.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
    }

    private Set<String> importedFiles() {
        File directory = new File(activity.getApplicationContext().getFilesDir(), "douai-voice-source");
        File[] files = directory.listFiles();
        Set<String> result = new HashSet<>();
        if (files != null) for (File file : files) result.add(file.getAbsolutePath());
        return result;
    }

    private static EditText pathInput(Dialog dialog) {
        EditText input = findPathInput(dialog.getWindow().getDecorView());
        assertNotNull("文件路径", input);
        return input;
    }

    private static EditText findPathInput(View root) {
        if (root instanceof EditText && "文件路径".equals(root.getContentDescription())) return (EditText) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText input = findPathInput(group.getChildAt(index));
                if (input != null) return input;
            }
        }
        return null;
    }

    private static Button findSelectButton(View root) {
        if (root instanceof Button && "选择音频文件".contentEquals(((Button) root).getText())) return (Button) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                Button button = findSelectButton(group.getChildAt(index));
                if (button != null) return button;
            }
        }
        return null;
    }

    private static void await(BooleanSupplier complete, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!complete.getAsBoolean() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        ShadowLooper.idleMainLooper();
        assertTrue(message, complete.getAsBoolean());
    }

    public static final class RegistryActivity extends Activity {
        final Registry registry = new Registry();
        public ActivityResultRegistry getActivityResultRegistry() { return registry; }
        @Override protected void onCreate(android.os.Bundle state) {
            super.onCreate(state);
            ScrollView scroll = new ScrollView(this);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(content);
            setContentView(scroll);
        }
    }

    private static final class Registry extends ActivityResultRegistry {
        int code;
        Intent intent;
        @Override public <I, O> void onLaunch(int code, ActivityResultContract<I, O> contract,
                I input, ActivityOptionsCompat options) {
            this.code = code;
            intent = contract.createIntent(RuntimeEnvironment.getApplication(), input);
        }
    }

    private static final class BlockingAudio extends InputStream {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final ByteArrayInputStream source = new ByteArrayInputStream(AUDIO);
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            reading.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting to finish audio import");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
            return source.read(buffer, offset, length);
        }
        @Override public int read() throws IOException {
            byte[] value = new byte[1];
            return read(value, 0, 1) < 0 ? -1 : value[0] & 0xff;
        }
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        FeatureSettings settings = FeatureSettings.defaults();
        public FeatureSettings load() { return settings; }
        public void save(FeatureSettings value) { settings = value; }
        public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }
        public String getString(String key, String fallback) {
            return values.containsKey(key) ? (String) values.get(key) : fallback;
        }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public boolean isInHost() { return true; }
    }
}
