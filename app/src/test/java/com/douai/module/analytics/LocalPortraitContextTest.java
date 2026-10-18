package com.douai.module.analytics;

import android.content.Context;
import android.content.ContextWrapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class LocalPortraitContextTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    @Before public void setup() throws Exception {
        File noBackup = temporary.newFolder("no-backup");
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getNoBackupFilesDir() { return noBackup; }
        };
    }
    @Test public void emptyReadDoesNotCreateFilesOrImaginedIdentity() {
        assertEquals("", LocalPortraitContext.current(context).purpose);
        assertEquals(0, context.getNoBackupFilesDir().list().length);
    }
    @Test public void localPurposeAndFeedbackSurviveReopenAndCanBeCleared() throws Exception {
        LocalPortraitContext.setPurpose(context, "找做饭灵感");
        LocalPortraitContext.setFeedback(context, "部分符合");
        clearCache();
        assertEquals("找做饭灵感", LocalPortraitContext.current(context).purpose);
        assertEquals("部分符合", LocalPortraitContext.current(context).feedback);
        LocalPortraitContext.clear(context);
        clearCache();
        assertEquals("", LocalPortraitContext.current(context).purpose);
        assertFalse(new File(context.getNoBackupFilesDir(), "douai-analytics/portrait-context.json").exists());
    }
    @Test public void invalidInputDoesNotReplacePreviouslySavedContext() {
        LocalPortraitContext.setPurpose(context, "休息");
        try { LocalPortraitContext.setPurpose(context, "a".repeat(241)); fail(); } catch (IllegalArgumentException expected) { }
        try { LocalPortraitContext.setFeedback(context, "任意反馈"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals("休息", LocalPortraitContext.current(context).purpose);
    }
    @Test public void explicitClearCanRecoverCorruptedSelfDescription() throws Exception {
        LocalPortraitContext.setPurpose(context, "学习");
        Files.write(new File(context.getNoBackupFilesDir(), "douai-analytics/portrait-context.json").toPath(), new byte[]{1,2,3});
        clearCache();
        try { LocalPortraitContext.current(context); fail(); } catch (IllegalStateException expected) { }
        LocalPortraitContext.clear(context);
        assertEquals("", LocalPortraitContext.current(context).purpose);
    }
    private static void clearCache() throws Exception {
        Field field = LocalPortraitContext.class.getDeclaredField("STATES"); field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).clear();
    }
}
