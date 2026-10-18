package com.douai.module.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.widget.EditText;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class HostSpeechInputTest {
    @Test public void recognizesSpeechAndOnlyFillsTheLocalDraftWithTheFirstCandidate() throws Exception {
        SpeechActivity activity = Robolectric.buildActivity(SpeechActivity.class).setup().get();
        EditText draft = new EditText(activity);
        AtomicInteger selections = new AtomicInteger();
        HostSpeechInput speech = HostSpeechInput.launch(activity, text -> {
            selections.incrementAndGet();
            draft.setText(text);
        });
        try {
            assertNotNull(speech);
            assertEquals(1, activity.registry.launches);
            assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, activity.registry.intent.getAction());
            assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    activity.registry.intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
            assertEquals("语音输入", activity.registry.intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT));
            assertEquals(0, selections.get());
            assertEquals("", draft.getText().toString());
            Map<?, ?> callbacks = registeredCallbacks(activity.registry);
            assertEquals(1, callbacks.size());
            ActivityResultCallback<ActivityResult> captured = capturedCallback(callbacks);

            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK,
                    recognized("请帮我整理这段话", "请帮我整理这句话"));

            assertEquals("请帮我整理这段话", draft.getText().toString());
            assertEquals(1, selections.get());
            assertTrue("Completion must unregister the activity-result callback", callbacks.isEmpty());
            assertEquals("Recognition results must not launch another activity or native send route",
                    1, activity.registry.launches);
            captured.onActivityResult(new ActivityResult(Activity.RESULT_OK, recognized("重复返回")));
            assertEquals(1, selections.get());
            assertEquals("请帮我整理这段话", draft.getText().toString());
        } finally {
            if (speech != null) speech.close();
            activity.finish();
        }
    }

    @Test public void cancellationReturnsNullAndUnregistersEvenWithRecognitionWords() throws Exception {
        SpeechActivity activity = Robolectric.buildActivity(SpeechActivity.class).setup().get();
        AtomicReference<String> selected = new AtomicReference<>("not selected");
        AtomicInteger selections = new AtomicInteger();
        HostSpeechInput speech = HostSpeechInput.launch(activity, text -> {
            selected.set(text);
            selections.incrementAndGet();
        });
        try {
            assertNotNull(speech);
            Map<?, ?> callbacks = registeredCallbacks(activity.registry);
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_CANCELED,
                    recognized("取消后不应写入"));
            assertNull(selected.get());
            assertEquals(1, selections.get());
            assertTrue(callbacks.isEmpty());
            assertEquals(1, activity.registry.launches);
        } finally {
            if (speech != null) speech.close();
            activity.finish();
        }
    }

    @Test public void aSuccessfulResultWithoutCandidatesReturnsNull() throws Exception {
        SpeechActivity activity = Robolectric.buildActivity(SpeechActivity.class).setup().get();
        AtomicReference<String> selected = new AtomicReference<>("not selected");
        HostSpeechInput speech = HostSpeechInput.launch(activity, selected::set);
        try {
            assertNotNull(speech);
            Map<?, ?> callbacks = registeredCallbacks(activity.registry);
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK, recognized());
            assertNull(selected.get());
            assertTrue(callbacks.isEmpty());
        } finally {
            if (speech != null) speech.close();
            activity.finish();
        }
    }

    @Test public void closeUnregistersAndRejectsAnAlreadyCapturedLateResult() throws Exception {
        SpeechActivity activity = Robolectric.buildActivity(SpeechActivity.class).setup().get();
        AtomicInteger selections = new AtomicInteger();
        HostSpeechInput speech = HostSpeechInput.launch(activity, ignored -> selections.incrementAndGet());
        try {
            assertNotNull(speech);
            Map<?, ?> callbacks = registeredCallbacks(activity.registry);
            ActivityResultCallback<ActivityResult> captured = capturedCallback(callbacks);
            speech.close();
            speech.close();
            assertTrue("Closing must unregister before a recognizer returns", callbacks.isEmpty());
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK,
                    recognized("过期结果"));
            // A callback can already have been queued before unregister. Exercise the
            // proxy's own closed guard as well as ActivityResultRegistry's removal.
            captured.onActivityResult(new ActivityResult(Activity.RESULT_OK, recognized("已排队的过期结果")));
            assertEquals(0, selections.get());
            assertEquals(1, activity.registry.launches);
        } finally {
            if (speech != null) speech.close();
            activity.finish();
        }
    }

    @Test public void anUnavailableRecognizerUnregistersWithoutSelectingOrSendingAnything() throws Exception {
        SpeechActivity activity = Robolectric.buildActivity(SpeechActivity.class).setup().get();
        AtomicInteger selections = new AtomicInteger();
        activity.registry.unavailable = true;
        try {
            assertNull(HostSpeechInput.launch(activity, ignored -> selections.incrementAndGet()));
            assertTrue(registeredCallbacks(activity.registry).isEmpty());
            assertEquals(0, selections.get());
            assertEquals(1, activity.registry.launches);
        } finally { activity.finish(); }
    }

    private static Intent recognized(String... words) {
        return new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,
                new ArrayList<>(Arrays.asList(words)));
    }

    private static Map<?, ?> registeredCallbacks(ActivityResultRegistry registry) throws Exception {
        Field callbacks = ActivityResultRegistry.class.getDeclaredField("keyToCallback");
        callbacks.setAccessible(true);
        return (Map<?, ?>) callbacks.get(registry);
    }

    @SuppressWarnings("unchecked")
    private static ActivityResultCallback<ActivityResult> capturedCallback(Map<?, ?> callbacks) throws Exception {
        Object registration = callbacks.values().iterator().next();
        Field callback = registration.getClass().getDeclaredField("callback");
        callback.setAccessible(true);
        return (ActivityResultCallback<ActivityResult>) callback.get(registration);
    }

    public static final class SpeechActivity extends Activity {
        final SpeechRegistry registry = new SpeechRegistry();
        public ActivityResultRegistry getActivityResultRegistry() { return registry; }
    }

    private static final class SpeechRegistry extends ActivityResultRegistry {
        int requestCode;
        int launches;
        Intent intent;
        boolean unavailable;

        @Override public <I, O> void onLaunch(int code, ActivityResultContract<I, O> contract,
                                            I input, ActivityOptionsCompat options) {
            requestCode = code;
            launches++;
            intent = contract.createIntent(RuntimeEnvironment.getApplication(), input);
            if (unavailable) throw new ActivityNotFoundException("No speech recognizer installed");
        }
    }
}
