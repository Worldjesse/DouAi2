package com.douai.module.feature;

import android.app.AlertDialog;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Shared navigation for behavior tests; visual tests exercise the real Compose controls. */
final class OfficialDownloadTestUi {
    private OfficialDownloadTestUi() { }
    static AlertDialog latest() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        return dialog;
    }
    static int indexOf(String label) {
        for (int i = 0; i < latest().getListView().getCount(); i++) {
            if (label.equals(latest().getListView().getAdapter().getItem(i))) return i;
        }
        return -1;
    }
    static int groupIndex(String name) {
        for (int i = 0; i < latest().getListView().getCount(); i++) {
            String label = String.valueOf(latest().getListView().getAdapter().getItem(i));
            if (label.equals(name) || label.startsWith(name + "（")) return i;
        }
        return -1;
    }
    static boolean hasGroup(String name) { return groupIndex(name) >= 0; }
    static void openGroup(String name) {
        int index = groupIndex(name);
        assertTrue("Missing media group: " + name, index >= 0);
        latest().getListView().performItemClick(null, index, index);
        ShadowLooper.idleMainLooper();
    }
    static void back() {
        assertNotNull(latest().getButton(AlertDialog.BUTTON_NEUTRAL));
        latest().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
    }
    static void chooseAction(String label) {
        int index = indexOf(label);
        if (index < 0) {
            openGroup(label.contains("实况") ? "实况" : label.contains("动态视频") ? "动态视频"
                    : label.contains("图片") ? "图片" : label.contains("音频") ? "音频" : "视频");
            index = indexOf(label);
        }
        assertTrue("Missing save action: " + label, index >= 0);
        latest().getListView().performItemClick(null, index, index);
        ShadowLooper.idleMainLooper();
    }
}
