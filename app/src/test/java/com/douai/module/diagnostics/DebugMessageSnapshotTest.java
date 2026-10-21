package com.douai.module.diagnostics;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public final class DebugMessageSnapshotTest {
    private static Path fixtureDirectory;
    private static URLClassLoader fixtureLoader;

    @BeforeClass public static void compileNamedHostDtos() throws Exception {
        fixtureDirectory = Files.createTempDirectory("douai-message-dtos-");
        String[][] definitions = {
                {"com.bytedance.im.core.model.Message", "public String id; public String content=\"{\\\"url\\\":\\\"fixture\\\"}\"; public Message(String id){this.id=id;} public String toString(){throw new AssertionError(\"No toString\");}"},
                {"com.ss.android.ugc.aweme.im.sdk.chat.model.AudioContent", "public String id; public long duration=2117; public AudioContent(String id){this.id=id;}"},
                {"com.ss.android.ugc.aweme.im.business.mediaselectpage.sender.audio.model.EncryptAudioNewContent", "extends com.ss.android.ugc.aweme.im.sdk.chat.model.AudioContent", "public EncryptAudioNewContent(String id){super(id);}"}
        };
        List<String> sources = new ArrayList<>();
        for (String[] definition : definitions) {
            String name = definition[0], simple = name.substring(name.lastIndexOf('.') + 1);
            String parent = definition.length == 3 ? " " + definition[1] : "";
            Path source = fixtureDirectory.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package " + name.substring(0, name.lastIndexOf('.')) + "; public class "
                    + simple + parent + " {" + definition[definition.length - 1] + "}", StandardCharsets.UTF_8);
            sources.add(source.toString());
        }
        // Android's compile bootclasspath omits javax.tools; the unit test still runs on a full JDK.
        Object compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null);
        assertNotNull("Host fixture compilation requires the test JDK", compiler);
        List<String> command = new ArrayList<>(Arrays.asList("-encoding", "UTF-8", "-d", fixtureDirectory.toString()));
        command.addAll(sources);
        Object exit = Class.forName("javax.tools.Tool").getMethod("run", java.io.InputStream.class,
                java.io.OutputStream.class, java.io.OutputStream.class, String[].class)
                .invoke(compiler, null, null, null, command.toArray(new String[0]));
        assertEquals(0, ((Number) exit).intValue());
        fixtureLoader = new URLClassLoader(new java.net.URL[]{fixtureDirectory.toUri().toURL()},
                DebugMessageSnapshotTest.class.getClassLoader());
    }

    @AfterClass public static void deleteNamedHostDtos() throws Exception {
        if (fixtureLoader != null) fixtureLoader.close();
        if (fixtureDirectory != null) {
            try (java.util.stream.Stream<Path> files = Files.walk(fixtureDirectory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(file);
            }
        }
    }

    @Test public void capturesExplicitOwnerArgumentsResultAndEncryptedAudioInheritance() throws Exception {
        List<Object> captured = new ArrayList<>(); List<String> logs = new ArrayList<>();
        DebugMessageSnapshot snapshot = snapshot(logs, () -> 0, value -> { captured.add(value); return json(value); });
        Object owner = message("owner"), argument = audio("argument"), result = message("result");
        Object encrypted = dto("com.ss.android.ugc.aweme.im.business.mediaselectpage.sender.audio.model.EncryptAudioNewContent", "encrypted");
        snapshot.capture("typed", "after", owner, Arrays.asList(argument, encrypted), result);
        assertEquals(Arrays.asList(owner, result, argument, encrypted), captured);
        assertEquals(4, logs.size());
        assertTrue(logs.stream().allMatch(line -> line.startsWith("[DouAi.MessageJson] hook=typed stage=after json={")));
    }

    @Test public void listsYieldAtMostTwoTypedMessagesAcrossAllBoundaries() throws Exception {
        List<Object> captured = new ArrayList<>(); List<String> logs = new ArrayList<>();
        DebugMessageSnapshot snapshot = snapshot(logs, () -> 0, value -> { captured.add(value); return json(value); });
        Object first = message("first"), second = message("second"), third = message("third");
        snapshot.capture("list", "before", null, Arrays.asList(Arrays.asList("not a DTO", first, first, audio("not-list-audio"), second, third),
                Collections.singletonList(third)), null);
        assertEquals(Arrays.asList(first, second), captured); assertEquals(2, logs.size());
        List<Object> longList = new ArrayList<>(Collections.nCopies(16, "unrelated")); longList.add(third);
        snapshot.capture("bounded_scan", "before", null, Collections.singletonList(longList), null);
        assertEquals(2, captured.size());
    }

    @Test public void neverTraversesWrappersMapsArraysOrCallsToString() throws Exception {
        AtomicInteger calls = new AtomicInteger(); List<String> logs = new ArrayList<>(); Object message = message("private");
        DebugMessageSnapshot snapshot = snapshot(logs, () -> 0, value -> { calls.incrementAndGet(); return "{}"; });
        snapshot.capture("unrelated", "before", new Wrapper(message), Arrays.asList(new Wrapper(message),
                Collections.singletonMap("message", message), new Object[]{message}, new Poison(),
                Collections.singletonList(new Wrapper(message))), new Wrapper(message));
        assertEquals(0, calls.get()); assertTrue(logs.isEmpty());
    }

    @Test public void duplicateObjectIsSerializedOnceAndCaptureHasAnOverallBound() throws Exception {
        List<Object> captured = new ArrayList<>(); List<String> logs = new ArrayList<>(); Object same = message("same");
        DebugMessageSnapshot snapshot = snapshot(logs, () -> 0, value -> { captured.add(value); return json(value); });
        snapshot.capture("identity", "before", same, Arrays.asList(same, Collections.singletonList(same)), same);
        assertEquals(1, captured.size());
        List<Object> arguments = new ArrayList<>(); for (int i = 0; i < 20; i++) arguments.add(message("arg" + i));
        snapshot.capture("bounded", "after", message("owner"), arguments, message("result"));
        assertEquals(7, captured.size());
        assertEquals("owner", captured.get(1).getClass().getField("id").get(captured.get(1)));
        assertEquals("result", captured.get(2).getClass().getField("id").get(captured.get(2)));
    }

    @Test public void disabledModeDoesNotInspectContainersOrSerialize() throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger(); List<String> logs = new ArrayList<>();
        DebugMessageSnapshot snapshot = new DebugMessageSnapshot(enabled::get, logs::add, () -> 0,
                value -> { calls.incrementAndGet(); return json(value); });
        List<?> poison = new java.util.AbstractList<Object>() {
            @Override public Object get(int index) { throw new AssertionError("No inspection while disabled"); }
            @Override public int size() { throw new AssertionError("No inspection while disabled"); }
        };
        snapshot.capture("disabled", "before", message("disabled"), poison, null);
        assertEquals(0, calls.get()); assertTrue(logs.isEmpty());
        enabled.set(true); snapshot.capture("enabled", "before", audio("enabled"), Collections.emptyList(), null);
        assertEquals(1, calls.get()); assertEquals(1, logs.size());
    }

    @Test public void hostGsonSerializesFullDtoAndEmbeddedContentWithoutCallingToString() throws Exception {
        List<String> logs = new ArrayList<>();
        new DebugMessageSnapshot(() -> true, logs::add, () -> 0)
                .capture("real_gson", "after", message("fixture"), Collections.emptyList(), null);
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("\"id\":\"fixture\""));
        assertTrue(logs.get(0).contains("\"content\":\"{\\\"url\\\":\\\"fixture\\\"}\""));
    }

    @Test public void independentAudioQuotaSurvivesUiNoiseAndPhasesDeduplicateSeparately() throws Exception {
        List<String> logs = new ArrayList<>(); AtomicLong now = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
        DebugMessageSnapshot snapshot = snapshot(logs, now::get, value -> { calls.incrementAndGet(); return json(value); });
        for (int i = 0; i < 30; i++) snapshot.capture("ui." + i, "after", message("ui" + i), Collections.emptyList(), null);
        assertEquals(8, logs.size()); assertEquals(20, calls.get());
        Object sent = message("sent");
        snapshot.capture("DouAi.AutoAudio.1", "before", sent, Collections.emptyList(), null);
        snapshot.capture("DouAi.AutoAudio.1", "after", sent, Collections.emptyList(), null);
        snapshot.capture("DouAi.PrivateSpeech.Queue", "before", null, Collections.singletonList(audio("queued")), null);
        assertEquals(11, logs.size());
        now.set(600_000_000L); snapshot.capture("DouAi.AutoAudio.1", "after", sent, Collections.emptyList(), null);
        assertEquals(11, logs.size());
        now.set(5_000_000_000L); snapshot.capture("another", "before", message("later"), Collections.emptyList(), null);
        assertTrue(logs.stream().anyMatch(line -> line.contains("dropped=")));
    }

    @Test public void failuresExposeOnlyExceptionClassAndOversizeNeverEmitsBrokenJson() throws Exception {
        List<String> logs = new ArrayList<>(); Object message = message("fixture");
        snapshot(logs, () -> 0, value -> { throw new InvocationTargetException(new IllegalStateException("PRIVATE SECRET")); })
                .capture("failure", "before", message, Collections.emptyList(), null);
        assertEquals(1, logs.size()); assertTrue(logs.get(0).contains("unavailable=java.lang.IllegalStateException"));
        assertFalse(logs.get(0).contains("PRIVATE SECRET"));
        snapshot(logs, () -> 0, value -> "x".repeat(256 * 1024 + 1))
                .capture("large", "before", message, Collections.emptyList(), null);
        assertEquals(2, logs.size()); assertTrue(logs.get(1).contains("unavailable=oversize chars=262145"));
        assertFalse(logs.get(1).contains(" json="));
        new DebugMessageSnapshot(() -> true, line -> { throw new AssertionError("sink"); }, () -> 0, value -> "{}")
                .capture("sink", "before", message, Collections.emptyList(), null);
    }

    @Test public void recursionIsSuppressedAndClearResetsIdenticalSnapshots() throws Exception {
        List<String> logs = new ArrayList<>(); Object message = message("fixture"); AtomicInteger calls = new AtomicInteger();
        DebugMessageSnapshot[] holder = new DebugMessageSnapshot[1];
        holder[0] = snapshot(logs, () -> 0, value -> {
            calls.incrementAndGet(); holder[0].capture("nested", "before", value, Collections.emptyList(), null); return "{}";
        });
        holder[0].capture("outer", "before", message, Collections.emptyList(), null);
        assertEquals(1, calls.get()); assertEquals(1, logs.size());
        holder[0].reset(); holder[0].capture("outer", "before", message, Collections.emptyList(), null);
        assertEquals(2, calls.get()); assertEquals(2, logs.size());
    }

    @Test public void clearDuringSerializationDiscardsTheOldEpoch() throws Exception {
        List<String> logs = Collections.synchronizedList(new ArrayList<>()); Object message = message("fixture");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        DebugMessageSnapshot snapshot = snapshot(logs, () -> 0, value -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); release.await(1, TimeUnit.SECONDS); } return "{}";
        });
        Thread older = new Thread(() -> snapshot.capture("row", "before", message, Collections.emptyList(), null)); older.start();
        try { assertTrue(entered.await(1, TimeUnit.SECONDS)); snapshot.reset(); } finally { release.countDown(); }
        older.join(1_000); assertFalse(older.isAlive()); assertTrue(logs.isEmpty());
        snapshot.capture("row", "before", message, Collections.emptyList(), null);
        assertEquals(1, logs.size()); assertEquals(2, calls.get());
    }

    private static DebugMessageSnapshot snapshot(List<String> logs, java.util.function.LongSupplier clock,
                                                 DebugMessageSnapshot.Serializer serializer) {
        return new DebugMessageSnapshot(() -> true, logs::add, clock, serializer);
    }
    private static Object message(String id) throws Exception { return dto("com.bytedance.im.core.model.Message", id); }
    private static Object audio(String id) throws Exception { return dto("com.ss.android.ugc.aweme.im.sdk.chat.model.AudioContent", id); }
    private static Object dto(String type, String id) throws Exception {
        return Class.forName(type, true, fixtureLoader).getConstructor(String.class).newInstance(id);
    }
    private static String json(Object value) throws Exception {
        return "{\"id\":\"" + value.getClass().getField("id").get(value) + "\"}";
    }
    private static final class Wrapper { final Object message; Wrapper(Object value) { message = value; } }
    private static final class Poison { @Override public String toString() { throw new AssertionError("No toString"); } }
}
