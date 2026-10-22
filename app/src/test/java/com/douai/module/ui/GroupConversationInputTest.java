package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.NativeMessagingHooks;
import com.douai.module.feature.ProfileListEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class GroupConversationInputTest {
    private Activity activity;
    private MemoryStore store;
    private ModuleSettingsScreen screen;
    private View home;
    private View groupSettings;
    private Field groupsField;
    private Field profilesField;
    private Object previousGroups;
    private Object previousProfiles;

    @Before public void setUp() throws Exception {
        groupsField = NativeMessagingHooks.class.getDeclaredField("groups");
        profilesField = NativeMessagingHooks.class.getDeclaredField("groupProfiles");
        groupsField.setAccessible(true);
        profilesField.setAccessible(true);
        previousGroups = groupsField.get(null);
        previousProfiles = profilesField.get(null);
        groupsField.set(null, Collections.emptyMap());
        profilesField.set(null, Collections.emptyMap());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
        store.putString("reply_group_scope_mode", "whitelist");
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
    }

    @After public void tearDown() throws Exception {
        if (screen != null) screen.dismissChildPages();
        groupsField.set(null, previousGroups);
        profilesField.set(null, previousProfiles);
        if (activity != null) activity.finish();
    }

    @Test public void emptyGroupCacheAllowsEnteringAndNormalizingConversationIds() {
        AlertDialog editor = openConversationEditor();
        EditText input = conversationInput(editor);
        assertEquals("", input.getText().toString());
        input.setText(" group101, group202,group101, ,group303 ");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("group101,group202,group303", store.getString("reply_group_targets", ""));
        assertEquals(1, store.groupWrites);
        TextView preview = groupSettings.findViewWithTag("settings.value.选择群聊");
        assertEquals("已选择 3 个群聊", preview.getText().toString());
    }

    @Test public void cachedGroupListStillOffersConversationInputWithSavedValues() throws Exception {
        groupsField.set(null, Collections.singletonMap("group101", "测试群"));
        profilesField.set(null, Collections.singletonMap("group101",
                new ProfileListEntry("group101", "测试群", "")));
        store.putString("reply_group_targets", "group101,unlisted-group");
        store.groupWrites = 0;
        AlertDialog editor = openConversationEditor();

        assertEquals("group101,unlisted-group", conversationInput(editor).getText().toString());
        conversationInput(editor).setText("unlisted-group,new-group");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("unlisted-group,new-group", store.getString("reply_group_targets", ""));
        assertEquals(1, store.groupWrites);
    }

    @Test public void cancellingConversationInputPreservesSavedSelection() {
        store.putString("reply_group_targets", "original-group");
        store.groupWrites = 0;
        AlertDialog editor = openConversationEditor();
        conversationInput(editor).setText("discarded-group");
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("original-group", store.getString("reply_group_targets", ""));
        assertEquals(0, store.groupWrites);
    }

    @Test public void closingSettingsInvalidatesConversationEditorSave() {
        store.putString("reply_group_targets", "original-group");
        store.groupWrites = 0;
        AlertDialog editor = openConversationEditor();
        conversationInput(editor).setText("late-group");
        screen.dismissChildPages();
        ShadowLooper.idleMainLooper();
        assertFalse(editor.isShowing());
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("original-group", store.getString("reply_group_targets", ""));
        assertEquals(0, store.groupWrites);
    }

    @Test public void closingBeforeManualTransitionDoesNotOpenAnEditorOrWrite() {
        AlertDialog choices = openGroupSelection();
        assertManualButton(choices).performClick();
        screen.dismissChildPages();
        ShadowLooper.idleMainLooper();

        assertFalse(ShadowDialog.getLatestDialog().isShowing());
        assertEquals("", store.getString("reply_group_targets", ""));
        assertEquals(0, store.groupWrites);
    }

    @Test public void automaticCommentsDoNotShowPausedWhenInteractionAutomationIsOff() {
        store.putBoolean("native_auto_comment", true);
        assertFalse(store.load().automationEnabled);
        option(home, "settings.category.AUTO_COMMENT").performClick();
        View comment = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        Switch enabled = comment.findViewWithTag("settings.toggle.开启自动评论");
        assertNotNull(enabled);
        assertTrue(enabled.isChecked());
        assertFalse(containsVisibleText(comment, "已暂停"));

        enabled.setChecked(false);
        assertFalse(store.getBoolean("native_auto_comment", true));
        assertFalse(containsVisibleText(comment, "已暂停"));
        enabled.setChecked(true);
        assertTrue(store.getBoolean("native_auto_comment", false));
        assertFalse(containsVisibleText(comment, "已暂停"));
    }

    private static boolean containsVisibleText(View view, String expected) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(expected)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsVisibleText(group.getChildAt(index), expected)) return true;
            }
        }
        return false;
    }

    private AlertDialog openConversationEditor() {
        AlertDialog choices = openGroupSelection();
        assertManualButton(choices).performClick();
        ShadowLooper.idleMainLooper();
        Dialog latest = ShadowDialog.getLatestDialog();
        assertNotSame(choices, latest);
        assertTrue(latest.isShowing());
        assertTrue(latest instanceof AlertDialog);
        return (AlertDialog) latest;
    }

    private AlertDialog openGroupSelection() {
        option(home, "settings.category.AUTO_REPLY").performClick();
        View reply = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        option(reply, "settings.option.群聊范围").performClick();
        groupSettings = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        option(groupSettings, "settings.option.选择群聊").performClick();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static View assertManualButton(AlertDialog choices) {
        TextView button = choices.getButton(AlertDialog.BUTTON_NEUTRAL);
        assertNotNull("群聊选择应提供手动填写入口", button);
        assertEquals("填写会话 ID", button.getText().toString());
        assertEquals(View.VISIBLE, button.getVisibility());
        return button;
    }

    private static EditText conversationInput(AlertDialog dialog) {
        EditText input = findInput(dialog.getWindow().getDecorView(), "群会话 ID，以逗号分隔");
        assertNotNull("填写群会话 ID 的输入框应可达", input);
        return input;
    }

    private static EditText findInput(View view, String description) {
        if (view instanceof EditText && description.contentEquals(view.getContentDescription())) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText input = findInput(group.getChildAt(index), description);
                if (input != null) return input;
            }
        }
        return null;
    }

    private static View option(View root, String tag) {
        View row = root.findViewWithTag(tag);
        assertNotNull(tag, row);
        return row;
    }

    private static final class MemoryStore implements SettingsStore {
        private final Map<String, Object> values = new HashMap<>();
        private FeatureSettings settings = FeatureSettings.defaults();
        int groupWrites;

        public FeatureSettings load() { return settings; }
        public void save(FeatureSettings value) { settings = value; }
        public boolean getBoolean(String key, boolean fallback) {
            return (Boolean) values.getOrDefault(key, fallback);
        }
        public String getString(String key, String fallback) {
            return (String) values.getOrDefault(key, fallback);
        }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) {
            values.put(key, value);
            if ("reply_group_targets".equals(key)) groupWrites++;
        }
        public boolean isInHost() { return true; }
    }
}
