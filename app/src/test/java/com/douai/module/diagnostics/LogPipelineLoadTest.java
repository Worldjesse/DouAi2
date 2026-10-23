package com.douai.module.diagnostics;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class LogPipelineLoadTest {
    @Test public void actualFacadeKeepsEveryAcceptedCriticalTraceWhileBothNoiseSourcesAreSaturated() throws Exception {
        assertTrue(DouAiLog.awaitIdle(5000));
        boolean oldMode = DebugLog.isEnabled(); DebugLog.setEnabled(true);
        Field storeField = DebugLog.class.getDeclaredField("store"); storeField.setAccessible(true);
        Object old = storeField.get(null); File directory = Files.createTempDirectory("douai-whole-pipeline").toFile();
        CountDownLatch sinkEntered = new CountDownLatch(1), releaseSink = new CountDownLatch(1), releaseLegacy = new CountDownLatch(1);
        try (DebugLogStore store = new DebugLogStore(directory)) {
            storeField.set(null, store);
            Field workerField = DebugLogStore.class.getDeclaredField("worker"); workerField.setAccessible(true);
            ThreadPoolExecutor legacy = (ThreadPoolExecutor) workerField.get(store);
            CountDownLatch legacyEntered = new CountDownLatch(1);
            legacy.execute(() -> { legacyEntered.countDown(); try { releaseLegacy.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } });
            assertTrue(legacyEntered.await(3, TimeUnit.SECONDS));
            DouAiLog.setFrameworkSink((level, json) -> {
                sinkEntered.countDown(); try { releaseSink.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            });
            DouAiLog.event("pipeline_noise", "first", "fixture", ""); assertTrue(sinkEntered.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 600; i++) store.record(true, 3, "legacy-noise", "index=" + i, null);
            for (int i = 0; i < 500; i++) DouAiLog.event("pipeline_noise", "observation", "changing_sample", "index=" + i);
            List<String> traces = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                DouAiLog.Operation operation = DouAiLog.begin("pipeline_business", "fixture"); traces.add(operation.traceId());
                operation.stage("apply", "target_present=true");
                operation.finish(DouAiLog.Outcome.SUCCESS, "fixture_result_verified", "result_scope=fixture_only");
            }
            releaseSink.countDown(); assertTrue(DouAiLog.awaitIdle(10000)); releaseLegacy.countDown();
            CountDownLatch read = new CountDownLatch(1); AtomicReference<String> text = new AtomicReference<>();
            store.read(DebugLogStore.TOTAL_BYTES, true, value -> { text.set(value); read.countDown(); });
            assertTrue(read.await(5, TimeUnit.SECONDS)); Set<String> starts = new HashSet<>(), stages = new HashSet<>(), ends = new HashSet<>();
            for (String line : text.get().split("\n")) {
                int json = line.indexOf('{'); if (json < 0) continue;
                JSONObject event = new JSONObject(line.substring(json));
                if (!"pipeline_business".equals(event.optString("feature_id"))) continue;
                String trace = event.getString("trace_id");
                switch (event.getString("event")) {
                    case "operation_started": assertTrue(starts.add(trace)); break;
                    case "stage": stages.add(trace); break;
                    case "operation_finished": assertTrue(ends.add(trace)); break;
                }
            }
            assertEquals(new HashSet<>(traces), starts); assertEquals(starts, stages); assertEquals(starts, ends);
            JSONObject integrity = new JSONObject(store.integrity()); assertEquals(0, integrity.getLong("io_failures"));
            assertTrue(integrity.getLong("lost_count") > 0); // The separately bounded legacy queue deliberately overflowed.
            assertTrue(new JSONObject(DouAiLog.manifest()).getJSONObject("loss_details").getLong("count") > 0);
        } finally {
            releaseSink.countDown(); releaseLegacy.countDown(); DouAiLog.awaitIdle(5000);
            DouAiLog.setFrameworkSink(null); storeField.set(null, old);
            DebugLog.setEnabled(oldMode);
        }
    }
}
