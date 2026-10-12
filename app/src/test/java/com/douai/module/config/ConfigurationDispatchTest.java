package com.douai.module.config;

import android.app.Application;
import android.content.SharedPreferences;

import com.douai.module.DouAiModule;
import com.douai.module.feature.FeedGestureToolsHook;
import com.douai.module.feature.FollowListPager;
import com.douai.module.feature.NativeFollowLists;
import com.douai.module.feature.PlaybackBehaviorHooks;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigurationDispatchTest {
    @Test public void changingOnlyPlaybackSpeedUpdatesPlaybackAndGestureSnapshots() throws Exception {
        DouAiModule module = module();
        Map<String, Object> values = new HashMap<>();
        values.put("playback_speed", "2");
        LocalOptions initial = new LocalOptions(values);
        try (FeedGestureToolsHook gesture = gesture(initial);
             PlaybackBehaviorHooks playback = playback(initial)) {
            setField(module, "feedGestureToolsHook", gesture);
            setField(module, "playbackBehaviorHooks", playback);
            update(module, values);
            LocalOptions previous = options(gesture);
            assertSame(previous, options(playback));

            values.put("playback_speed", "3");
            update(module, values);
            LocalOptions updated = (LocalOptions) field(module, "localOptions");
            assertNotSame(previous, updated);
            assertSame(updated, options(gesture));
            assertSame(updated, options(playback));
            assertEquals(3f, options(gesture).decimal("playback_speed", 2f), 0f);
            assertEquals(3f, options(playback).decimal("playback_speed", 2f), 0f);

            values.put("reply_tip", true);
            update(module, values);
            assertSame(updated, options(gesture));
            assertSame(updated, options(playback));
        } finally {
            ShadowLooper.idleMainLooper();
        }
    }

    @Test public void changingOnlyReplyScopeModeUpdatesRelationshipSnapshot() throws Exception {
        assertRelationshipDispatch("reply_scope_mode", "all", "whitelist");
    }

    @Test public void changingOnlyCommentScopeModeUpdatesRelationshipSnapshot() throws Exception {
        assertRelationshipDispatch("comment_scope_mode", "all", "blacklist");
    }

    @Test public void changingOnlyPrivateChatParticipationUpdatesRelationshipSnapshot() throws Exception {
        assertRelationshipDispatch("reply_private_chats", false, true);
    }

    @Test public void changingOnlyLegacyReplyGlobalUpdatesRelationshipSnapshot() throws Exception {
        assertRelationshipDispatch("reply_global", true, false);
    }

    private static void assertRelationshipDispatch(String key, Object before, Object after) throws Exception {
        DouAiModule module = module();
        Map<String, Object> values = new HashMap<>();
        values.put("reply_scope_relations", "following");
        values.put("comment_scope_relations", "followers");
        values.put(key, before);
        try (NativeFollowLists relationships = relationships()) {
            setField(module, "nativeFollowLists", relationships);
            update(module, values);
            LocalOptions previous = options(relationships);
            assertSame(field(module, "localOptions"), previous);

            values.put(key, after);
            update(module, values);
            LocalOptions updated = (LocalOptions) field(module, "localOptions");
            assertNotSame(previous, updated);
            assertSame(key, updated, options(relationships));
            if (after instanceof Boolean) assertEquals(after, options(relationships).enabled(key));
            else assertEquals(after, options(relationships).text(key, ""));
            assertEquals("following", options(relationships).text("reply_scope_relations", ""));
            assertEquals("followers", options(relationships).text("comment_scope_relations", ""));

            values.put("playback_speed", "3");
            update(module, values);
            assertSame(updated, options(relationships));
        }
    }

    private static FeedGestureToolsHook gesture(LocalOptions options) throws Exception {
        Constructor<FeedGestureToolsHook> constructor = FeedGestureToolsHook.class.getDeclaredConstructor(
                Application.class, LocalOptions.class, Consumer.class, Method.class,
                Field.class, Field.class, Field.class, Class.class);
        constructor.setAccessible(true);
        return constructor.newInstance(RuntimeEnvironment.getApplication(), options,
                (Consumer<String>) ignored -> { }, null, null, null, null, null);
    }

    private static PlaybackBehaviorHooks playback(LocalOptions options) throws Exception {
        Constructor<PlaybackBehaviorHooks> constructor = PlaybackBehaviorHooks.class.getDeclaredConstructor(
                LocalOptions.class, Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(options, (Consumer<String>) ignored -> { });
    }

    private static NativeFollowLists relationships() throws Exception {
        Class<?> accountSource = Class.forName(NativeFollowLists.class.getName() + "$AccountSource");
        Object accounts = Proxy.newProxyInstance(accountSource.getClassLoader(), new Class<?>[]{accountSource},
                (proxy, method, arguments) -> { throw new AssertionError("Unexpected account lookup"); });
        FollowListPager.Fetcher fetcher = (kind, query) -> { throw new AssertionError("Unexpected host fetch"); };
        Constructor<NativeFollowLists> constructor = NativeFollowLists.class.getDeclaredConstructor(
                accountSource, FollowListPager.Fetcher.class, Consumer.class);
        constructor.setAccessible(true);
        NativeFollowLists relationships = constructor.newInstance(accounts, fetcher,
                (Consumer<String>) ignored -> { });
        ScheduledThreadPoolExecutor original = (ScheduledThreadPoolExecutor) field(relationships, "worker");
        original.shutdownNow();
        // Exercise real setOptions dispatch without starting host account or network work.
        setField(relationships, "worker", new ScheduledThreadPoolExecutor(1) {
            @Override public void execute(Runnable command) { }
        });
        return relationships;
    }

    private static DouAiModule module() {
        DouAiModule module = new DouAiModule();
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, method, arguments) -> null);
        module.attachFramework(framework, () -> { });
        return module;
    }

    private static void update(DouAiModule module, Map<String, Object> values) throws Exception {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if ("getAll".equals(method.getName())) return new HashMap<>(values);
                    throw new AssertionError("Live getter used after capture: " + method.getName());
                });
        Method update = DouAiModule.class.getDeclaredMethod("updateConfiguration", SharedPreferences.class);
        update.setAccessible(true);
        update.invoke(module, preferences);
    }

    private static LocalOptions options(Object target) throws Exception {
        return (LocalOptions) field(target, "options");
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
