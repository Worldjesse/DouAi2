package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;

import com.douai.module.config.AiModelConfig;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.SettingsStore;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;

import com.ss.android.ugc.aweme.im.business.mediaselectpage.choose.model.MediaChooseResult;
import com.ss.android.ugc.aweme.im.service.model.MediaModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.ss.android.ugc.aweme",
        application = HostMediaPickerTest.HostApplication.class)
public final class HostMediaPickerTest {
    private PickerActivity activity;
    private ActivityController<PickerActivity> activityController;
    private HostMediaPicker picker;
    private final AtomicInteger callbacks = new AtomicInteger();
    private final AtomicReference<List<HostMediaPicker.Selection>> selected = new AtomicReference<>();

    @Before public void prepare() {
        HostAdaptation.setCurrentProfile(null);
        activityController = Robolectric.buildActivity(PickerActivity.class).setup();
        activity = activityController.get();
        version("40.8.0", 400801);
    }

    @After public void close() {
        if (picker != null) picker.close();
        if (activity != null) activity.finish();
        HostAdaptation.setCurrentProfile(null);
    }

    @Test public void legacyProfileSelectsItsOwnAlbumContractAndIgnoresSpoofedProcessVersion() {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_406);
        version("99.0.0", 990001);
        launch(false);
        assertEquals("X.131D", activity.registry.intent.getSerializableExtra("extra_choose_parameters").getClass().getName());
    }

    @Test public void launchesTheOfficialImageAlbumWithFreshLocalReturnParameters() throws Exception {
        launch(false);
        assertTrue(HostMediaPicker.isPending(activity));
        Intent intent = activity.registry.intent;
        assertEquals("com.ss.android.ugc.aweme.im.business.mediaselectpage.choose.MediaChooseActivity",
                intent.getComponent().getClassName());
        Object config = intent.getSerializableExtra("extra_choose_parameters");
        assertEquals("X.1UOy", config.getClass().getName());
        assertEquals(0x1, config.getClass().getField("a").getInt(config));
        assertEquals(3, config.getClass().getField("e").getInt(config));
        assertEquals(4, config.getClass().getField("f").getInt(config));
        assertTrue(config.getClass().getField("b").getBoolean(config));
        assertTrue(config.getClass().getField("h").getBoolean(config));
        assertNull("No native direct-send config may be created", config.getClass().getField("l").get(config));
        assertNull("No real or fabricated IM conversation is needed", config.getClass().getField("g").get(config));
        assertFalse(config.getClass().getField("m").getBoolean(config));
        assertEquals(1, intent.getIntExtra("picure_choose_origin", -1));
        assertEquals(0, callbacks.get());
    }

    @Test public void videoResultPrefersTheOriginalContentUriAndCopiesOnlyMetadata() throws Exception {
        launch(true);
        Object config = activity.registry.intent.getSerializableExtra("extra_choose_parameters");
        assertEquals(0x10, config.getClass().getField("a").getInt(config));
        MediaModel video = media(true, "/nonexistent/future-cache/video.mp4", "content://media/external/video/media/18");
        video.mimeType = "video/mp4";
        video.duration = 8_600;
        video.fileSize = 900_000;
        video.width = 1080;
        video.height = 1920;
        Map<?, ?> registrations = registrations();
        result(2012, video);
        assertEquals(1, callbacks.get());
        assertEquals(1, selected.get().size());
        HostMediaPicker.Selection item = selected.get().get(0);
        assertEquals(Uri.parse("content://media/external/video/media/18"), item.uri);
        assertTrue(item.video);
        assertEquals("video/mp4", item.mimeType);
        assertEquals(8_600, item.durationMillis);
        assertEquals(900_000, item.sizeBytes);
        assertEquals(1080, item.width);
        assertEquals(1920, item.height);
        assertTrue(registrations.isEmpty());
        assertFalse(HostMediaPicker.isPending(activity));
        assertEquals("Returning a selection must not launch an edit/upload/send route", 1, activity.registry.launches);
    }

    @Test public void limitsAndDeduplicatesSelectionsWithoutOpeningLocalFiles() {
        launch(false);
        MediaModel photo = media(false, "/not-readable/photo.jpg", null);
        result(2012, photo, photo,
                media(false, "https://example.invalid/photo.jpg", null),
                media(false, "relative/path.jpg", null),
                media(true, "/not-readable/video.mp4", null),
                media(false, "/not-readable/photo2.jpg", null),
                media(false, "/not-readable/photo3.jpg", null),
                media(false, "/not-readable/photo4.jpg", null),
                media(false, "/not-readable/photo5.jpg", null));
        assertEquals(4, selected.get().size());
        assertEquals(Uri.parse("file:///not-readable/photo.jpg"), selected.get().get(0).uri);
        assertEquals("photo.jpg", selected.get().get(0).name);
        assertEquals(Uri.parse("file:///not-readable/photo4.jpg"), selected.get().get(3).uri);
        assertFalse(selected.get().get(0).video);
    }

    @Test public void explicitlyEditedMediaKeepsItsEditedLocalPath() {
        launch(false);
        MediaModel edited = media(false, "/host-cache/edited.jpg", "content://media/external/images/media/22");
        edited.edited = true;
        result(2012, edited);
        assertEquals(Uri.parse("file:///host-cache/edited.jpg"), selected.get().get(0).uri);
    }

    @Test public void unmodifiedMediaKeepsItsSourceInsteadOfAHostCacheDestination() {
        launch(false);
        result(2012,
                media(false, "content://host.cache/not-created/1", "content://media/external/images/media/22"),
                media(false, "/host-cache/not-created.jpg", "/original/not-readable/photo.jpg"));
        assertEquals(Uri.parse("content://media/external/images/media/22"), selected.get().get(0).uri);
        assertEquals(Uri.parse("file:///original/not-readable/photo.jpg"), selected.get().get(1).uri);
    }

    @Test public void nativeCancellationIgnoresEvenAPresentSelectionPayload() throws Exception {
        launch(true);
        Map<?, ?> registrations = registrations();
        result(2011, media(true, "content://media/external/video/media/18", null));
        assertEquals(1, callbacks.get());
        assertNull(selected.get());
        assertTrue(registrations.isEmpty());
        assertFalse(HostMediaPicker.isPending(activity));
    }

    @Test public void standardResultOkDoesNotMasqueradeAsNativeConfirmation() {
        launch(false);
        result(Activity.RESULT_OK, media(false, "/not-readable/photo.jpg", null));
        assertEquals(1, callbacks.get());
        assertNull(selected.get());
    }

    @Test public void closeUnregistersAndRejectsAnAlreadyQueuedCallback() throws Exception {
        launch(true);
        Map<?, ?> registrations = registrations();
        Object registration = registrations.values().iterator().next();
        Field callback = registration.getClass().getDeclaredField("callback");
        callback.setAccessible(true);
        @SuppressWarnings("unchecked") ActivityResultCallback<ActivityResult> queued =
                (ActivityResultCallback<ActivityResult>) callback.get(registration);
        picker.close();
        picker.close();
        assertTrue(registrations.isEmpty());
        assertFalse(HostMediaPicker.isPending(activity));
        queued.onActivityResult(new ActivityResult(2012, resultIntent(media(true, "/not-readable/video.mp4", null))));
        assertEquals(0, callbacks.get());
    }

    @Test public void anUnavailableHostOrActivityNeverFallsBackToASystemPicker() throws Exception {
        version("40.7.0", 400701);
        assertNull(HostMediaPicker.launch(activity, false, selected::set));
        assertNull(activity.registry.intent);
        assertFalse(HostMediaPicker.isPending(activity));
        version("40.8.0", 400801);
        activity.registry.unavailable = true;
        assertNull(HostMediaPicker.launch(activity, true, selected::set));
        assertEquals(1, activity.registry.launches);
        assertNotNull(activity.registry.intent.getComponent());
        assertNull(activity.registry.intent.getAction());
        assertTrue(registrations().isEmpty());
        assertFalse(HostMediaPicker.isPending(activity));
    }

    @Test public void settingsRemainOpenWhileTheOfficialAlbumPausesTheirOwner() throws Exception {
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        Dialog settings = new Dialog(activity);
        settings.setOwnerActivity(activity);
        settings.show();
        PickerActivity chooser = null;
        try {
            controller.onActivityResumed(activity);
            // This test targets lifecycle ownership, independent of settings-entry discovery.
            Field ownerDialog = HostUiController.class.getDeclaredField("settingsDialog");
            ownerDialog.setAccessible(true);
            ownerDialog.set(controller, settings);
            assertTrue(settings.isShowing());
            launch(true);
            controller.onActivityPaused(activity);
            assertTrue("Opening the official album must preserve its settings owner", settings.isShowing());
            chooser = Robolectric.buildActivity(PickerActivity.class).setup().get();
            controller.onActivityResumed(chooser);
            controller.onActivityPaused(chooser);
            assertTrue("Pausing the chooser must not dismiss another Activity's settings", settings.isShowing());
            result(2012, media(true, "/future-cache/clip.mp4", "content://media/external/video/media/31"));
            controller.onActivityResumed(activity);
            ShadowLooper.idleMainLooper();
            assertTrue(settings.isShowing());
            controller.onActivityPaused(activity);
            assertFalse("An ordinary pause still dismisses settings", settings.isShowing());
        } finally {
            controller.close();
            settings.dismiss();
            if (chooser != null) chooser.finish();
        }
    }

    @Test public void contactEditorSurvivesTheNativeAlbumActivityPause() throws Exception {
        SettingsStore store = new HostSettingsStore(activity);
        AiModelConfig config = AiModelConfig.fromJson(new org.json.JSONObject().put("id", "album-lifecycle")
                .put("name", "模型配置").put("apiEndpoint", "https://example.com/v1")
                .put("modelName", "test-model").put("messageContactEnabled", true));
        new AiModelSettings(java.util.Collections.singletonList(config), config.id).save(store);
        java.lang.reflect.Method edit = AiConversationDialog.class.getDeclaredMethod("edit",
                Activity.class, SettingsStore.class, AiModelConfig.class);
        edit.setAccessible(true);
        edit.invoke(null, activity, store, config);
        Dialog editor = ShadowDialog.getLatestDialog();
        try {
            assertTrue(editor.isShowing());
            launch(true);
            assertTrue(HostMediaPicker.isPending(activity));
            activityController.pause();
            assertTrue("The contact editor must remain alive while the official album is open", editor.isShowing());
            result(2012, media(true, "/future-cache/clip.mp4", "content://media/external/video/media/32"));
            activityController.resume();
            ShadowLooper.idleMainLooper();
            assertTrue(editor.isShowing());
            activityController.pause();
            assertFalse(editor.isShowing());
        } finally { editor.dismiss(); }
    }

    private void launch(boolean video) {
        picker = HostMediaPicker.launch(activity, video, value -> {
            callbacks.incrementAndGet();
            selected.set(value);
        });
        assertNotNull("Host package=" + activity.getPackageName() + "; album logs="
                + ShadowLog.getLogsForTag("DouAi.HostAlbum"), picker);
    }

    private void result(int code, MediaModel... models) {
        activity.registry.dispatchResult(activity.registry.requestCode, code, resultIntent(models));
    }

    private static Intent resultIntent(MediaModel... models) {
        return new Intent().putExtra("extra_choose_result", new MediaChooseResult(new ArrayList<>(Arrays.asList(models))));
    }

    private static MediaModel media(boolean video, String file, String original) {
        MediaModel result = new MediaModel();
        result.type = video ? 1 : 0;
        result.filePath = file;
        result.relativePath = original;
        result.mimeType = video ? "video/mp4" : "image/jpeg";
        return result;
    }

    private Map<?, ?> registrations() throws Exception {
        Field callbacks = ActivityResultRegistry.class.getDeclaredField("keyToCallback");
        callbacks.setAccessible(true);
        return (Map<?, ?>) callbacks.get(activity.registry);
    }

    private void version(String name, long code) {
        PackageInfo info = new PackageInfo();
        info.packageName = activity.getPackageName();
        info.applicationInfo = activity.getApplicationInfo();
        info.versionName = name;
        info.setLongVersionCode(code);
        ((ShadowPackageManager) Shadow.extract(activity.getPackageManager())).installPackage(info);
    }

    public static final class PickerActivity extends Activity {
        final Registry registry = new Registry();
        @Override public String getPackageName() { return "com.ss.android.ugc.aweme"; }
        public ActivityResultRegistry getActivityResultRegistry() { return registry; }
    }

    public static final class HostApplication extends android.app.Application {
        @Override public String getPackageName() { return "com.ss.android.ugc.aweme"; }
    }

    private static final class Registry extends ActivityResultRegistry {
        int requestCode;
        int launches;
        Intent intent;
        boolean unavailable;

        @Override public <I, O> void onLaunch(int code, ActivityResultContract<I, O> contract,
                                            I input, ActivityOptionsCompat options) {
            requestCode = code;
            launches++;
            intent = contract.createIntent(RuntimeEnvironment.getApplication(), input);
            if (unavailable) throw new ActivityNotFoundException("Host album unavailable");
        }
    }
}
