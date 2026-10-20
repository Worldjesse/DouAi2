package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.compose.ui.test.ActionsKt;
import androidx.compose.ui.test.AssertionsKt;
import androidx.compose.ui.test.FiltersKt;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.MiniMaxSpeechOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.SpeechSettings;
import com.douai.module.config.ThirdPartyRequestTemplate;
import com.douai.module.feature.SpeechHttpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.io.File;
import java.io.FileOutputStream;

import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class SpeechSettingsDialogTest {
    private Activity activity;
    private MemoryStore store;
    private SpeechSettingsDialog page;
    private View root;
    private final List<Dialog> children = new ArrayList<>();

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
    }

    @After public void tearDown() {
        if (page != null) page.close();
        for (Dialog dialog : children) dialog.dismiss();
        if (page != null) page.dialog().dismiss();
        ShadowLooper.idleMainLooper();
        activity.finish();
    }

    @Test public void providerChoiceHidesOtherControlsAndPreservesBothConfigurations() {
        store.putString("tts_engine", "installed.engine");
        store.putString("tts_language", "en-US");
        store.putString("tts_rate", "1.4");
        store.putString("tts_pitch", "0.8");
        store.putString(SpeechSettings.GET_TEMPLATE, "https://example.com/speak?text={text}");
        open(false);
        assertNotNull(root.findViewWithTag("settings.option.语音引擎"));
        assertNull(root.findViewWithTag("settings.option.合成 GET"));
        chooseProvider(1);
        assertNull(root.findViewWithTag("settings.option.语音引擎"));
        assertNotNull(root.findViewWithTag("settings.option.合成 GET"));
        assertNotNull(root.findViewWithTag("settings.option.音色列表 GET"));
        assertNotNull(root.findViewWithTag("settings.option.音色高级设置"));
        assertCompactThirdPartyPage();
        chooseProvider(0);
        assertNull(root.findViewWithTag("settings.option.音色高级设置"));
        assertEquals("installed.engine", value("语音引擎"));
        assertEquals("en-US", value("语音语言"));
        assertEquals("1.4", value("语速"));
        assertEquals("0.8", value("音调"));
        assertEquals("https://example.com/speak?text={text}", store.getString(SpeechSettings.GET_TEMPLATE, ""));
    }

    @Test public void miniMaxControlsPreserveSystemAndThirdPartySettingsAndHideTheApiKey() {
        store.putString("tts_rate", "1.4");
        store.putString("tts_pitch", "0.8");
        store.putString(SpeechSettings.SELECTED_VOICE, "third-voice");
        store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-private-key");
        store.putString(SpeechSettings.MINIMAX_VOICE_ID, "minimax-voice");
        open(false);
        chooseProvider(2);
        assertEquals("MiniMax", value("语音来源"));
        assertEquals("已设置", value("API Key"));
        assertEquals(SpeechSettings.DEFAULT_MINIMAX_BASE_URL, value("API 地址"));
        assertEquals("1", value("语速"));
        assertEquals("1", value("音量"));
        assertEquals("0", value("音调"));
        assertEquals("minimax-voice", value("音色"));
        assertNull(root.findViewWithTag("settings.option.合成 GET"));
        assertNull(root.findViewWithTag("settings.option.语音引擎"));
        assertNull(root.findViewWithTag("settings.option.音色高级设置"));
        AlertDialog editor = option("API Key");
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                input(editor, "API Key").getInputType() & android.text.InputType.TYPE_MASK_VARIATION);
        editor.dismiss();
        editor = option("音量");
        input(editor, "音量").setText("10");
        save(editor);
        assertEquals("10", store.getString(SpeechSettings.MINIMAX_VOLUME, ""));
        chooseProvider(1);
        assertEquals("third-voice", value("音色"));
        chooseProvider(0);
        assertEquals("1.4", value("语速"));
        assertEquals("0.8", value("音调"));
        assertEquals("minimax-voice", store.getString(SpeechSettings.MINIMAX_VOICE_ID, ""));
    }

    @Test public void miniMaxRefreshActionsBelongToTheirCachedListsAndOpeningDoesNotRequestOrSynthesize() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-key");
            store.putString(SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString());
            store.putString(SpeechSettings.MINIMAX_MODEL_SAMPLE, "[\"speech-2.8-hd\"]");
            store.putString(SpeechSettings.MINIMAX_MODEL_SOURCE, "api");
            store.putString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "[{\"id\":\"saved-voice\",\"name\":\"缓存音色\"}]");
            open(false);
            assertNull("Model refresh must stay inside its list", root.findViewWithTag("settings.option.刷新模型"));
            assertNull("Official refresh must stay inside its model list", root.findViewWithTag("settings.option.刷新官方模型列表"));
            assertNull("Voice refresh must stay inside its list", root.findViewWithTag("settings.option.刷新音色"));
            AlertDialog models = option("模型");
            assertEquals("speech-2.8-hd", models.getListView().getAdapter().getItem(0));
            assertEquals("刷新", models.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("手动填写", models.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            models.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            AlertDialog sources = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(2, sources.getListView().getAdapter().getCount());
            assertTrue(sources.getListView().getAdapter().getItem(0).toString().contains("当前接口"));
            assertTrue(sources.getListView().getAdapter().getItem(1).toString().contains("官方目录"));
            sources.dismiss();
            AlertDialog voices = option("音色");
            assertEquals("缓存音色 · saved-voice", voices.getListView().getAdapter().getItem(0));
            assertEquals("刷新", voices.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("手动填写", voices.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            assertEquals("Opening cached lists must not request models, voices or paid synthesis", 0, server.getRequestCount());
        }
    }

    @Test public void miniMaxEditsValidateRangesAndManualVoiceDoesNotOverwriteThirdPartySelection() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        store.putString(SpeechSettings.SELECTED_VOICE, "third-voice");
        store.putString(SpeechSettings.MINIMAX_VOICE_ID, "saved-voice");
        open(false);
        String[][] invalids = {{"语速", "0.49"}, {"音量", "0"}, {"音调", "0.5"}, {"API 地址", "invalid-url"}};
        for (String[] invalid : invalids) {
            AlertDialog editor = option(invalid[0]);
            input(editor, invalid[0]).setText(invalid[1]);
            save(editor);
            assertTrue(editor.isShowing());
            assertNotNull(input(editor, invalid[0]).getError());
            editor.dismiss();
        }
        AlertDialog voice = option("音色");
        voice.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog manual = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("saved-voice", input(manual, "音色编号").getText().toString());
        input(manual, "音色编号").setText("new-minimax-voice");
        save(manual);
        assertEquals("new-minimax-voice", store.getString(SpeechSettings.MINIMAX_VOICE_ID, ""));
        assertEquals("third-voice", store.getString(SpeechSettings.SELECTED_VOICE, ""));
    }

    @Test public void miniMaxModelsOfferTheBuiltInCatalogAndManualIdsWithoutRelabelingThemAsFetched() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        store.putString(SpeechSettings.MINIMAX_MODEL, "manual-model");
        open(false);
        AlertDialog choice = option("模型");
        assertEquals(9, choice.getListView().getAdapter().getCount());
        assertTrue(choice.getListView().getAdapter().getItem(0).toString().contains("当前模型，未在列表中"));
        choice.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        input(editor, "模型").setText("");
        save(editor);
        assertTrue(editor.isShowing());
        assertNotNull(input(editor, "模型").getError());
        input(editor, "模型").setText("speech-future-hd");
        save(editor);
        assertEquals("speech-future-hd", value("模型"));
        TextView hint = root.findViewWithTag("speech.model.capability");
        assertNotNull(hint);
        assertTrue(hint.getText().toString().contains("能力未验证"));
    }

    @Test public void miniMaxModelRefreshUsesTheApiAndPreservesItsCacheWhenTheApiHasOnlyTextModels() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":\"MiniMax-M2.5\"},{\"id\":\"speech-future-hd\"}]}"));
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":\"MiniMax-M2.5\"}]}"));
            server.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-key");
            store.putString(SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString());
            open(false);
            refreshMiniMaxList("模型");
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("GET", request.getMethod());
            assertEquals("/v1/models", request.getPath());
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"));
            await(() -> "api".equals(store.getString(SpeechSettings.MINIMAX_MODEL_SOURCE, "")));
            String cached = store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, "");
            assertTrue(cached.contains("speech-future-hd"));
            assertFalse(cached.contains("MiniMax-M2.5"));
            AlertDialog choice = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(2, choice.getListView().getAdapter().getCount());
            choice.getListView().performItemClick(null, 1, 1);
            assertEquals("speech-future-hd", value("模型"));
            refreshMiniMaxList("模型");
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            await(() -> org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString().contains("未返回语音模型"));
            assertEquals(cached, store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, ""));
            assertEquals("api", store.getString(SpeechSettings.MINIMAX_MODEL_SOURCE, ""));
        }
    }

    @Test public void changingMiniMaxEndpointCancelsTheModelFetchAndReleasesTheSharedVoiceRequestSlot() throws Exception {
        try (MockWebServer old = new MockWebServer(); MockWebServer current = new MockWebServer()) {
            old.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            current.enqueue(new MockResponse().setBody("{\"system_voice\":[{\"voice_id\":\"current\"}],\"base_resp\":{\"status_code\":0}}"));
            old.start(); current.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-key");
            store.putString(SpeechSettings.MINIMAX_BASE_URL, old.url("/").toString());
            open(false);
            refreshMiniMaxList("模型");
            assertNotNull(old.takeRequest(2, TimeUnit.SECONDS));
            refreshMiniMaxList("音色");
            assertEquals(1, old.getRequestCount());
            AlertDialog editor = option("API 地址");
            input(editor, "API 地址").setText(current.url("/").toString());
            save(editor);
            refreshMiniMaxList("音色");
            assertNotNull(current.takeRequest(2, TimeUnit.SECONDS));
            await(() -> store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "").contains("current"));
            assertEquals("", store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, ""));
        }
    }

    @Test public void miniMaxAdvancedSettingsValidateAndPersistWithoutChangingLocalControls() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        store.putString(SpeechSettings.RATE, "1.8");
        store.putString(SpeechSettings.PITCH, "1.4");
        open(false);
        AlertDialog advanced = option("MiniMax 高级设置");
        View form = advanced.getWindow().getDecorView();
        pick(form, "情绪", 1);
        assertEquals("happy", store.getString(MiniMaxSpeechOptions.EMOTION, ""));
        pick(form, "音频格式", 1);
        assertEquals("wav", store.getString(MiniMaxSpeechOptions.AUDIO_FORMAT, ""));
        assertNull(form.findViewWithTag("settings.option.MP3 码率"));
        pick(form, "采样率", 5);
        pick(form, "声道", 1);
        assertEquals("44100", store.getString(MiniMaxSpeechOptions.SAMPLE_RATE, ""));
        assertEquals("2", store.getString(MiniMaxSpeechOptions.CHANNEL, ""));
        AlertDialog editor = option(form, "音高调整（-100 至 100）");
        input(editor, "音高调整（-100 至 100）").setText("101");
        save(editor);
        assertTrue(editor.isShowing());
        input(editor, "音高调整（-100 至 100）").setText("-30");
        save(editor);
        assertEquals("-30", store.getString(MiniMaxSpeechOptions.MODIFY_PITCH, ""));
        editor = option(form, "发音字典（每行 原文/替换内容）");
        input(editor, "发音字典（每行 原文/替换内容）").setText("invalid");
        save(editor);
        assertTrue(editor.isShowing());
        input(editor, "发音字典（每行 原文/替换内容）").setText("处理/(chu3)(li3)\n世界/world");
        save(editor);
        assertEquals("处理/(chu3)(li3)\n世界/world", store.getString(MiniMaxSpeechOptions.PRONUNCIATION_TONE, ""));
        assertEquals("1.8", store.getString(SpeechSettings.RATE, ""));
        assertEquals("1.4", store.getString(SpeechSettings.PITCH, ""));
    }

    @Test public void miniMaxAdvancedAndModelEditsRejectIncompatibleMergedSettingsBeforeSaving() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        open(false);
        AlertDialog advanced = option("MiniMax 高级设置");
        View form = advanced.getWindow().getDecorView();
        AlertDialog emotion = option(form, "情绪");
        assertTrue(emotion.getListView().getAdapter().getItem(9).toString().contains("不适用"));
        assertTrue(emotion.getListView().getAdapter().getItem(9).toString().contains("speech-2.6"));
        emotion.getListView().performItemClick(null, 9, 9);
        assertTrue(emotion.isShowing());
        assertEquals("", store.getString(MiniMaxSpeechOptions.EMOTION, ""));
        emotion.dismiss();
        pick(form, "语言增强", java.util.Arrays.asList(MiniMaxSpeechOptions.LANGUAGES).indexOf("English"));
        AlertDialog latex = option(form, "LaTeX 朗读");
        assertTrue(latex.getListView().getAdapter().getItem(1).toString().contains("不适用"));
        latex.getListView().performItemClick(null, 1, 1);
        assertTrue(latex.isShowing());
        assertEquals("", store.getString(MiniMaxSpeechOptions.LATEX_READ, ""));
        latex.dismiss();
        pick(form, "语言增强", 1);
        pick(form, "LaTeX 朗读", 1);
        assertEquals("true", store.getString(MiniMaxSpeechOptions.LATEX_READ, ""));
        pick(form, "文本归一化", 2);
        advanced.dismiss();
        AlertDialog models = option("模型");
        boolean offeredConflict = false;
        for (int index = 0; index < models.getListView().getAdapter().getCount(); index++) {
            String label = models.getListView().getAdapter().getItem(index).toString();
            if (label.startsWith("speech-01-hd")) offeredConflict = label.contains("不适用");
        }
        assertTrue(offeredConflict);
        models.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog manual = (AlertDialog) ShadowDialog.getLatestDialog();
        input(manual, "模型").setText("speech-01-hd");
        save(manual);
        assertTrue(manual.isShowing());
        assertNotNull(input(manual, "模型").getError());
        assertEquals(SpeechSettings.DEFAULT_MINIMAX_MODEL, store.getString(SpeechSettings.MINIMAX_MODEL, SpeechSettings.DEFAULT_MINIMAX_MODEL));
    }

    private void pick(View form, String title, int index) {
        AlertDialog choice = option(form, title);
        choice.getListView().performItemClick(null, index, index);
        assertFalse(title, choice.isShowing());
    }

    private void refreshMiniMaxList(String title) {
        AlertDialog choice = option(title);
        choice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        if ("模型".equals(title)) {
            AlertDialog source = (AlertDialog) ShadowDialog.getLatestDialog();
            source.getListView().performItemClick(null, 0, 0);
            ShadowLooper.idleMainLooper();
        }
    }

    @Test public void miniMaxRefreshShowsSafeHttpAndProviderReasonsAndRetainsExistingCatalogs() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-private-key");
            store.putString(SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString());
            String models = "[\"speech-2.8-hd\"]", voices = "[{\"id\":\"saved-voice\",\"name\":\"saved\"}]";
            store.putString(SpeechSettings.MINIMAX_MODEL_SAMPLE, models);
            store.putString(SpeechSettings.MINIMAX_VOICE_SAMPLE, voices);
            open(false);
            for (String action : new String[]{"刷新模型", "刷新音色"}) {
                for (int code : new int[]{401, 429, 1008, 2042, -1}) {
                    String expected;
                    MockResponse response = new MockResponse().addHeader("Content-Type", "application/json");
                    if (code == 401 || code == 429) {
                        response.setResponseCode(code).setBody("private-token https://secret.example");
                        expected = "HTTP " + code;
                    } else if (code > 0) {
                        response.setBody("{\"base_resp\":{\"status_code\":" + code
                                + ",\"status_msg\":\"fixture-private-key https://secret.example\"}}");
                        expected = code == 1008 ? "账户余额不足" : "当前账户无所选音色权限";
                    } else {
                        response.setBody("private-token https://secret.example");
                        expected = "不是有效的 JSON";
                    }
                    server.enqueue(response);
                    refreshMiniMaxList("刷新模型".equals(action) ? "模型" : "音色");
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
                    await(() -> String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()).contains(expected));
                    String message = String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
                    assertTrue(message.contains("已保留"));
                    assertFalse(message.contains("fixture-private-key"));
                    assertFalse(message.contains("private-token"));
                    assertFalse(message.contains("secret.example"));
                    assertEquals(models, store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, ""));
                    assertEquals(voices, store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, ""));
                }
            }
        }
    }

    @Test public void miniMaxRefreshCachesItsOwnCatalogAndSelectsTheProviderVoiceId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody(
                    "{\"system_voice\":[{\"voice_id\":\"male-qn-qingse\",\"voice_name\":\"青涩青年\"}],"
                    + "\"voice_cloning\":[{\"voice_id\":\"cloned\"}],\"voice_generation\":[],\"base_resp\":{\"status_code\":0}}"));
            server.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString());
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-key");
            store.putString("tts_voice_sample", "7. Third Voice");
            store.putString(SpeechSettings.SELECTED_VOICE, "7");
            open(false);
            refreshMiniMaxList("音色");
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertEquals("/v1/get_voice", request.getPath());
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"));
            await(() -> !store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "").isEmpty());
            AlertDialog choice = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(2, choice.getListView().getAdapter().getCount());
            choice.getListView().performItemClick(null, 1, 1);
            assertEquals("cloned", store.getString(SpeechSettings.MINIMAX_VOICE_ID, ""));
            assertEquals("7. Third Voice", store.getString("tts_voice_sample", ""));
            assertEquals("7", store.getString(SpeechSettings.SELECTED_VOICE, ""));
            AlertDialog apiKey = option("API Key");
            input(apiKey, "API Key").setText("new-key");
            save(apiKey);
            assertEquals("", store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, ""));
            assertEquals("7. Third Voice", store.getString("tts_voice_sample", ""));
        }
    }

    @Test public void changingMiniMaxEndpointCancelsTheOldVoiceFetch() throws Exception {
        try (MockWebServer old = new MockWebServer(); MockWebServer current = new MockWebServer()) {
            old.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            current.enqueue(new MockResponse().setBody("{\"system_voice\":[{\"voice_id\":\"current\",\"voice_name\":\"当前音色\"}],\"base_resp\":{\"status_code\":0}}"));
            old.start(); current.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-key");
            store.putString(SpeechSettings.MINIMAX_BASE_URL, old.url("/").toString());
            open(false);
            refreshMiniMaxList("音色");
            assertNotNull(old.takeRequest(2, TimeUnit.SECONDS));
            AlertDialog editor = option("API 地址");
            input(editor, "API 地址").setText(current.url("/").toString());
            save(editor);
            refreshMiniMaxList("音色");
            assertNotNull(current.takeRequest(2, TimeUnit.SECONDS));
            await(() -> store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "").contains("current"));
            assertEquals("当前音色 · current", ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().getAdapter().getItem(0));
        }
    }

    @Test public void savingGetUpdatesParameterNamesAndRejectsAnInvalidAddress() {
        open(true);
        AlertDialog editor = option("合成 GET");
        EditText input = input(editor, "合成 GET");
        input.setText("invalid-url");
        save(editor);
        assertTrue(editor.isShowing());
        assertNotNull(input.getError());
        input.setText("https://example.com/tts?ID=key&body={text}&speaker={voice}");
        save(editor);
        assertFalse(editor.isShowing());
        assertEquals("ID、body、speaker", value("合成参数"));
        AlertDialog parameters = option("合成参数");
        View form = parameters.getWindow().getDecorView();
        assertEquals("ID", ((EditText) form.findViewWithTag("speech.parameter.name.0")).getText().toString());
        assertEquals(View.GONE, form.findViewWithTag("speech.parameter.fixed.1").getVisibility());
        assertEquals(View.GONE, form.findViewWithTag("speech.parameter.fixed.2").getVisibility());
    }

    @Test public void localRateAndPitchRejectInvalidEditsWithoutChangingSavedValues() {
        store.putString("tts_rate", "1.4");
        store.putString("tts_pitch", "0.8");
        open(false);
        String[][] settings = {{"语速", "tts_rate", "1.4"}, {"音调", "tts_pitch", "0.8"}};
        for (String[] setting : settings) {
            AlertDialog editor = option(setting[0]);
            EditText input = input(editor, setting[0]);
            for (String invalid : new String[]{"0.49", "2.01", "0", "-1", "invalid", "NaN", "Infinity", "", "1e100"}) {
                input.setText(invalid);
                save(editor);
                assertTrue(setting[0] + " must reject " + invalid, editor.isShowing());
                assertNotNull(setting[0] + " must explain the valid range", input.getError());
                assertEquals(setting[2], store.getString(setting[1], ""));
            }
            editor.dismiss();
        }
    }

    @Test public void localRateAndPitchAcceptBothRangeEndpoints() {
        open(false);
        String[][] settings = {{"语速", "tts_rate"}, {"音调", "tts_pitch"}};
        for (String[] setting : settings) {
            for (String valid : new String[]{"0.5", "2"}) {
                AlertDialog editor = option(setting[0]);
                input(editor, setting[0]).setText(valid);
                save(editor);
                assertFalse(editor.isShowing());
                assertEquals(valid, store.getString(setting[1], ""));
                assertEquals(valid, value(setting[0]));
            }
        }
    }

    @Test public void legacyLocalValuesDisplayEffectiveValuesWithoutRewritingStoredPreferences() {
        store.putString("tts_rate", "3");
        store.putString("tts_pitch", "0.1");
        open(false);
        assertEquals("2", value("语速"));
        assertEquals("0.5", value("音调"));
        AlertDialog rate = option("语速");
        assertEquals("2", input(rate, "语速").getText().toString());
        rate.dismiss();
        assertEquals("3", store.getString("tts_rate", ""));
        assertEquals("0.1", store.getString("tts_pitch", ""));

        for (String invalid : new String[]{"invalid", "NaN", "Infinity", ""}) {
            chooseProvider(1);
            store.putString("tts_rate", invalid);
            store.putString("tts_pitch", invalid);
            chooseProvider(0);
            assertEquals("1", value("语速"));
            assertEquals("1", value("音调"));
            assertEquals(invalid, store.getString("tts_rate", ""));
            assertEquals(invalid, store.getString("tts_pitch", ""));
        }
    }

    @Test public void parametersHaveStableTagsAndSupportArbitraryNamesAndTextVoiceBindings() {
        store.putString(SpeechSettings.GET_TEMPLATE, "https://example.com/speak?ID=old&body={text}&speaker={voice}");
        open(true);
        AlertDialog editor = option("合成参数");
        View form = editor.getWindow().getDecorView();
        form.findViewWithTag("speech.parameter.remove.0").performClick();
        form.findViewWithTag("speech.parameter.add").performClick();
        assertNull(form.findViewWithTag("speech.parameter.name.0"));
        ((EditText) form.findViewWithTag("speech.parameter.name.3")).setText("token");
        ((EditText) form.findViewWithTag("speech.parameter.value.3")).setText("key + 中文&value");
        Spinner source = form.findViewWithTag("speech.parameter.source.1");
        source.setSelection(0);
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, form.findViewWithTag("speech.parameter.fixed.1").getVisibility());
        ((EditText) form.findViewWithTag("speech.parameter.value.1")).setText("retained fixed value");
        source.setSelection(1);
        ShadowLooper.idleMainLooper();
        assertEquals(View.GONE, form.findViewWithTag("speech.parameter.fixed.1").getVisibility());
        save(editor);
        ThirdPartyRequestTemplate template = ThirdPartyRequestTemplate.parse(store.getString(SpeechSettings.GET_TEMPLATE, ""), "{text}", "{voice}");
        assertEquals(3, template.parameters.size());
        assertEquals("{text}", template.parameters.get(0).value);
        assertEquals("{voice}", template.parameters.get(1).value);
        assertEquals("token", template.parameters.get(2).name);
        assertEquals("key + 中文&value", template.parameters.get(2).value);
        String resolved = SpeechHttpClient.resolveTemplate(store.getString(SpeechSettings.GET_TEMPLATE, ""), "你好 & +", "223");
        assertTrue(resolved, resolved.contains("body=%E4%BD%A0%E5%A5%BD%20%26%20%2B"));
        assertTrue(resolved, resolved.contains("speaker=223"));
    }

    @Test public void voiceListGetIsEditedAsOneAddressWithoutAParameterEditor() {
        store.putString(SpeechSettings.VOICE_GET_TEMPLATE, "https://example.com/voices");
        open(true);
        AlertDialog editor = option("音色列表 GET");
        assertEquals("https://example.com/voices", input(editor, "音色列表 GET").getText().toString());
        input(editor, "音色列表 GET").setText("https://example.com/catalog.txt");
        save(editor);
        assertEquals("https://example.com/catalog.txt", value("音色列表 GET"));
        assertCompactThirdPartyPage();
    }

    @Test public void headersValidateStringValuesAndSaveUserDefinedNames() {
        open(true);
        AlertDialog editor = option("合成请求头");
        EditText input = input(editor, "合成请求头");
        input.setText("{\"X-Api-Key\":42}");
        save(editor);
        assertTrue(editor.isShowing());
        assertNotNull(input.getError());
        input.setText("{\"X-Api-Key\":\"user-key\"}");
        save(editor);
        assertEquals("{\"X-Api-Key\":\"user-key\"}", store.getString(SpeechSettings.HEADERS, ""));
        assertEquals("{\"X-Api-Key\":\"user-key\"}", value("合成请求头"));
        assertCompactThirdPartyPage();
    }

    @Test public void advancedPageKeepsSavedFieldsAndSamplesAndRefreshesAfterEdits() {
        store.putString(SpeechSettings.HEADERS, "{\"X-Synthesis\":\"saved\"}");
        store.putString(SpeechSettings.AUDIO_FIELD, "data.audio");
        store.putString(SpeechSettings.VOICE_HEADERS, "{\"X-Catalog\":\"saved\"}");
        store.putString(SpeechSettings.VOICE_LIST_PATH, "data.voices");
        store.putString(SpeechSettings.VOICE_ID_PATH, "id");
        store.putString(SpeechSettings.VOICE_NAME_PATH, "name");
        String sample = "{\"data\":{\"voices\":[{\"id\":\"7\",\"name\":\"saved voice\"}]}}";
        store.putString("tts_voice_sample", sample);
        open(true);
        assertCompactThirdPartyPage();
        AlertDialog advanced = option("音色高级设置");
        View form = advanced.getWindow().getDecorView();
        assertEquals("{\"X-Synthesis\":\"saved\"}", value("合成请求头"));
        assertEquals("data.audio", value("音频地址字段"));
        assertNull(form.findViewWithTag("settings.option.合成请求头"));
        assertNull(form.findViewWithTag("settings.option.音频地址字段"));
        assertEquals("{\"X-Catalog\":\"saved\"}", value(form, "音色列表请求头"));
        assertEquals("data.voices", value(form, "列表字段"));
        assertEquals("id", value(form, "音色编号字段"));
        assertEquals("name", value(form, "音色名称字段"));
        assertEquals("已保存", value(form, "音色列表样本"));
        AlertDialog editor = option(form, "列表字段");
        input(editor, "列表字段").setText("result.voices");
        save(editor);
        assertTrue(advanced.isShowing());
        assertEquals("result.voices", value(form, "列表字段"));
        assertEquals(sample, store.getString("tts_voice_sample", ""));
        assertEquals("result.voices", store.getString(SpeechSettings.VOICE_LIST_PATH, ""));
        assertEquals("{\"X-Catalog\":\"saved\"}", store.getString(SpeechSettings.VOICE_HEADERS, ""));
        assertCompactThirdPartyPage();
        advanced.dismiss();
        chooseProvider(0);
        chooseProvider(1);
        advanced = option("音色高级设置");
        assertEquals("result.voices", value(advanced.getWindow().getDecorView(), "列表字段"));
        assertEquals("data.audio", value("音频地址字段"));
    }

    @Test public void synthesisSamplePreviewsAndSelectsAnAudioFieldLikeTheDownloadEditor() {
        String sample = "{\"code\":200,\"data\":{\"audio\":\"https://example.com/voice.mp3\","
                + "\"cover\":\"https://example.com/cover.jpg\"}}";
        open(true);
        AlertDialog editor = option("合成响应样本");
        EditText input = editor.getWindow().getDecorView().findViewWithTag("speech.synthesis.sample");
        input.setText(sample);
        editor.getWindow().getDecorView().findViewWithTag("speech.synthesis.parse").performClick();
        TextView preview = editor.getWindow().getDecorView().findViewWithTag("speech.synthesis.preview");
        assertEquals("data.audio\ndata.cover", preview.getText().toString());
        save(editor);
        assertEquals("2 个链接字段", value("合成响应样本"));
        AlertDialog fields = option("音频地址字段");
        assertEquals("data.audio", fields.getListView().getAdapter().getItem(1));
        fields.getListView().performItemClick(null, 1, 1);
        assertEquals("data.audio", store.getString(SpeechSettings.AUDIO_FIELD, ""));
        assertEquals("https://example.com/voice.mp3", SpeechHttpClient.audioUrl(sample, settingsAudioField()));
        assertEquals(sample, store.getString("tts_synthesis_sample", ""));
        assertEquals("", store.getString("tts_voice_sample", ""));
    }

    @Test public void synthesisArrayFieldsKeepTheirIndexAndManualFieldsRemainEditable() {
        String sample = "{\"data\":{\"audio\":[\"https://example.com/one.mp3\",\"https://example.com/two.mp3\"]}}";
        store.putString("tts_synthesis_sample", sample);
        open(true);
        AlertDialog fields = option("音频地址字段");
        assertEquals("data.audio[1]", fields.getListView().getAdapter().getItem(2));
        fields.getListView().performItemClick(null, 2, 2);
        assertEquals("https://example.com/two.mp3", SpeechHttpClient.audioUrl(sample, settingsAudioField()));
        fields = option("音频地址字段");
        fields.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog manual = (AlertDialog) ShadowDialog.getLatestDialog();
        input(manual, "音频地址字段").setText("custom.output");
        save(manual);
        assertEquals("custom.output", value("音频地址字段"));
    }

    @Test public void synthesisTxtSampleAndInvalidOrCanceledEditsDoNotAffectVoiceSettings() {
        String link = "https://example.com/audio.mp3";
        store.putString("tts_voice_sample", "1. 叶修");
        store.putString(SpeechSettings.SELECTED_VOICE, "1");
        open(true);
        AlertDialog editor = option("合成响应样本");
        EditText input = editor.getWindow().getDecorView().findViewWithTag("speech.synthesis.sample");
        input.setText("{invalid}"); save(editor);
        assertTrue(editor.isShowing()); assertNotNull(input.getError());
        input.setText(link); save(editor);
        assertEquals("1 个链接字段", value("合成响应样本"));
        AlertDialog fields = option("音频地址字段");
        fields.getListView().performItemClick(null, 0, 0);
        assertEquals("自动识别", value("音频地址字段"));
        assertEquals(link, SpeechHttpClient.audioUrl(link, settingsAudioField()));
        editor = option("合成响应样本");
        ((EditText) editor.getWindow().getDecorView().findViewWithTag("speech.synthesis.sample")).setText("");
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals(link, store.getString("tts_synthesis_sample", ""));
        assertEquals("1. 叶修", store.getString("tts_voice_sample", ""));
        assertEquals("1", store.getString(SpeechSettings.SELECTED_VOICE, ""));
    }

    private String settingsAudioField() { return store.getString(SpeechSettings.AUDIO_FIELD, ""); }

    @Test public void cancelingEditorsLeavesExistingConfigurationAndVoiceUntouched() {
        store.putString(SpeechSettings.VOICE_HEADERS, "{\"X-Catalog\":\"saved\"}");
        store.putString(SpeechSettings.SELECTED_VOICE, "7");
        store.putString(SpeechSettings.SELECTED_VOICE_NAME, "saved voice");
        store.putString("tts_voice_sample", "7. saved voice\n8. other voice");
        open(true);
        AlertDialog advanced = option("音色高级设置");
        AlertDialog editor = option(advanced.getWindow().getDecorView(), "音色列表请求头");
        input(editor, "音色列表请求头").setText("{}");
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals("{\"X-Catalog\":\"saved\"}", store.getString(SpeechSettings.VOICE_HEADERS, ""));
        advanced.dismiss();
        AlertDialog choice = option("音色");
        choice.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals("saved voice · 7", value("音色"));
    }

    @Test public void numberedTxtSampleUsesTheExplicitVoiceIdInTheSynthesisRequest() {
        store.putString(SpeechSettings.GET_TEMPLATE, "https://example.com/tts?content={text}&ID={voice}");
        open(true);
        AlertDialog advanced = option("音色高级设置");
        AlertDialog sample = option(advanced.getWindow().getDecorView(), "音色列表样本");
        input(sample, "音色列表样本").setText("1. 叶修\n223. 瓦瓦");
        save(sample);
        assertEquals("已保存", value(advanced.getWindow().getDecorView(), "音色列表样本"));
        advanced.dismiss();
        AlertDialog choice = option("音色");
        assertEquals(2, choice.getListView().getAdapter().getCount());
        assertEquals("瓦瓦 · 223", choice.getListView().getAdapter().getItem(1));
        choice.getListView().performItemClick(null, 1, 1);
        assertEquals("223", store.getString(SpeechSettings.SELECTED_VOICE, ""));
        assertEquals("瓦瓦", store.getString(SpeechSettings.SELECTED_VOICE_NAME, ""));
        assertEquals("瓦瓦 · 223", value("音色"));
        assertTrue(SpeechHttpClient.resolveTemplate(store.getString(SpeechSettings.GET_TEMPLATE, ""), "hello", "223").endsWith("&ID=223"));
    }

    @Test public void refreshUsesIndependentGetAndHeadersAndDisplaysAllNumberedVoices() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            StringBuilder body = new StringBuilder();
            for (int id = 1; id <= 223; id++) body.append(id).append(". 音色").append(id).append('\n');
            server.enqueue(new MockResponse().addHeader("Content-Type", "text/plain; charset=utf-8").setBody(body.toString()));
            server.start();
            store.putString(SpeechSettings.VOICE_GET_TEMPLATE, server.url("/voices").toString());
            store.putString(SpeechSettings.VOICE_HEADERS, "{\"X-Catalog\":\"catalog-key\"}");
            open(true);
            root.findViewWithTag("settings.option.刷新音色").performClick();
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("GET", request.getMethod());
            assertEquals("/voices", request.getPath());
            assertNull(request.getRequestUrl().query());
            assertEquals("catalog-key", request.getHeader("X-Catalog"));
            await(() -> body.toString().equals(store.getString("tts_voice_sample", "")));
            AlertDialog choice = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(223, choice.getListView().getAdapter().getCount());
            choice.getListView().performItemClick(null, 222, 222);
            assertEquals("223", store.getString(SpeechSettings.SELECTED_VOICE, ""));
        }
    }

    @Test public void changingCatalogSettingsCancelsOldFetchAndAllowsTheNewCatalog() throws Exception {
        try (MockWebServer old = new MockWebServer(); MockWebServer current = new MockWebServer()) {
            old.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            current.enqueue(new MockResponse().setBody("7. 当前音色"));
            old.start();
            current.start();
            store.putString(SpeechSettings.VOICE_GET_TEMPLATE, old.url("/old").toString());
            open(true);
            root.findViewWithTag("settings.option.刷新音色").performClick();
            assertNotNull(old.takeRequest(2, TimeUnit.SECONDS));
            AlertDialog editor = option("音色列表 GET");
            input(editor, "音色列表 GET").setText(current.url("/new").toString());
            save(editor);
            root.findViewWithTag("settings.option.刷新音色").performClick();
            assertNotNull(current.takeRequest(2, TimeUnit.SECONDS));
            await(() -> "7. 当前音色".equals(store.getString("tts_voice_sample", "")));
            assertEquals("当前音色 · 7", ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().getAdapter().getItem(0));
        }
    }

    @Test public void closingPreviewIgnoresTheCanceledSynthesisCallback() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            store.putString(SpeechSettings.GET_TEMPLATE, server.url("/tts").toString());
            open(true);
            AlertDialog preview = option("试听");
            View form = preview.getWindow().getDecorView();
            form.findViewWithTag("speech.preview.start").performClick();
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            TextView status = form.findViewWithTag("speech.preview.status");
            assertEquals("正在生成语音", status.getText().toString());
            preview.dismiss();
            ShadowLooper.idleMainLooper();
            assertEquals("正在生成语音", status.getText().toString());
            assertFalse(form.findViewWithTag("speech.preview.start").isEnabled());
        }
    }

    private void open(boolean thirdParty) {
        if (thirdParty) store.putString(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY);
        page = new SpeechSettingsDialog(activity, store, false, (dialog, cleanup) -> {
            children.add(dialog);
            if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
            dialog.setOwnerActivity(activity);
            dialog.show();
        });
        page.dialog().setOwnerActivity(activity);
        page.dialog().show();
        root = page.dialog().getWindow().getDecorView();
    }

    private AlertDialog option(String title) {
        return option(root, title);
    }

    private AlertDialog option(View parent, String title) {
        View option = parent.findViewWithTag("settings.option." + title);
        assertNotNull(title, option);
        assertTrue(option.performClick());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private void chooseProvider(int which) {
        AlertDialog choice = option("语音来源");
        choice.getListView().performItemClick(null, which, which);
        ShadowLooper.idleMainLooper();
    }

    private String value(String title) {
        return value(root, title);
    }

    private String value(View parent, String title) {
        TextView value = parent.findViewWithTag("settings.value." + title);
        assertNotNull(title, value);
        return value.getText().toString();
    }

    private void assertCompactThirdPartyPage() {
        assertNotNull(root.findViewWithTag("settings.option.合成请求头"));
        assertNotNull(root.findViewWithTag("settings.option.音频地址字段"));
        assertNull(root.findViewWithTag("settings.option.音色列表参数"));
        for (String title : new String[]{"音色列表请求头", "列表字段", "音色编号字段", "音色名称字段", "音色列表样本"}) {
            assertNull(title, root.findViewWithTag("settings.option." + title));
        }
    }

    private static EditText input(AlertDialog dialog, String title) {
        return dialog.getWindow().getDecorView().findViewWithTag("settings.input." + title);
    }

    private static void save(AlertDialog dialog) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); }

    private static void await(BooleanSupplier complete) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!complete.getAsBoolean() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        ShadowLooper.idleMainLooper();
        assertTrue("Speech settings request did not complete", complete.getAsBoolean());
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        FeatureSettings settings = FeatureSettings.defaults();
        public FeatureSettings load() { return settings; }
        public void save(FeatureSettings value) { settings = value; }
        public boolean getBoolean(String key, boolean fallback) { return values.containsKey(key) ? (Boolean) values.get(key) : fallback; }
        public String getString(String key, String fallback) { return values.containsKey(key) ? (String) values.get(key) : fallback; }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public boolean isInHost() { return true; }
    }

    @RunWith(RobolectricTestRunner.class)
    @Config(sdk = 28, qualifiers = "w296dp-h560dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public static final class MiuixPreviewTest {
        @Rule public final AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> compose =
                AndroidComposeTestRule_androidKt.createAndroidComposeRule(ComponentActivity.class, EmptyCoroutineContext.INSTANCE);
        private final List<Dialog> children = new ArrayList<>();
        private SpeechSettingsDialog page;

        @After public void close() {
            compose.runOnUiThread(() -> {
                if (page != null) { page.close(); page.dialog().dismiss(); }
                for (Dialog dialog : children) dialog.dismiss();
                return Unit.INSTANCE;
            });
        }

        @Test public void narrowMiuixPageAndParameterEditorKeepTheirControlsVisible() throws Exception { verify(); }

        @Test @Config(sdk = 28, qualifiers = "w640dp-h360dp-land-mdpi")
        public void landscapeMiuixPageAndParameterEditorKeepTheirControlsVisible() throws Exception { verify(); }

        private void verify() throws Exception {
            MemoryStore store = new MemoryStore();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY);
            store.putString(SpeechSettings.GET_TEMPLATE, "https://example.com/tts?ID=example-user-key&body={text}&speaker={voice}");
            compose.runOnUiThread(() -> {
                page = new SpeechSettingsDialog(compose.getActivity(), store, true, (dialog, cleanup) -> {
                    children.add(dialog);
                    dialog.setOwnerActivity(compose.getActivity());
                    if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
                    dialog.show();
                });
                page.dialog().show();
                return Unit.INSTANCE;
            });
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.语音来源"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.合成 GET"), false));
            savePreview(page.dialog(), "speech-settings");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.合成参数"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasContentDescription("添加参数", false, false), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("speech.parameter.name.0"), false));
            AssertionsKt.assertIsDisplayed(compose.onAllNodes(FiltersKt.hasContentDescription("移除参数", false, false), false).get(0));
            savePreview(children.get(children.size() - 1), "speech-parameters");
            compose.runOnUiThread(() -> {
                children.get(children.size() - 1).dismiss();
                return Unit.INSTANCE;
            });
            scrollToOption("音色高级设置");
            savePreview(page.dialog(), "speech-settings-voices");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.音色高级设置"), false));
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色高级设置"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.音色列表请求头"), false));
            savePreview(children.get(children.size() - 1), "speech-voice-advanced");
            compose.runOnUiThread(() -> {
                children.get(children.size() - 1).dismiss();
                store.putString("tts_synthesis_sample", "{\"data\":{\"audio\":\"https://example.com/sound.mp3\"}}");
                return Unit.INSTANCE;
            });
            scrollToOption("音频地址字段");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音频地址字段"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("data.audio", false, false), false));
            savePreview(children.get(children.size() - 1), "speech-audio-fields");
            compose.runOnUiThread(() -> {
                children.get(children.size() - 1).dismiss();
                return Unit.INSTANCE;
            });
            scrollToOption("语音来源");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.语音来源"), false));
            compose.waitForIdle();
            ActionsKt.performClick(compose.onNode(FiltersKt.hasText("MiniMax", false, false), false));
            compose.waitForIdle();
            assertEquals(SpeechSettings.MINIMAX, store.getString(SpeechSettings.PROVIDER, ""));
            scrollToOption("模型");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.模型"), false));
            savePreview(page.dialog(), "speech-minimax-settings");
            compose.runOnIdle(() -> {
                View main = page.dialog().getWindow().getDecorView();
                assertNull(main.findViewWithTag("settings.option.刷新模型"));
                assertNull(main.findViewWithTag("settings.option.刷新官方模型列表"));
                assertNull(main.findViewWithTag("settings.option.刷新音色"));
                return Unit.INSTANCE;
            });
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.模型"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.button.-1"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("手动填写", false, false), false));
            savePreview(children.get(children.size() - 1), "speech-minimax-model-list");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.button.-1"), false));
            compose.waitForIdle();
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("当前接口模型（使用 API Key）", false, false), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("MiniMax 官方目录（无需 Key）", false, false), false));
            savePreview(children.get(children.size() - 1), "speech-minimax-model-refresh-sources");
            compose.runOnUiThread(() -> {
                children.get(children.size() - 1).dismiss();
                return Unit.INSTANCE;
            });
            scrollToOption("音色");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.button.-1"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("手动填写", false, false), false));
            savePreview(children.get(children.size() - 1), "speech-minimax-voice-list");
            compose.runOnUiThread(() -> {
                children.get(children.size() - 1).dismiss();
                return Unit.INSTANCE;
            });
            scrollToOption("MiniMax 高级设置");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.MiniMax 高级设置"), false));
            compose.waitForIdle();
            Dialog minimaxAdvanced = children.get(children.size() - 1);
            scrollToOption(minimaxAdvanced, "情绪");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.情绪"), false));
            scrollToOption(minimaxAdvanced, "发音字典（每行 原文/替换内容）");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.发音字典（每行 原文/替换内容）"), false));
            scrollToOption(minimaxAdvanced, "AIGC 水印");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.AIGC 水印"), false));
            savePreview(minimaxAdvanced, "speech-minimax-advanced");
        }

        private void scrollToOption(String title) {
            scrollToOption(page.dialog(), title);
        }

        private void scrollToOption(Dialog dialog, String title) {
            // Dynamic provider changes replace the native form inside AndroidView. Let that layout
            // settle before reading its row positions or inspecting a newly opened child dialog.
            compose.waitForIdle();
            compose.runOnUiThread(() -> {
                View row = dialog.getWindow().getDecorView().findViewWithTag("settings.option." + title);
                assertNotNull("Dialog must contain the requested row before scrolling: " + title, row);
                android.view.ViewParent parent = row.getParent();
                while (!(parent instanceof android.widget.ScrollView)) parent = parent.getParent();
                ((android.widget.ScrollView) parent).scrollTo(0, row.getTop());
                return Unit.INSTANCE;
            });
            compose.waitForIdle();
        }

        private void savePreview(Dialog dialog, String name) throws Exception {
            compose.waitForIdle();
            String location = System.getenv("DOUAI_CHAT_SCREENSHOTS");
            if (location == null) return;
            compose.runOnUiThread(() -> {
                View decor = dialog.getWindow().getDecorView();
                android.view.WindowManager.LayoutParams bounds = dialog.getWindow().getAttributes();
                int width = bounds.width > 0 ? bounds.width : decor.getWidth();
                int height = bounds.height > 0 ? bounds.height : decor.getHeight();
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, width, height);
                Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                decor.draw(new Canvas(bitmap));
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                HashSet<Integer> colors = new HashSet<>();
                for (int pixel : pixels) colors.add(pixel);
                assertTrue("Miuix speech settings should render controls", colors.size() > 8);
                File directory = new File(location);
                assertTrue(directory.isDirectory() || directory.mkdirs());
                File output = new File(directory, "miuix-" + name + "-" + width + "x" + height + ".png");
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                } catch (Exception failure) { throw new AssertionError(failure); }
                bitmap.recycle();
                return Unit.INSTANCE;
            });
        }
    }
}
