package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class MenuConfigurationAccessTest {
    private Activity activity;
    private MemoryStore store;
    private ModuleSettingsScreen screen;
    private View home;

    @Before public void createScreen() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
    }

    @After public void closeScreen() {
        screen.dismissChildPages();
        activity.finish();
    }

    @Test public void commentVoicePathCanBeConfiguredBeforeReplacementIsEnabled() {
        assertVoicePathCanBeConfiguredWhileDisabled("COMMENT", "评论语音文件",
                "替换语音文件", "comment_voice_path", "comment_voice_replace");
    }

    @Test public void privateMessageVoicePathCanBeConfiguredBeforeReplacementIsEnabled() {
        assertVoicePathCanBeConfiguredWhileDisabled("MESSAGE", "私聊语音文件",
                "替换私聊语音", "dm_voice_path", "dm_voice_replace");
    }

    @Test public void enabledCommentReplacementAllowsRepeatedPathChangesWithoutToggling() {
        assertEnabledVoicePathRemainsEditable("COMMENT", "评论语音文件",
                "替换语音文件", "comment_voice_path", "comment_voice_replace");
    }

    @Test public void enabledPrivateMessageReplacementAllowsRepeatedPathChangesWithoutToggling() {
        assertEnabledVoicePathRemainsEditable("MESSAGE", "私聊语音文件",
                "替换私聊语音", "dm_voice_path", "dm_voice_replace");
    }

    @Test public void cancellingVoicePathEditorsDoesNotWriteConfiguration() {
        String[][] targets = {
                {"COMMENT", "评论语音文件", "comment_voice_path"},
                {"MESSAGE", "私聊语音文件", "dm_voice_path"}
        };
        for (String[] target : targets) {
            store.putString(target[2], "/storage/emulated/0/Music/original.mp3");
            Map<String, Object> saved = new HashMap<>(store.values);
            AlertDialog editor = openOption(openCategory(target[0]), target[1]);
            pathInput(editor).setText("/storage/emulated/0/Music/cancelled.mp3");
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse(editor.isShowing());
            assertEquals(target[1], saved, store.values);
            screen.dismissChildPages();
        }
    }

    @Test public void commentLongPressToolsOpenFromAnOptionAndSaveIndependentChoices() {
        View page = openCategory("COMMENT");
        assertNull(page.findViewWithTag("settings.toggle.长按操作"));
        AlertDialog choices = openOption(page, "长按操作");
        ListView list = choices.getListView();
        assertNotNull(list);
        assertEquals(3, list.getCount());
        assertEquals("复制评论", list.getAdapter().getItem(0).toString());
        assertEquals("保存评论图片", list.getAdapter().getItem(1).toString());
        assertEquals("复制源数据", list.getAdapter().getItem(2).toString());
        list.performItemClick(null, 0, 0);
        list.performItemClick(null, 2, 2);
        save(choices);
        assertTrue(store.getBoolean("copy_comment_text", false));
        assertFalse(store.getBoolean("comment_media_tools", false));
        assertTrue(store.getBoolean("copy_source_data", false));

        choices = openOption(page, "长按操作");
        assertTrue(choices.getListView().isItemChecked(0));
        assertFalse(choices.getListView().isItemChecked(1));
        assertTrue(choices.getListView().isItemChecked(2));
        choices.getListView().performItemClick(null, 0, 0);
        choices.getListView().performItemClick(null, 1, 1);
        save(choices);
        assertFalse(store.getBoolean("copy_comment_text", true));
        assertTrue(store.getBoolean("comment_media_tools", false));
        assertTrue(store.getBoolean("copy_source_data", false));
    }

    @Test public void commentAndMessagePagesBothReachTextToSpeechConfiguration() {
        Map<String, Object> saved = new HashMap<>(store.values);
        for (String category : new String[]{"COMMENT", "MESSAGE"}) {
            Dialog speech = openOption(openCategory(category), "文本转语音设置");
            assertNotNull(category, speech.getWindow().getDecorView()
                    .findViewWithTag("settings.option.语音来源"));
            assertEquals(saved, store.values);
            screen.dismissChildPages();
        }
    }

    private void assertVoicePathCanBeConfiguredWhileDisabled(String category, String title,
            String switchTitle, String pathKey, String switchKey) {
        View page = openCategory(category);
        assertFalse(toggle(page, switchTitle).isChecked());
        AlertDialog editor = openOption(page, title);
        pathInput(editor).setText("/storage/emulated/0/Music/replacement.mp3");
        save(editor);
        assertEquals("/storage/emulated/0/Music/replacement.mp3", store.getString(pathKey, ""));
        assertFalse(store.getBoolean(switchKey, false));
        assertFalse(toggle(page, switchTitle).isChecked());
        assertEquals("/storage/emulated/0/Music/replacement.mp3",
                pathInput(openOption(page, title)).getText().toString());
    }

    private void assertEnabledVoicePathRemainsEditable(String category, String title,
            String switchTitle, String pathKey, String switchKey) {
        store.putBoolean(switchKey, true);
        store.putString(pathKey, "/storage/emulated/0/Music/original.mp3");
        View page = openCategory(category);
        assertTrue(toggle(page, switchTitle).isChecked());
        AlertDialog first = openOption(page, title);
        assertEquals("/storage/emulated/0/Music/original.mp3", pathInput(first).getText().toString());
        pathInput(first).setText("/storage/emulated/0/Music/first.mp3");
        save(first);
        assertTrue(store.getBoolean(switchKey, false));
        assertTrue(toggle(page, switchTitle).isChecked());

        AlertDialog second = openOption(page, title);
        assertEquals("/storage/emulated/0/Music/first.mp3", pathInput(second).getText().toString());
        pathInput(second).setText("/storage/emulated/0/Music/second.mp3");
        save(second);
        assertEquals("/storage/emulated/0/Music/second.mp3", store.getString(pathKey, ""));
        assertTrue(store.getBoolean(switchKey, false));
        assertTrue(toggle(page, switchTitle).isChecked());
    }

    private View openCategory(String category) {
        View row = home.findViewWithTag("settings.category." + category);
        assertNotNull(category, row);
        assertTrue(category, row.performClick());
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(category, dialog);
        assertTrue(category, dialog.isShowing());
        return dialog.getWindow().getDecorView();
    }

    private static AlertDialog openOption(View page, String title) {
        View row = page.findViewWithTag("settings.option." + title);
        assertNotNull(title, row);
        assertTrue(title, row.isEnabled());
        assertTrue(title, row.performClick());
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(title, dialog instanceof AlertDialog);
        assertTrue(title, dialog.isShowing());
        AlertDialog alert = (AlertDialog) dialog;
        ListView choices = alert.getListView();
        if (choices != null) {
            ShadowLooper.idleMainLooper();
            choices.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST));
            choices.layout(0, 0, choices.getMeasuredWidth(), choices.getMeasuredHeight());
            ShadowLooper.idleMainLooper();
        }
        return alert;
    }

    private static Switch toggle(View page, String title) {
        Switch toggle = page.findViewWithTag("settings.toggle." + title);
        assertNotNull(title, toggle);
        return toggle;
    }

    private static EditText pathInput(Dialog dialog) {
        EditText input = findPathInput(dialog.getWindow().getDecorView());
        assertNotNull("文件路径", input);
        return input;
    }

    private static EditText findPathInput(View view) {
        if (view instanceof EditText && "文件路径".contentEquals(view.getContentDescription())) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText input = findPathInput(group.getChildAt(index));
                if (input != null) return input;
            }
        }
        return null;
    }

    private static void save(AlertDialog dialog) {
        assertNotNull(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
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
