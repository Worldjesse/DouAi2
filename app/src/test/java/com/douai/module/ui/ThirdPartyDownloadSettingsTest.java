package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.ThirdPartyDownloadSettings;
import com.douai.module.config.ThirdPartyRequestTemplate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ThirdPartyDownloadSettingsTest {
    private Activity activity;
    private MemoryStore store;
    private ModuleSettingsScreen screen;
    private View home;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
    }

    @After public void tearDown() {
        screen.close();
        activity.finish();
    }

    @Test public void changingMethodReplacesOnlyItsControlsAndKeepsBothConfigurations() {
        store.putBoolean("download_custom_name", true);
        store.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, "https://example.com/parse?url={url}");
        View page = openDownloads();
        assertNotNull(page.findViewWithTag("settings.option.文件名模板"));
        assertNull(page.findViewWithTag("settings.option.GET 请求"));

        selectMethod(page, 1);
        assertEquals(ThirdPartyDownloadSettings.THIRD_PARTY,
                store.getString(ThirdPartyDownloadSettings.METHOD, ""));
        assertNull(page.findViewWithTag("settings.option.文件名模板"));
        assertNull(page.findViewWithTag("settings.toggle.去除下载限制"));
        assertNotNull(page.findViewWithTag("settings.option.GET 请求"));
        assertNotNull(page.findViewWithTag("settings.option.响应样本"));
        assertNotNull(page.findViewWithTag("settings.option.文件命名字段"));

        selectMethod(page, 0);
        assertNotNull(page.findViewWithTag("settings.option.文件名模板"));
        assertNull(page.findViewWithTag("settings.option.GET 请求"));
        assertTrue(store.getBoolean("download_custom_name", false));
        assertEquals("https://example.com/parse?url={url}",
                store.getString(ThirdPartyDownloadSettings.GET_TEMPLATE, ""));
    }

    @Test public void officialDefaultBehaviorStartsOffAndActualSwitchPersistsBothStates() {
        View page = openDownloads();
        Switch direct = page.findViewWithTag("settings.toggle.默认行为");
        Switch master = page.findViewWithTag("settings.toggle.启用下载菜单");
        assertNotNull(direct);
        assertFalse(direct.isChecked());
        assertFalse(new LocalOptions(store.values).officialDownloadDirect());
        assertFalse(store.values.containsKey(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT));
        assertFalse(master.isChecked());

        direct.performClick();
        assertTrue(direct.isChecked());
        assertTrue(master.isChecked());
        assertTrue(store.load().downloadEnabled);
        assertTrue(store.getBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, false));
        assertTrue(new LocalOptions(store.values).officialDownloadDirect());

        direct.performClick();
        assertFalse(store.getBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, true));
        assertFalse(new LocalOptions(store.values).officialDownloadDirect());
        assertTrue("Turning off direct saving must keep the shared download menu enabled", master.isChecked());
        screen.dismissChildPages();
        page = openDownloads();
        assertFalse(((Switch) page.findViewWithTag("settings.toggle.默认行为")).isChecked());
    }

    @Test public void switchingMethodsKeepsTheirIndependentDefaultBehaviorControlsAndValues() {
        View page = openDownloads();
        Switch direct = page.findViewWithTag("settings.toggle.默认行为");
        direct.performClick();
        selectMethod(page, 1);
        Switch thirdPartyDirect = page.findViewWithTag("settings.toggle.默认行为");
        assertNotNull(thirdPartyDirect);
        assertNotSame(direct, thirdPartyDirect);
        assertFalse(thirdPartyDirect.isChecked());
        assertFalse(new ThirdPartyDownloadSettings(new LocalOptions(store.values)).direct);
        assertNotNull(page.findViewWithTag("settings.option.GET 请求"));
        assertTrue(store.getBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, false));
        thirdPartyDirect.performClick();
        assertTrue(store.getBoolean(ThirdPartyDownloadSettings.DIRECT, false));

        selectMethod(page, 0);
        direct = page.findViewWithTag("settings.toggle.默认行为");
        assertNotNull(direct);
        assertTrue(direct.isChecked());
        assertNull(page.findViewWithTag("settings.option.GET 请求"));
        assertTrue(new LocalOptions(store.values).officialDownloadDirect());
        direct.performClick();
        assertFalse(store.getBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, true));
        assertTrue(store.getBoolean(ThirdPartyDownloadSettings.DIRECT, false));
        selectMethod(page, 1);
        assertTrue(((Switch) page.findViewWithTag("settings.toggle.默认行为")).isChecked());
        assertFalse(new LocalOptions(store.values).officialDownloadDirect());
    }

    @Test public void openingThirdPartySettingsDoesNotInheritOrResetASavedOfficialDefaultBehavior() {
        store.putBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, true);
        View page = openThirdPartyDownloads();
        assertFalse(((Switch) page.findViewWithTag("settings.toggle.默认行为")).isChecked());
        assertNull(page.findViewWithTag("settings.option.文件名模板"));
        assertTrue(store.getBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, false));
        assertFalse(new ThirdPartyDownloadSettings(new LocalOptions(store.values)).direct);
        selectMethod(page, 0);
        assertTrue(((Switch) page.findViewWithTag("settings.toggle.默认行为")).isChecked());
    }

    @Test public void thirdPartyDefaultBehaviorStartsOffAndItsSwitchSavesBothStatesWithoutChangingFields() {
        store.putString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, "[\"data.audio\"]");
        store.putString(ThirdPartyDownloadSettings.NAMING_FIELDS, "[\"data.id\"]");
        View page = openThirdPartyDownloads();
        Switch direct = page.findViewWithTag("settings.toggle.默认行为");
        Switch master = page.findViewWithTag("settings.toggle.启用下载菜单");
        assertNotNull(direct);
        assertFalse(direct.isChecked());
        assertFalse(new ThirdPartyDownloadSettings(new LocalOptions(store.values)).direct);
        assertFalse(store.values.containsKey(ThirdPartyDownloadSettings.DIRECT));
        assertFalse(master.isChecked());

        direct.performClick();
        assertTrue(master.isChecked());
        assertTrue(store.load().downloadEnabled);
        assertTrue(store.getBoolean(ThirdPartyDownloadSettings.DIRECT, false));
        assertTrue(new ThirdPartyDownloadSettings(new LocalOptions(store.values)).direct);
        assertFalse(store.values.containsKey(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT));
        direct.performClick();
        assertFalse(store.getBoolean(ThirdPartyDownloadSettings.DIRECT, true));
        assertFalse(new ThirdPartyDownloadSettings(new LocalOptions(store.values)).direct);
        assertTrue(master.isChecked());
        assertEquals("[\"data.audio\"]", store.getString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, ""));
        assertEquals("[\"data.id\"]", store.getString(ThirdPartyDownloadSettings.NAMING_FIELDS, ""));
        screen.dismissChildPages();
        page = openDownloads();
        assertFalse(((Switch) page.findViewWithTag("settings.toggle.默认行为")).isChecked());
    }

    @Test public void savingDirectoryIsSharedBetweenMethodsAndCanBeResetToDefault() {
        String tree = "content://directories/tree/primary%3ADownload%2FDouAi";
        store.putString("download_directory_uri", tree);
        View page = openDownloads();
        View directory = page.findViewWithTag("settings.option.保存位置");
        assertNotNull(directory);
        assertEquals("primary:Download/DouAi", ((TextView) page.findViewWithTag("settings.value.保存位置")).getText().toString());
        selectMethod(page, 1);
        assertSame(directory, page.findViewWithTag("settings.option.保存位置"));
        assertEquals(tree, store.getString("download_directory_uri", ""));
        selectMethod(page, 0);
        assertSame(directory, page.findViewWithTag("settings.option.保存位置"));
        AlertDialog choice = openOption(page, "保存位置");
        choice.getListView().performItemClick(null, 1, 1);
        ShadowLooper.idleMainLooper();
        assertEquals("", store.getString("download_directory_uri", "missing"));
        assertEquals("默认位置", ((TextView) page.findViewWithTag("settings.value.保存位置")).getText().toString());
    }

    @Test public void secretIsMaskedAndHeadersRequireAJsonObjectWithStringValues() {
        store.putString(ThirdPartyDownloadSettings.APP_SECRET, "private-key");
        View page = openThirdPartyDownloads();
        TextView preview = page.findViewWithTag("settings.value.签名密钥");
        assertEquals("已设置", preview.getText().toString());
        AlertDialog secret = openOption(page, "签名密钥");
        EditText secretInput = secret.getWindow().getDecorView().findViewWithTag("settings.input.签名密钥");
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                secretInput.getInputType() & InputType.TYPE_MASK_VARIATION);
        secret.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();

        AlertDialog headers = openOption(page, "请求头");
        EditText input = headers.getWindow().getDecorView().findViewWithTag("settings.input.请求头");
        input.setText("{\"Authorization\":42}");
        headers.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(headers.isShowing());
        assertNotNull(input.getError());
        assertEquals("{}", store.getString(ThirdPartyDownloadSettings.HEADERS, "{}"));
        input.setText("{\"Authorization\":\"Bearer user-key\"}");
        headers.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(headers.isShowing());
        assertEquals("{\"Authorization\":\"Bearer user-key\"}",
                store.getString(ThirdPartyDownloadSettings.HEADERS, ""));
    }

    @Test public void changingGetDerivesNewParameterNamesAndRejectsInvalidAddress() {
        store.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, "https://example.com/parse?app_id=123&url={url}");
        View page = openThirdPartyDownloads();
        assertEquals("app_id、url", ((TextView) page.findViewWithTag("settings.value.请求参数")).getText().toString());
        AlertDialog editor = openOption(page, "GET 请求");
        EditText input = editor.getWindow().getDecorView().findViewWithTag("settings.input.GET 请求");
        input.setText("not-a-url");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(editor.isShowing());
        assertNotNull(input.getError());
        input.setText("https://example.com/parse?ID=456&link={url}");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(editor.isShowing());
        assertEquals("ID、link", ((TextView) page.findViewWithTag("settings.value.请求参数")).getText().toString());
        AlertDialog parameters = openOption(page, "请求参数");
        View form = parameters.getWindow().getDecorView();
        assertEquals("ID", ((EditText) form.findViewWithTag("third_party.parameter.name.0")).getText().toString());
        assertEquals("456", ((EditText) form.findViewWithTag("third_party.parameter.value.0")).getText().toString());
        assertEquals(1, ((Spinner) form.findViewWithTag("third_party.parameter.source.1")).getSelectedItemPosition());
    }

    @Test public void parametersCanBeAddedRemovedRenamedAndBoundToTheWorkLink() {
        store.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, "https://example.com/parse?app_id=123&url={url}");
        View page = openThirdPartyDownloads();
        AlertDialog editor = openOption(page, "请求参数");
        View form = editor.getWindow().getDecorView();
        ((EditText) form.findViewWithTag("third_party.parameter.name.0")).setText("ID");
        ((EditText) form.findViewWithTag("third_party.parameter.value.0")).setText("key + 空格&值");
        assertTrue(form.findViewWithTag("third_party.parameter.remove.1").performClick());
        assertTrue(form.findViewWithTag("third_party.parameter.add").performClick());
        ((EditText) form.findViewWithTag("third_party.parameter.name.2")).setText("target");
        ((Spinner) form.findViewWithTag("third_party.parameter.source.2")).setSelection(1);
        ShadowLooper.idleMainLooper();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(editor.isShowing());
        ThirdPartyRequestTemplate result = ThirdPartyRequestTemplate.parse(store.getString(ThirdPartyDownloadSettings.GET_TEMPLATE, ""));
        assertEquals(2, result.parameters.size());
        assertEquals("ID", result.parameters.get(0).name);
        assertEquals("key + 空格&值", result.parameters.get(0).value);
        assertEquals("target", result.parameters.get(1).name);
        assertEquals("{url}", result.parameters.get(1).value);
        assertEquals("ID、target", ((TextView) page.findViewWithTag("settings.value.请求参数")).getText().toString());
        assertEquals(store.getString(ThirdPartyDownloadSettings.GET_TEMPLATE, ""),
                ((TextView) page.findViewWithTag("settings.value.GET 请求")).getText().toString());
    }

    @Test public void linkParameterCanBecomeFixedAndAllParametersCanBeRemoved() {
        store.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, "https://example.com/parse?link={url}");
        View page = openThirdPartyDownloads();
        AlertDialog editor = openOption(page, "请求参数");
        View form = editor.getWindow().getDecorView();
        ((Spinner) form.findViewWithTag("third_party.parameter.source.0")).setSelection(0);
        ((EditText) form.findViewWithTag("third_party.parameter.value.0")).setText("fixed-video-id");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("https://example.com/parse?link=fixed-video-id",
                store.getString(ThirdPartyDownloadSettings.GET_TEMPLATE, ""));
        editor = openOption(page, "请求参数");
        assertTrue(editor.getWindow().getDecorView().findViewWithTag("third_party.parameter.remove.0").performClick());
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("https://example.com/parse", store.getString(ThirdPartyDownloadSettings.GET_TEMPLATE, ""));
        assertEquals("无请求参数", ((TextView) page.findViewWithTag("settings.value.请求参数")).getText().toString());
    }

    @Test public void signatureChoiceHidesCredentialsAndRetainsTheLegacySecret() {
        store.putString(ThirdPartyDownloadSettings.APP_SECRET, "private-key");
        View page = openThirdPartyDownloads();
        assertNull(page.findViewWithTag("settings.option.App ID"));
        assertNull(page.findViewWithTag("settings.option.App Secret"));
        assertNotNull(page.findViewWithTag("settings.option.签名密钥"));
        assertNotNull(page.findViewWithTag("settings.option.签名请求头"));
        AlertDialog mode = openOption(page, "签名方式");
        mode.getListView().performItemClick(null, 0, 0);
        ShadowLooper.idleMainLooper();
        assertEquals(ThirdPartyDownloadSettings.SIGNATURE_NONE, store.getString(ThirdPartyDownloadSettings.SIGNATURE_MODE, ""));
        assertNull(page.findViewWithTag("settings.option.签名密钥"));
        assertNull(page.findViewWithTag("settings.option.签名请求头"));
        assertEquals("private-key", store.getString(ThirdPartyDownloadSettings.APP_SECRET, ""));
        mode = openOption(page, "签名方式");
        mode.getListView().performItemClick(null, 1, 1);
        ShadowLooper.idleMainLooper();
        assertEquals("已设置", ((TextView) page.findViewWithTag("settings.value.签名密钥")).getText().toString());
        AlertDialog header = openOption(page, "签名请求头");
        EditText input = header.getWindow().getDecorView().findViewWithTag("settings.input.签名请求头");
        input.setText("invalid header");
        header.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(header.isShowing());
        input.setText("X-Signature");
        header.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("X-Signature", store.getString(ThirdPartyDownloadSettings.SIGNATURE_HEADER, ""));
    }

    @Test public void legacyAppIdIsResolvedIntoAnEditableParameterWithoutChangingOtherSettings() {
        store.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, "https://example.com/parse?app_id={app_id}&url={url}");
        store.putString(ThirdPartyDownloadSettings.APP_ID, "legacy-id");
        store.putString(ThirdPartyDownloadSettings.APP_SECRET, "private-key");
        store.putString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, "[\"media\"]");
        View page = openThirdPartyDownloads();
        AlertDialog editor = openOption(page, "请求参数");
        View form = editor.getWindow().getDecorView();
        assertEquals("legacy-id", ((EditText) form.findViewWithTag("third_party.parameter.value.0")).getText().toString());
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("https://example.com/parse?app_id=legacy-id&url={url}",
                store.getString(ThirdPartyDownloadSettings.GET_TEMPLATE, ""));
        assertEquals(ThirdPartyDownloadSettings.SIGNATURE_MD5, store.getString(ThirdPartyDownloadSettings.SIGNATURE_MODE, ""));
        assertEquals("legacy-id", store.getString(ThirdPartyDownloadSettings.APP_ID, ""));
        assertEquals("private-key", store.getString(ThirdPartyDownloadSettings.APP_SECRET, ""));
        assertEquals("[\"media\"]", store.getString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, ""));
    }

    @Test public void choosingSignaturePreservesTheLegacyLinkPrefixBinding() {
        store.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, "https://example.com/parse?link=");
        store.putString(ThirdPartyDownloadSettings.APP_SECRET, "private-key");
        View page = openThirdPartyDownloads();
        AlertDialog mode = openOption(page, "签名方式");
        mode.getListView().performItemClick(null, 0, 0);
        ShadowLooper.idleMainLooper();
        assertEquals("https://example.com/parse?link={url}", store.getString(ThirdPartyDownloadSettings.GET_TEMPLATE, ""));
        assertEquals(ThirdPartyDownloadSettings.SIGNATURE_NONE, store.getString(ThirdPartyDownloadSettings.SIGNATURE_MODE, ""));
    }

    @Test public void getTestWithNoParametersDoesNotRequireShareTextOrAddAppIdOrSignature() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"media\":\"https://example.com/video.mp4\"}"));
            server.start();
            store.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/static").toString());
            store.putString(ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_NONE);
            store.putString(ThirdPartyDownloadSettings.APP_ID, "unused-id");
            store.putString(ThirdPartyDownloadSettings.APP_SECRET, "unused-secret");
            View page = openThirdPartyDownloads();
            assertNull(page.findViewWithTag("settings.option.签名密钥"));
            AlertDialog test = openOption(page, "测试 GET");
            View form = test.getWindow().getDecorView();
            form.findViewWithTag("third_party.fetch").performClick();
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/static", request.getPath());
            assertNull(request.getHeader("Sign"));
            await(() -> "解析成功".contentEquals(((TextView) form.findViewWithTag("third_party.status")).getText()));
        }
    }

    @Test public void savingAnIntentionallyEmptyQueryValueDoesNotBindItToTheShareUrl() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"media\":\"https://example.com/video.mp4\"}"));
            server.start();
            View page = openThirdPartyDownloads();
            AlertDialog editor = openOption(page, "GET 请求");
            ((EditText) editor.getWindow().getDecorView().findViewWithTag("settings.input.GET 请求"))
                    .setText(server.url("/parse").toString() + "?ID=");
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            AlertDialog test = openOption(page, "测试 GET");
            View form = test.getWindow().getDecorView();
            form.findViewWithTag("third_party.fetch").performClick();
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/parse?ID=", request.getPath());
            await(() -> "解析成功".contentEquals(((TextView) form.findViewWithTag("third_party.status")).getText()));
        }
    }

    @Test public void samplePreviewDiscoversNestedDownloadAndInformationFields() {
        View page = openThirdPartyDownloads();
        AlertDialog editor = openOption(page, "响应样本");
        View form = editor.getWindow().getDecorView();
        EditText sample = form.findViewWithTag("third_party.sample");
        sample.setText("{\"payload\":{\"clips\":[\"https://example.com/clip.mp4\"],\"name\":\"作品\",\"id\":\"123\"}}");
        assertTrue(form.findViewWithTag("third_party.parse").performClick());
        String preview = ((TextView) form.findViewWithTag("third_party.preview")).getText().toString();
        assertTrue(preview, preview.contains("payload.clips"));
        assertTrue(preview, preview.contains("payload.name"));
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(editor.isShowing());
        assertEquals(sample.getText().toString(), store.getString(ThirdPartyDownloadSettings.SAMPLE, ""));
        AlertDialog fields = openOption(page, "下载字段");
        CheckBox clips = fields.getWindow().getDecorView().findViewWithTag("third_party.field.payload.clips");
        assertNotNull(clips);
        clips.setChecked(true);
        fields.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(ThirdPartyDownloadSettings.fields(store.getString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, "[]"))
                .contains("payload.clips"));
    }

    @Test public void downloadFieldsKeepEmptyAndMissingSelectionsAndAllowAdditionalPaths() {
        store.putString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS,
                "[\"data.video\",\"data.images\",\"data.future_media\"]");
        View page = openThirdPartyDownloads();
        AlertDialog editor = openOption(page, "下载字段");
        View form = editor.getWindow().getDecorView();
        CheckBox images = form.findViewWithTag("third_party.field.data.images");
        CheckBox missing = form.findViewWithTag("third_party.field.data.future_media");
        assertTrue(images.isChecked());
        assertTrue(missing.isChecked());
        ((CheckBox) form.findViewWithTag("third_party.field.data.video")).setChecked(false);
        ((EditText) form.findViewWithTag("third_party.extra_fields")).setText("data.documents\ndata.future_media");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("data.images", "data.future_media", "data.documents"),
                ThirdPartyDownloadSettings.fields(store.getString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, "[]")));
    }

    @Test public void unselectedEmptyArrayIsAvailableForFutureImageResponses() {
        store.putString(ThirdPartyDownloadSettings.SAMPLE, "{\"media\":[],\"title\":\"作品\"}");
        store.putString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, "[]");
        View page = openThirdPartyDownloads();
        AlertDialog editor = openOption(page, "下载字段");
        CheckBox media = editor.getWindow().getDecorView().findViewWithTag("third_party.field.media");
        assertNotNull(media);
        assertFalse(media.isChecked());
        media.setChecked(true);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("media"),
                ThirdPartyDownloadSettings.fields(store.getString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, "[]")));
    }

    @Test public void namingFieldsPersistTheUserOrderAndNewScalarSelection() {
        View page = openThirdPartyDownloads();
        AlertDialog editor = openOption(page, "文件命名字段");
        View form = editor.getWindow().getDecorView();
        assertTrue(form.findViewWithTag("third_party.naming.down.0").performClick());
        assertEquals("1. data.id", ((TextView) form.findViewWithTag("third_party.naming.path.0")).getText().toString());
        assertTrue(form.findViewWithTag("third_party.naming.add").performClick());
        AlertDialog chooser = (AlertDialog) ShadowDialog.getLatestDialog();
        int textIndex = -1;
        for (int index = 0; index < chooser.getListView().getAdapter().getCount(); index++) {
            if ("data.text".equals(chooser.getListView().getAdapter().getItem(index))) textIndex = index;
        }
        assertTrue(textIndex >= 0);
        chooser.getListView().performItemClick(null, textIndex, textIndex);
        ShadowLooper.idleMainLooper();
        assertTrue(editor.isShowing());
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("data.id", "data.author.nickname", "data.text"),
                ThirdPartyDownloadSettings.fields(store.getString(ThirdPartyDownloadSettings.NAMING_FIELDS, "[]")));
    }

    @Test public void getTestParsesShareTextAndSavesTheResponseOnlyOnExplicitAction() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String body = "{\"media\":\"https://example.com/sample.mp4\",\"title\":\"sample\"}";
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
            server.start();
            store.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/parse").toString() + "?url={url}");
            View page = openThirdPartyDownloads();
            AlertDialog test = openOption(page, "测试 GET");
            View form = test.getWindow().getDecorView();
            ((EditText) form.findViewWithTag("third_party.share")).setText(
                    "复制打开抖音，看看作品 https://v.douyin.com/XVo-IAGSo2s/ Cus:/ 08/27");
            form.findViewWithTag("third_party.fetch").performClick();
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("https://v.douyin.com/XVo-IAGSo2s/", request.getRequestUrl().queryParameter("url"));
            await(() -> "解析成功".contentEquals(((TextView) form.findViewWithTag("third_party.status")).getText()));
            assertFalse(store.values.containsKey(ThirdPartyDownloadSettings.SAMPLE));
            assertTrue(form.findViewWithTag("third_party.save_sample").performClick());
            assertEquals(body, store.getString(ThirdPartyDownloadSettings.SAMPLE, ""));
        }
    }

    @Test public void closingDownloadPageCancelsItsPendingGetTest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            store.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/parse").toString() + "?url={url}");
            View page = openThirdPartyDownloads();
            AlertDialog test = openOption(page, "测试 GET");
            View form = test.getWindow().getDecorView();
            ((EditText) form.findViewWithTag("third_party.share")).setText("https://v.douyin.com/example/");
            form.findViewWithTag("third_party.fetch").performClick();
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            screen.dismissChildPages();
            ShadowLooper.idleMainLooper();
            assertFalse(test.isShowing());
            assertFalse(store.values.containsKey(ThirdPartyDownloadSettings.SAMPLE));
        }
    }

    private View openDownloads() {
        assertTrue(home.findViewWithTag("settings.category.DOWNLOAD").performClick());
        return ShadowDialog.getLatestDialog().getWindow().getDecorView();
    }

    private View openThirdPartyDownloads() {
        store.putString(ThirdPartyDownloadSettings.METHOD, ThirdPartyDownloadSettings.THIRD_PARTY);
        return openDownloads();
    }

    private void selectMethod(View page, int index) {
        AlertDialog choice = openOption(page, "下载方式");
        choice.getListView().performItemClick(null, index, index);
        ShadowLooper.idleMainLooper();
    }

    private static AlertDialog openOption(View page, String title) {
        View option = page.findViewWithTag("settings.option." + title);
        assertNotNull(title, option);
        assertTrue(option.performClick());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static void await(BooleanSupplier ready) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        ShadowLooper.idleMainLooper();
        assertTrue("Background GET did not finish", ready.getAsBoolean());
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
