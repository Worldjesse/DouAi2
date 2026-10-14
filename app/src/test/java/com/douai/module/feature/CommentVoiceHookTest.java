package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public final class CommentVoiceHookTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void replacementPreservesSynthesizedSpeechButStillReplacesUserRecordings() throws Throwable {
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        try (CommentVoiceHook hook = CommentVoiceHook.install(framework.api, report(), getClass().getClassLoader(),
                new LocalOptions(Map.of("comment_voice_replace", true)), ignored -> { })) {
            File replacement = temporary.newFile("replacement.audio");
            Class<?> prepared = Class.forName(CommentVoiceHook.class.getName() + "$Prepared");
            Constructor<?> constructor = prepared.getDeclaredConstructor(File.class, long.class);
            constructor.setAccessible(true);
            HostTargets.field(CommentVoiceHook.class, "prepared").set(hook, constructor.newInstance(replacement, 1000L));
            Method upload = Upload.class.getMethod("upload", Object.class, String.class);
            for (String folder : new String[]{"douai-tts", "douai-private-voice"}) {
                File speech = new File(temporary.newFolder(folder), "voice.m4a");
                assertEquals(speech.getAbsolutePath(), framework.invoke(upload, null, new Object(), speech.getAbsolutePath()));
            }
            assertEquals(replacement.getAbsolutePath(), framework.invoke(upload, null, new Object(),
                    temporary.newFile("recording.m4a").getAbsolutePath()));
        }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "comment_audio_upload", Upload.class.getMethod("upload", Object.class, String.class));
        add(rules, "comment_uploader_path", Upload.class.getMethod("path", String.class));
        add(rules, "comment_uploader_listener", Upload.class.getMethod("listener", Object.class));
        add(rules, "comment_audio_notify", Upload.class.getMethod("notify", int.class, int.class, Object.class));
        return new HostScanReport("fixture", rules, false);
    }

    private static void add(List<RuleScanResult> rules, String id, Method method) {
        rules.add(new RuleScanResult(id, "comment", Collections.singletonList(new HostCandidate("fixture.apk", "classes.dex",
                HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }

    public static final class Upload {
        public static String upload(Object state, String path) { return path; }
        public void path(String path) { }
        public void listener(Object listener) { }
        public void notify(int status, int extra, Object info) { }
    }
}
