package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiModelEditorTest {
    private Activity activity;
    private MemoryStore store;
    private AiModelEditor editor;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
    }

    @After public void closeEditor() { if (editor != null) editor.close(); }

    @Test public void invalidDraftAndFailedPersistenceStayOpenUntilSuccessfulSave() throws Exception {
        AiModelConfig original = profile();
        new AiModelSettings(Collections.singletonList(original), original.id).save(store);
        int[] refreshes = {0};
        editor = new AiModelEditor(activity, store, original, false, () -> refreshes[0]++);
        show(editor);
        AlertDialog dialog = editor.dialog();
        input(dialog, "模型名称").setText("changed-model");
        input(dialog, "超时秒数（5–120）").setText("4");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        assertEquals("saved-model", AiModelSettings.read(store).selected().modelName);
        assertEquals(0, refreshes[0]);

        input(dialog, "超时秒数（5–120）").setText("30");
        store.failWrite = true;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        assertEquals("saved-model", AiModelSettings.read(store).selected().modelName);
        assertEquals(0, refreshes[0]);
        store.failWrite = false;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(dialog.isShowing());
        assertEquals("changed-model", AiModelSettings.read(store).selected().modelName);
        assertEquals(1, refreshes[0]);
    }

    @Test public void closingEditorClosesPromptAndCancelsBlockedCatalog() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            AiModelConfig original = AiModelConfig.fromJson(profile().toJson()
                    .put("apiEndpoint", server.url("/v1").toString())
                    .put("allowInsecureHttp", true).put("timeoutSeconds", 30));
            editor = new AiModelEditor(activity, store, original, false, () -> { }, worker);
            show(editor);
            View root = editor.dialog().getWindow().getDecorView();
            assertTrue(root.findViewWithTag("ai.fetch_models").performClick());
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            root.findViewWithTag("ai.prompt.primary").performClick();
            Dialog prompt = ShadowDialog.getLatestDialog();
            assertTrue(prompt instanceof PromptEditorDialog);
            assertTrue(prompt.isShowing());
            editor.close();
            assertTrue("Editor close must release its blocked worker", worker.awaitTermination(2, TimeUnit.SECONDS));
            ShadowLooper.idleMainLooper();
            assertFalse(editor.dialog().isShowing());
            assertFalse(prompt.isShowing());
            assertSame(prompt, ShadowDialog.getLatestDialog());
        } finally { worker.shutdownNow(); }
    }

    private void show(AiModelEditor value) {
        value.show((dialog, onDismiss) -> {
            dialog.setOwnerActivity(activity);
            dialog.setOnDismissListener(ignored -> onDismiss.run());
            dialog.show();
        });
    }

    private static AiModelConfig profile() throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", "editor").put("name", "配置")
                .put("apiEndpoint", "https://example.com/v1").put("modelName", "saved-model"));
    }

    private static EditText input(Dialog dialog, String label) {
        EditText found = findInput(dialog.getWindow().getDecorView(), label);
        assertNotNull(label, found);
        return found;
    }

    private static EditText findInput(View view, String label) {
        if (view instanceof EditText && label.contentEquals(view.getContentDescription())) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText found = findInput(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        boolean failWrite;
        public FeatureSettings load() { return FeatureSettings.defaults(); }
        public void save(FeatureSettings value) { }
        public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }
        public String getString(String key, String fallback) {
            return values.containsKey(key) ? (String) values.get(key) : fallback;
        }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) {
            if (failWrite) throw new IllegalStateException("Storage unavailable");
            values.put(key, value);
        }
        public boolean isInHost() { return true; }
    }
}
