package com.douai.module.diagnostics;

import org.junit.Test;

import static org.junit.Assert.*;

public final class DebugLogViewTest {
    @Test public void unfilteredViewPreservesEveryByteAndKeepsStackContinuationWithItsEvent() {
        String failure = line(1, "Hook call threw; hook=audio; exception=java.lang.IllegalStateException")
                + " at fixture.Audio.run(Audio.java:12)\nCaused by: fixture.Failure\n";
        String sent = line(2, "Automatic reply outcome: sent");
        DebugLogView view = DebugLogView.parse(failure + sent);
        DebugLogView.Selection all = view.select(DebugLogView.Category.ALL, "");
        assertEquals(failure + sent, all.text); assertEquals(2, all.entries.size());
        assertEquals(failure, all.entries.get(0).text);
        DebugLogView.Selection stack = view.select(DebugLogView.Category.RESULTS, "Audio.java:12");
        assertEquals(1, stack.entries.size()); assertEquals(failure, stack.text);
    }

    @Test public void snapshotContentDoesNotInventSuccessOrFailures() {
        String message = line(1, "[DouAi.MessageJson] hook=DouAi.AutoAudio.1 stage=after json={\"content\":\"outcome: failed\",\"message\":\"success\"}");
        String comment = line(2, "[DouAi.CommentJson] hook=row stage=before json={\"text\":\"Automatic reply outcome: sent\",\"stage\":\"failed\"}");
        DebugLogView view = DebugLogView.parse(message + comment);
        assertTrue(view.select(DebugLogView.Category.RESULTS, "").entries.isEmpty());
        DebugLogView.Selection json = view.select(DebugLogView.Category.JSON, "");
        assertEquals(2, json.entries.size()); assertTrue(json.summary.contains("成功 0 · 失败 0"));
        assertTrue(json.summary.endsWith("JSON 2"));
    }

    @Test public void outcomeCountersUseTheActualAutomaticLogProtocol() {
        String text = line(1, "Automatic reply outcome: sent")
                + line(2, "Automatic comment_reply outcome: submitted")
                + line(3, "Automatic reply outcome: failed")
                + line(4, "Automatic comment native callback: sent; code=0; media=audio")
                + line(5, "Automatic reply outcome: cancelled")
                + line(6, "[DouAi.Trace] hook=DouAi.VersionInfo.int outcome=package_not_found elapsedUs=5");
        DebugLogView.Selection selected = DebugLogView.parse(text).select(DebugLogView.Category.RESULTS, "");
        assertEquals(6, selected.entries.size());
        assertEquals("显示 6 / 6 条 · 成功 2 · 失败 1 · 待确认 1 · 取消 1 · JSON 0", selected.summary);
        assertEquals(DebugLogView.Result.SKIPPED, selected.entries.get(5).result);
    }

    @Test public void categoriesCoverMessagingCommentRecallAndOverlayMetadata() {
        String comment = line(1, "Automatic comment selection skipped: disabled");
        String chat = line(2, "[DouAi.Trace] hook=DouAi.Advanced.prevent_message_recall.1 result=java.lang.Boolean(false)");
        String overlay = line(3, "[DouAi.ReplyOverlay] state=show phase=speech count=1");
        String other = line(4, "Selected host profile 40.8.0");
        DebugLogView view = DebugLogView.parse(comment + chat + overlay + other);
        assertEquals(comment, view.select(DebugLogView.Category.COMMENT, "").text);
        assertEquals(chat + overlay, view.select(DebugLogView.Category.CHAT, "").text);
        assertEquals(chat + overlay, view.select(DebugLogView.Category.HOOK, "").text);
        assertEquals(4, view.select(DebugLogView.Category.ALL, "").entries.size());
        assertEquals(DebugLogView.Result.NONE, view.select(DebugLogView.Category.CHAT, "false").entries.get(0).result);
    }

    @Test public void searchingUsesCaseInsensitiveLiteralTextAcrossFullJsonAndPreservesOrder() {
        String a = line(1, "[DouAi.MessageJson] hook=queue stage=before json={\"audio_url\":\"fixture-A[1]\"}");
        String b = line(2, "[DouAi.CommentJson] hook=row stage=after json={\"audio_vid\":\"fixture-B\"}");
        DebugLogView view = DebugLogView.parse(a + b);
        assertEquals(a, view.select(DebugLogView.Category.JSON, " FIXTURE-a[1] ").text);
        assertEquals(a + b, view.select(DebugLogView.Category.JSON, "audio_").text);
        assertTrue(view.select(DebugLogView.Category.JSON, "(.*)").entries.isEmpty());
        assertTrue(view.select(DebugLogView.Category.CHAT, "fixture-B").entries.isEmpty());
    }

    @Test public void partialTailIsRetainedAndHeaderLikeJsonEscapesDoNotSplitAnEntry() {
        String partial = "（较早内容已省略）\npartial stack\n";
        String json = line(1, "[DouAi.CommentJson] hook=row stage=after json={\"text\":\"2026-10-22 01:00:00.000 D/DouAi [detail] escaped\\ntext\"}");
        DebugLogView.Selection all = DebugLogView.parse(partial + json).select(DebugLogView.Category.ALL, "");
        assertEquals(partial + json, all.text); assertEquals(2, all.entries.size());
        assertEquals(json, DebugLogView.parse(partial + json).select(DebugLogView.Category.JSON, "").text);
    }

    @Test public void unavailableSnapshotIsAnAttentionEventRatherThanAJsonSnapshot() {
        String unavailable = line(1, "[DouAi.MessageJson] hook=queue stage=before unavailable=oversize chars=262145");
        DebugLogView view = DebugLogView.parse(unavailable);
        assertTrue(view.select(DebugLogView.Category.JSON, "").entries.isEmpty());
        assertEquals(unavailable, view.select(DebugLogView.Category.RESULTS, "").text);
        assertEquals(unavailable, view.select(DebugLogView.Category.CHAT, "").text);
    }

    @Test public void entryTitlesExposeStageAndHookWithoutDumpingJsonIntoTheChooser() {
        DebugLogView.Selection selected = DebugLogView.parse(line(1,
                "[DouAi.MessageJson] hook=DouAi.PrivateSpeech.Queue stage=after json={\"text\":\"BODY-ONLY\"}"))
                .select(DebugLogView.Category.ALL, "");
        String title = selected.entries.get(0).title();
        assertTrue(title.contains("JSON")); assertTrue(title.contains("DouAi.PrivateSpeech.Queue"));
        assertTrue(title.contains("after")); assertFalse(title.contains("BODY-ONLY"));
    }

    @Test public void emptyLogPlaceholdersDoNotBecomeFakeEvents() {
        assertTrue(DebugLogView.parse(null).select(null, null).entries.isEmpty());
        DebugLogView.Selection empty = DebugLogView.parse("暂无调试日志").select(DebugLogView.Category.ALL, "");
        assertTrue(empty.entries.isEmpty()); assertEquals("暂无调试日志", empty.text);
        assertTrue(empty.summary.startsWith("显示 0 / 0 条"));
    }

    private static String line(int second, String text) {
        return String.format(java.util.Locale.ROOT, "2026-10-22 00:00:%02d.000 D/DouAi [detail] %s\n", second, text);
    }
}
