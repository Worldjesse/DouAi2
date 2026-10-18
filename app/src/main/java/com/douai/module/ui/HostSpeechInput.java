package com.douai.module.ui;

import android.app.Activity;
import android.content.Intent;
import android.speech.RecognizerIntent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;

/** Uses an installed system recognizer to fill a local draft; never records or sends IM audio. */
final class HostSpeechInput implements AutoCloseable {
    private Object launcher;
    private Class<?> launcherType;
    private boolean closed;

    static HostSpeechInput launch(Activity activity, Consumer<String> selected) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        HostSpeechInput input = new HostSpeechInput();
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> contract = Class.forName("androidx.activity.result.contract.ActivityResultContract", false, loader);
            Class<?> callback = Class.forName("androidx.activity.result.ActivityResultCallback", false, loader);
            Class<?> registryType = Class.forName("androidx.activity.result.ActivityResultRegistry", false, loader);
            input.launcherType = Class.forName("androidx.activity.result.ActivityResultLauncher", false, loader);
            Object registry = activity.getClass().getMethod("getActivityResultRegistry").invoke(activity);
            Object open = Class.forName("androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult", false, loader)
                    .getConstructor().newInstance();
            Object result = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                    return "DouAiSpeechInput";
                }
                if (!"onActivityResult".equals(method.getName()) || input.closed) return null;
                input.close();
                String text = null;
                if (args != null && args.length > 0 && args[0] != null) {
                    Object raw = args[0];
                    Class<?> resultType = Class.forName("androidx.activity.result.ActivityResult", false, loader);
                    if (Integer.valueOf(Activity.RESULT_OK).equals(resultType.getMethod("getResultCode").invoke(raw))) {
                        Intent data = (Intent) resultType.getMethod("getData").invoke(raw);
                        ArrayList<String> words = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        if (words != null && !words.isEmpty()) text = words.get(0);
                    }
                }
                selected.accept(text);
                return null;
            });
            input.launcher = registryType.getMethod("register", String.class, contract, callback)
                    .invoke(registry, "douai_speech_" + UUID.randomUUID(), open, result);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "语音输入");
            input.launcherType.getMethod("launch", Object.class).invoke(input.launcher, intent);
            return input;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            input.close();
            return null;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (launcher != null) {
            try { launcherType.getMethod("unregister").invoke(launcher); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
    }
}
