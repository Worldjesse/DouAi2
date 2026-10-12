package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.List;

import com.douai.module.config.AiModelConfig;
import com.douai.module.feature.ChatImage;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class HostImagePickerTest {
    @Test public void documentPickerRequestsOnlyImagesAndUnregistersAfterResult() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>();
        HostImagePicker picker = HostImagePicker.launch(activity, selected::set);
        try {
            assertNotNull(picker);
            assertTrue(HostImagePicker.isPending(activity));
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, activity.registry.intent.getAction());
            assertArrayEquals(new String[]{"image/*"}, activity.registry.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
            Uri source = Uri.parse("content://test/selected-image");
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK, new Intent().setData(source));
            assertEquals(source, selected.get());
            assertFalse(HostImagePicker.isPending(activity));
            picker.close();
        } finally { activity.finish(); }
    }

    @Test public void closingPickerPreventsLateSelection() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>();
        HostImagePicker picker = HostImagePicker.launch(activity, selected::set);
        try {
            assertNotNull(picker);
            picker.close();
            assertFalse(HostImagePicker.isPending(activity));
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK,
                    new Intent().setData(Uri.parse("content://test/late")));
            assertNull(selected.get());
        } finally { activity.finish(); }
    }

    @Test public void hostSettingsSurvivePickerPauseAndStillCloseOnOrdinaryPause() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        HostImagePicker picker = null;
        try {
            controller.setSettingsPageTargets(PickerActivity.class, ScrollView.class);
            controller.onActivityResumed(activity);
            ShadowLooper.idleMainLooper();
            activity.findViewById(android.R.id.content).findViewWithTag("DouAi.HostSettingsEntry").performClick();
            Dialog settings = ShadowDialog.getLatestDialog();
            assertTrue(settings.isShowing());
            picker = HostImagePicker.launch(activity, ignored -> { });
            assertNotNull(picker);
            controller.onActivityPaused(activity);
            assertTrue(settings.isShowing());
            picker.close();
            controller.onActivityResumed(activity);
            controller.onActivityPaused(activity);
            assertFalse(settings.isShowing());
        } finally {
            if (picker != null) picker.close();
            controller.close();
            activity.finish();
        }
    }

    @Test public void unavailableRegistryDoesNotLeavePendingSelection() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertNull(HostImagePicker.launch(activity, ignored -> { }));
        assertFalse(HostImagePicker.isPending(activity));
        activity.finish();
    }

    @Test public void dialogLoadsSelectedDocumentAndClearRejectsQueuedImageCallback() throws Exception {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        InlineExecutor worker = new InlineExecutor();
        AiModelConfig config = AiModelConfig.fromJson(new org.json.JSONObject().put("id", "picker-test")
                .put("name", "测试模型").put("apiEndpoint", "https://example.com/v1").put("modelName", "vision"));
        AiTestChatDialog dialog = new AiTestChatDialog(activity, config, () -> new AiTestChatDialog.Session() {
            @Override public String reply(String text, List<ChatImage> images) { return "测试回复"; }
            @Override public void cancel() { }
        }, worker);
        java.io.File file = java.io.File.createTempFile("douai-picker", ".png");
        try {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888);
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
            } finally { bitmap.recycle(); }
            dialog.show();
            android.view.View root = dialog.getWindow().getDecorView();
            root.findViewWithTag("ai.chat.attach").performClick();
            org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowAlertDialog>extract(ShadowDialog.getLatestDialog()).clickOnItem(0);
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK,
                    new Intent().setData(Uri.fromFile(file)));
            root.findViewWithTag("ai.chat.clear").performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(android.view.View.GONE, root.findViewWithTag("ai.chat.attachments").getVisibility());
            assertFalse(root.findViewWithTag("ai.chat.send").isEnabled());

            root.findViewWithTag("ai.chat.attach").performClick();
            org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowAlertDialog>extract(ShadowDialog.getLatestDialog()).clickOnItem(0);
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK,
                    new Intent().setData(Uri.fromFile(file)));
            ShadowLooper.idleMainLooper();
            assertEquals(android.view.View.VISIBLE, root.findViewWithTag("ai.chat.attachments").getVisibility());
            assertTrue(root.findViewWithTag("ai.chat.send").isEnabled());
            assertFalse(HostImagePicker.isPending(activity));
        } finally {
            dialog.dismiss();
            activity.finish();
            java.nio.file.Files.deleteIfExists(file.toPath());
        }
    }

    public static final class PickerActivity extends Activity {
        final TestRegistry registry = new TestRegistry();
        public ActivityResultRegistry getActivityResultRegistry() { return registry; }
        @Override protected void onCreate(android.os.Bundle state) {
            super.onCreate(state);
            ScrollView scroll = new ScrollView(this);
            android.widget.LinearLayout content = new android.widget.LinearLayout(this);
            content.setOrientation(android.widget.LinearLayout.VERTICAL);
            scroll.addView(content);
            setContentView(scroll);
        }
    }

    private static final class TestRegistry extends ActivityResultRegistry {
        int requestCode;
        Intent intent;
        @Override public <I, O> void onLaunch(int code, ActivityResultContract<I, O> contract,
                                               I input, ActivityOptionsCompat options) {
            requestCode = code;
            intent = contract.createIntent(RuntimeEnvironment.getApplication(), input);
        }
    }

    private static final class InlineExecutor extends AbstractExecutorService {
        boolean shutdown;
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long time, TimeUnit unit) { return shutdown; }
    }
}
