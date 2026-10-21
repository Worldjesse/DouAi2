package com.douai.module.feature;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public final class CommentAudioDiagnosticsTest {
    @Test public void tracesPublishedMediaDisappearingAtTheSameCommentsRowWithoutLeakingPayload() {
        List<String> logs = new ArrayList<>();
        CommentAudioDiagnostics diagnostics = new CommentAudioDiagnostics(logs::add, "test");
        Model model = new Model();
        model.A.audio.vid = "private-upload-vid";
        CommentAudioDiagnostics.Sample sample = diagnostics.begin(model, "private-work", false);
        diagnostics.model(sample, "publish_before", model);
        Response response = new Response();
        diagnostics.response(sample, "success_before", response);
        response.comment.commentAudio = null;
        diagnostics.response(sample, "success_after", response);
        diagnostics.observe("row_bound", response.comment, "");
        String all = String.join("\n", logs);
        assertTrue(all.contains("stage=success_before; code=0; comment=true; audio=true"));
        assertTrue(all.contains("upload_vid_match=same"));
        assertTrue(all.contains("stage=success_after; code=0; comment=true; audio=false"));
        assertTrue(all.contains("stage=row_bound; audio=false"));
        for (String secret : new String[]{"private-work", "private-comment", "private-upload-vid", "private-text", "private-url"}) {
            assertFalse(secret, all.contains(secret));
        }
        // The observer never changes the DTO or supplies replacement media.
        assertNull(response.comment.commentAudio);
        assertEquals("private-upload-vid", model.A.audio.vid);
    }

    @Test public void distinguishesAMissingMediaFieldFromAnUnverifiedContract() {
        assertEquals("; audio=unknown", CommentAudioDiagnostics.summary(new Object()));
        Comment comment = new Comment();
        comment.commentAudio = null;
        assertEquals("; audio=false", CommentAudioDiagnostics.summary(comment));
        assertEquals("; audio=unknown", CommentAudioDiagnostics.summary(new WrongComment()));
    }

    @Test public void controlsAreLimitedToRepliesToTheOwnedCommentOnTheSameWork() {
        List<String> logs = new ArrayList<>();
        CommentAudioDiagnostics diagnostics = new CommentAudioDiagnostics(logs::add, "test");
        CommentAudioDiagnostics.Sample sample = diagnostics.begin(new Model(), "private-work", false);
        diagnostics.response(sample, "success_before", new Response());
        int count = logs.size();
        Comment unrelated = new Comment();
        unrelated.cid = "unrelated";
        diagnostics.observe("row_bound", unrelated, "");
        assertEquals(count, logs.size());
        Comment reply = new Comment();
        reply.cid = "private-reply";
        reply.replyId = "private-comment";
        reply.awemeId = "other-work";
        diagnostics.observe("row_bound", reply, "");
        assertEquals(count, logs.size());
        reply.awemeId = "private-work";
        diagnostics.observe("row_bound", reply, "");
        assertTrue(logs.get(logs.size() - 1).contains("sample=a1r1; kind=related_reply"));
        diagnostics.observe("row_bound", reply, "");
        assertEquals(count + 1, logs.size());
    }

    @Test public void samplesExpireAndEvictedIdsCannotAccumulateNewRowLogs() {
        List<String> logs = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        CommentAudioDiagnostics diagnostics = new CommentAudioDiagnostics(logs::add, "test", now::get);
        CommentAudioDiagnostics.Sample first = diagnostics.begin(new Model(), "private-work", false);
        diagnostics.response(first, "success_before", new Response());
        for (int index = 0; index < 8; index++) diagnostics.begin(new Model(), "private-work", false);
        int count = logs.size();
        diagnostics.observe("row_bound", new Comment(), "");
        assertEquals(count, logs.size());
        CommentAudioDiagnostics.Sample recent = diagnostics.begin(new Model(), "private-work", true);
        diagnostics.response(recent, "success_before", new Response());
        now.set(15 * 60_000L);
        count = logs.size();
        diagnostics.observe("row_bound", new Comment(), "");
        diagnostics.response(recent, "success_after", new Response());
        assertEquals(count, logs.size());
    }

    @Test public void metadataLoggingIsBoundedAndCloseStopsObservation() {
        List<String> logs = new ArrayList<>();
        CommentAudioDiagnostics diagnostics = new CommentAudioDiagnostics(logs::add, "test");
        CommentAudioDiagnostics.Sample sample = diagnostics.begin(new Model(), "private-work", false);
        Response response = new Response();
        diagnostics.response(sample, "success_before", response);
        for (int index = 0; index < 100; index++) {
            response.comment.commentAudio.e = (long) index;
            diagnostics.observe("row_bound", response.comment, "");
        }
        assertEquals(48, logs.size());
        diagnostics.close();
        diagnostics.observe("row_bound", response.comment, "");
        assertEquals(48, logs.size());
        assertNull(diagnostics.begin(new Model(), "private-work", false));
        // A broken logging consumer must not interrupt publishing.
        CommentAudioDiagnostics broken = new CommentAudioDiagnostics(ignored -> { throw new IllegalStateException(); }, "test");
        broken.response(broken.begin(new Model(), "private-work", false), "success_before", response);
    }

    @Test public void identifiesFileHeadersWithoutRewritingOrTrustingTheirExtensions() throws Exception {
        byte[][] headers = { {'I','D','3',0,0,0,0,0,0,0,0,0},
                {(byte)255,(byte)241,0,0,0,0,0,0,0,0,0,0},
                {0,0,0,20,'f','t','y','p','M','4','A',' '},
                {'R','I','F','F',0,0,0,0,'W','A','V','E'} };
        String[] expected = {"id3_audio", "aac_adts", "mp4", "wav"};
        File file = File.createTempFile("voice-probe", ".mp3");
        try {
            for (int index = 0; index < headers.length; index++) {
                Files.write(file.toPath(), headers[index]);
                assertEquals(expected[index], CommentAudioDiagnostics.container(file));
                assertArrayEquals(headers[index], Files.readAllBytes(file.toPath()));
            }
        } finally { Files.deleteIfExists(file.toPath()); }
    }

    public static final class Response { public int status_code; public Comment comment = new Comment(); }
    public static final class Comment {
        public String cid = "private-comment", awemeId = "private-work", replyId, text = "private-text";
        public Integer contentType = 0;
        public Audio commentAudio = new Audio();
    }
    public static final class Audio {
        public String a = "private-upload-vid", b = "https://private-url/?key=secret", d = "0,1,0";
        public Long e = 2400L;
        public Integer f = 1;
        public String g = "private-text";
        public boolean isLocal, isFolded;
    }
    public static final class Model { public Attachment A = new Attachment(); }
    public static final class Attachment { public Upload audio = new Upload(); }
    public static final class Upload {
        public String vid;
        public File localFile;
        public List<Float> waveList = new ArrayList<>();
        public Long duration = 2400L;
    }
    public static final class WrongComment { public WrongAudio commentAudio = new WrongAudio(); }
    public static final class WrongAudio { public String a, b, d, e; }
}
