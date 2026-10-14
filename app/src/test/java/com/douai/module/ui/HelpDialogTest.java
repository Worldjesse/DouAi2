package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

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
public final class HelpDialogTest {
    private Activity activity;
    private ModuleSettingsScreen screen;
    private View home;

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        screen = new ModuleSettingsScreen(activity, new MemoryStore());
        home = screen.createView();
        activity.setContentView(home);
    }

    @After public void close() {
        screen.dismissChildPages();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void aboutIsImmediatelyAfterExtensionAndOpensItsExistingProjectInformation() {
        View extension = home.findViewWithTag("settings.category.EXTENSION");
        View about = home.findViewWithTag("settings.category.ABOUT");
        LinearLayout list = (LinearLayout) extension.getParent();
        assertEquals(list.indexOfChild(extension) + 2, list.indexOfChild(about));
        assertEquals(View.GONE, ((LinearLayout) about).getChildAt(1).getVisibility());
        about.performClick();
        AlertDialog page = latest();
        TextView details = page.findViewById(android.R.id.content).findViewWithTag("about.details");
        assertTrue(details.getText().toString().contains(AboutDialog.SOURCE_URL));
        assertTrue(details.getText().toString().contains(AboutDialog.RELEASES_URL));
        assertTrue(details.getText().toString().contains(AboutDialog.LSP_URL));
        assertTrue(details.getText().toString().contains(AboutDialog.MIUIX_URL));
        assertTrue(details.getText().toString().contains("免责协议"));
        assertTrue(details.getText().toString().contains(AboutDialog.DISCLAIMER_TEXT));
        assertNotNull(page.getWindow().getDecorView().findViewWithTag("about.help"));
    }

    @Test public void helpSearchFindsBodyContentAndReturningFromTopicPreservesQuery() {
        AlertDialog help = openHelp();
        EditText search = help.getWindow().getDecorView().findViewWithTag("help.search");
        search.setText("{voice}");
        assertFalse(HelpContent.search("{voice}").isEmpty());
        HelpContent.Section match = HelpContent.search("{voice}").get(0);
        help.getWindow().getDecorView().findViewWithTag("help.topic." + match.id).performClick();
        ShadowLooper.idleMainLooper();
        TextView body = help.getWindow().getDecorView().findViewWithTag("help.body");
        for (HelpContent.Part part : match.parts) {
            assertTrue(body.getText().toString().contains(part.title));
            assertTrue(body.getText().toString().contains(part.text));
        }
        assertTrue(body.isTextSelectable());
        help.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(help.isShowing());
        assertNull(help.getWindow().getDecorView().findViewWithTag("help.body"));
        assertEquals("{voice}", ((EditText) help.getWindow().getDecorView().findViewWithTag("help.search")).getText().toString());
        search.setText("没有这样的帮助XYZ");
        assertNotNull(help.getWindow().getDecorView().findViewWithTag("help.empty"));
        search.setText("");
        assertNotNull(help.getWindow().getDecorView().findViewWithTag("help.topic." + match.id));
    }

    @Test public void hardwareBackReturnsFromTopicThenClosesHelpAndKeepsAbout() {
        home.findViewWithTag("settings.category.ABOUT").performClick();
        AlertDialog about = latest();
        about.getWindow().getDecorView().findViewWithTag("about.help").performClick();
        AlertDialog help = latest();
        ShadowLooper.idleMainLooper();
        EditText search = help.getWindow().getDecorView().findViewWithTag("help.search");
        search.setText("GET");
        HelpContent.Section section = HelpContent.search("GET").get(0);
        help.getWindow().getDecorView().findViewWithTag("help.topic." + section.id).performClick();
        help.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        help.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        assertTrue(help.isShowing());
        assertNotNull(help.getWindow().getDecorView().findViewWithTag("help.search"));
        help.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertFalse(help.isShowing());
        assertTrue(about.isShowing());
    }

    @Test public void closingSettingsDismissesTheAboutAndHelpBranch() {
        AlertDialog help = openHelp();
        assertTrue(help.isShowing());
        screen.dismissChildPages();
        assertFalse(help.isShowing());
        assertTrue(ShadowDialog.getShownDialogs().stream().noneMatch(android.app.Dialog::isShowing));
    }

    private AlertDialog openHelp() {
        home.findViewWithTag("settings.category.ABOUT").performClick();
        AlertDialog about = latest();
        about.getWindow().getDecorView().findViewWithTag("about.help").performClick();
        assertTrue(about.isShowing());
        ShadowLooper.idleMainLooper();
        return latest();
    }

    private static AlertDialog latest() { return (AlertDialog) ShadowDialog.getLatestDialog(); }

    private static final class MemoryStore implements SettingsStore {
        FeatureSettings settings = FeatureSettings.defaults();
        final Map<String, Object> values = new HashMap<>();
        public FeatureSettings load() { return settings; }
        public void save(FeatureSettings value) { settings = value; }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public boolean isInHost() { return true; }
    }
}
