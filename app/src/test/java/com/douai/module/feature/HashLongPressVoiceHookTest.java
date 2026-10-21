package com.douai.module.feature;

import android.content.Context;
import android.view.View;
import android.widget.EditText;

import com.douai.module.config.LocalOptions;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HashLongPressVoiceHookTest {
    @Test public void nonHashDelegatesToTheOriginalNativeLongListenerExactlyOnce() throws Throwable {
        Fixture f = new Fixture(true);
        f.editor.setText("native text");
        assertEquals(true, f.hook.longClick(f.chain(f.send)));
        assertEquals(1, f.nativeLongPresses.get());
        assertEquals(1, f.proceeds.get());
        assertEquals("native text", f.editor.getText().toString());
        f.hook.close();
    }

    @Test public void hashWithUnavailableVoiceStillConsumesAndRetainsTheDraft() throws Throwable {
        Fixture f = new Fixture(true);
        f.editor.setText("#voice");
        assertEquals(true, f.hook.longClick(f.chain(f.send)));
        assertEquals(0, f.proceeds.get());
        assertEquals(0, f.nativeLongPresses.get());
        assertEquals("#voice", f.editor.getText().toString());
        f.hook.close();
    }

    @Test public void emptyHashAndFailedContextValidationNeverInvokeNativeTextClick() throws Throwable {
        Fixture f = new Fixture(true);
        f.editor.setText("#  ");
        assertEquals(true, f.hook.longClick(f.chain(f.send)));
        f.owner.e = null;
        f.editor.setText("#voice");
        assertEquals(true, f.hook.longClick(f.chain(f.send)));
        assertEquals(0, f.proceeds.get());
        assertEquals("#voice", f.editor.getText().toString());
        f.hook.close();
    }

    @Test public void disabledAndUnrelatedViewsKeepTheirNativeLongPresses() throws Throwable {
        Fixture f = new Fixture(false);
        f.editor.setText("#voice");
        assertEquals(true, f.hook.longClick(f.chain(f.send)));
        f.hook.setOptions(enabled());
        View unrelated = new ReadyView(RuntimeEnvironment.getApplication());
        unrelated.setOnLongClickListener(view -> { f.nativeLongPresses.incrementAndGet(); return true; });
        assertEquals(true, f.hook.longClick(f.chain(unrelated)));
        assertEquals(2, f.proceeds.get());
        assertEquals(2, f.nativeLongPresses.get());
        f.hook.close();
    }

    @Test public void changingTheOptionAndClosingRestoreTheNativeLongClickableFlags() throws Exception {
        Fixture f = new Fixture(false);
        f.send.setOnLongClickListener(null);
        f.send.setLongClickable(false);
        // A fresh native control without an original listener starts non-long-clickable.
        View send = new ReadyView(RuntimeEnvironment.getApplication());
        assertFalse(send.isLongClickable());
        f.hook.bindMessage(f.owner, send, f.editor);
        f.hook.setOptions(enabled());
        assertTrue(send.isLongClickable());
        f.hook.setOptions(disabled());
        assertFalse(send.isLongClickable());
        f.hook.setOptions(enabled());
        assertTrue(send.isLongClickable());
        f.hook.close();
        assertFalse(send.isLongClickable());
    }

    @Test public void hashSettingIsIndependentOfBothOldSingleClickSettings() {
        assertFalse(HashLongPressVoiceHook.handles(disabled(), "#voice"));
        assertTrue(HashLongPressVoiceHook.handles(enabled(), "#voice"));
        assertFalse(HashLongPressVoiceHook.handles(enabled(), "ordinary"));
        assertFalse(HashLongPressVoiceHook.handles(enabled(), " #voice"));
        assertFalse(HashLongPressVoiceHook.handles(enabled(), null));
    }

    @Test public void rendererResolutionRejectsAmbiguityInsteadOfPickingTheFirstMethod() {
        assertEquals("render", HashLongPressVoiceHook.uniqueRender(Renderer.class).getName());
        try { HashLongPressVoiceHook.uniqueRender(AmbiguousRenderer.class); fail(); }
        catch (IllegalStateException expected) { }
    }

    public static final class Renderer { public void bind(View view) { } public void render(Object state) { } }
    public static final class AmbiguousRenderer { public void render(Object state) { } public void other(String state) { } }
    public static final class Session { public String id() { return "conversation"; } }
    public static final class Drawer { public Session e = new Session(); }
    public static final class CommentHolder { public Object e; }
    private static LocalOptions enabled() { return new LocalOptions(Collections.singletonMap(HashLongPressVoiceHook.OPTION, true)); }
    private static LocalOptions disabled() { return new LocalOptions(Collections.emptyMap()); }

    private static final class ReadyView extends View {
        ReadyView(Context context) { super(context); }
        @Override public boolean isShown() { return true; }
        @Override public boolean hasWindowFocus() { return true; }
    }
    private static final class ReadyEditor extends EditText {
        ReadyEditor(Context context) { super(context); }
        @Override public boolean isShown() { return true; }
    }
    private static final class Fixture {
        final Drawer owner = new Drawer();
        final View send = new ReadyView(RuntimeEnvironment.getApplication());
        final EditText editor = new ReadyEditor(RuntimeEnvironment.getApplication());
        final AtomicInteger nativeLongPresses = new AtomicInteger();
        final AtomicInteger proceeds = new AtomicInteger();
        final HashLongPressVoiceHook hook;
        Fixture(boolean enabled) throws Exception {
            send.setOnLongClickListener(view -> { nativeLongPresses.incrementAndGet(); return true; });
            hook = new HashLongPressVoiceHook(enabled ? enabled() : disabled(), null, null,
                    Session.class.getMethod("id"), CommentHolder.class.getField("e"), ignored -> { });
            hook.bindMessage(owner, send, editor);
        }
        XposedInterface.Chain chain(View view) {
            return (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getThisObject")) return view;
                        if (method.getName().equals("proceed")) { proceeds.incrementAndGet(); return view.performLongClick(); }
                        throw new AssertionError(method.getName());
                    });
        }
    }
}
