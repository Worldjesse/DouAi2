package com.douai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.SpeechSettings;
import com.douai.module.config.ThirdPartyRequestTemplate;
import com.douai.module.feature.LocalSpeechSynthesis;
import com.douai.module.feature.SpeechHttpClient;
import com.douai.module.feature.SpeechSynthesis;
import com.douai.module.feature.ThirdPartyResponse;
import com.douai.module.feature.VoiceCatalog;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.ui.miuix.MiuixExtraControls;
import com.douai.module.ui.miuix.MiuixSettingsViews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Provider-specific settings using the host's existing dialog ownership. */
final class SpeechSettingsDialog implements AutoCloseable {
    private static final String VOICE_SAMPLE = "tts_voice_sample";
    private static final String SYNTHESIS_SAMPLE = "tts_synthesis_sample";
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), runnable -> {
                Thread thread = new Thread(runnable, "douai-voice-list");
                thread.setDaemon(true);
                return thread;
            });
    private final Context context;
    private final SettingsStore store;
    private final boolean miuix;
    private final BiConsumer<Dialog, Runnable> presenter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout content;
    private final AlertDialog dialog;
    private LinearLayout advancedContent;
    private SpeechHttpClient.Request fetch;
    private Future<?> future;
    private SpeechSynthesis preview;
    private MediaPlayer player;
    private java.io.File previewFile;
    private long previewGeneration;
    private boolean closed;

    SpeechSettingsDialog(Context context, SettingsStore store, boolean miuix,
                         BiConsumer<Dialog, Runnable> presenter) {
        this.context = context;
        this.store = store;
        this.miuix = miuix;
        this.presenter = presenter;
        content = form();
        render();
        dialog = builder("文本转语音设置").setView(scroll(content)).setNegativeButton("返回", null).create();
    }

    Dialog dialog() { return dialog; }

    private void render() {
        content.removeAllViews();
        boolean third = SpeechSettings.THIRD_PARTY.equals(store.getString(SpeechSettings.PROVIDER, SpeechSettings.LOCAL));
        option("语音来源", third ? "第三方" : "系统引擎", () -> show(builder("语音来源")
                .setSingleChoiceItems(new String[]{"系统引擎", "第三方"}, third ? 1 : 0, (choice, which) -> {
                    store.putString(SpeechSettings.PROVIDER, which == 1 ? SpeechSettings.THIRD_PARTY : SpeechSettings.LOCAL);
                    stopPreview();
                    cancelFetch();
                    choice.dismiss();
                    render();
                }).setNegativeButton("取消", null).create(), null));
        if (!third) {
            editable("语音引擎", "tts_engine", "", false);
            editable("语音语言", "tts_language", "zh-CN", false);
            editable("语速", SpeechSettings.RATE, "", false);
            editable("音调", SpeechSettings.PITCH, "", false);
        } else {
            editable("合成 GET", SpeechSettings.GET_TEMPLATE, SpeechSettings.DEFAULT_GET_TEMPLATE, false);
            option("合成参数", parameterNames(settings().getTemplate), this::parameters);
            editable("合成请求头", SpeechSettings.HEADERS, "{}", false);
            option("合成响应样本", synthesisSampleLabel(), this::editSynthesisSample);
            option("音频地址字段", settings().audioField.isEmpty() ? "自动识别" : settings().audioField, this::chooseAudioField);
            option("音色", voiceLabel(), this::selectVoice);
            editable("音色列表 GET", SpeechSettings.VOICE_GET_TEMPLATE, SpeechSettings.DEFAULT_VOICE_GET_TEMPLATE, false);
            option("刷新音色", null, this::refreshVoices);
            option("音色高级设置", null, this::advanced);
        }
        option("试听", null, this::testSpeech);
        if (advancedContent != null) renderAdvanced(advancedContent);
    }

    private void advanced() {
        LinearLayout form = form();
        advancedContent = form;
        renderAdvanced(form);
        AlertDialog advanced = builder("音色高级设置").setView(scroll(form)).setNegativeButton("返回", null).create();
        show(advanced, () -> {
            if (advancedContent == form) advancedContent = null;
        });
    }

    private void renderAdvanced(LinearLayout form) {
        form.removeAllViews();
        editable(form, "音色列表请求头", SpeechSettings.VOICE_HEADERS, "{}", false);
        editable(form, "列表字段", SpeechSettings.VOICE_LIST_PATH, "", false);
        editable(form, "音色编号字段", SpeechSettings.VOICE_ID_PATH, "", false);
        editable(form, "音色名称字段", SpeechSettings.VOICE_NAME_PATH, "", false);
        option(form, "音色列表样本", store.getString(VOICE_SAMPLE, "").isEmpty() ? "未保存" : "已保存", this::editSample);
    }

    private String voiceLabel() {
        SpeechSettings settings = settings();
        return settings.selectedVoiceName.isEmpty() ? settings.selectedVoice : settings.selectedVoiceName + " · " + settings.selectedVoice;
    }

    private String synthesisSampleLabel() {
        String sample = store.getString(SYNTHESIS_SAMPLE, "");
        if (sample.isEmpty()) return "未保存";
        try { return audioCandidates(sample).size() + " 个链接字段"; }
        catch (Exception ignored) { return "待解析"; }
    }

    private static Map<String, String> audioCandidates(String sample) {
        Map<String, String> candidates = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, List<String>> field : ThirdPartyResponse.parse(sample).downloadFields().entrySet()) {
                List<String> urls = field.getValue();
                if (urls.size() == 1) candidates.put(field.getKey(), urls.get(0));
                else for (int index = 0; index < urls.size(); index++) {
                    candidates.put(field.getKey() + "[" + index + "]", urls.get(index));
                }
            }
        } catch (IllegalArgumentException failure) {
            // JSON scalar links are accepted by speech even when the structured parser needs an object root.
            candidates.put("", SpeechHttpClient.audioUrl(sample, ""));
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException("响应样本中没有音频链接");
        return candidates;
    }

    private void editSynthesisSample() {
        LinearLayout form = form();
        EditText sample = input(form, "JSON / TXT 响应", store.getString(SYNTHESIS_SAMPLE, ""), false);
        sample.setTag("speech.synthesis.sample");
        sample.setMaxLines(12);
        TextView preview = new TextView(context);
        preview.setTag("speech.synthesis.preview");
        android.widget.Button parse = button(form, "解析预览", "speech.synthesis.parse");
        form.addView(preview, matchWrap());
        parse.setOnClickListener(ignored -> {
            try {
                StringBuilder fields = new StringBuilder();
                for (String path : audioCandidates(sample.getText().toString()).keySet()) {
                    if (fields.length() > 0) fields.append('\n');
                    fields.append(path.isEmpty() ? "直接音频链接" : path);
                }
                preview.setText(fields.toString());
                sample.setError(null);
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
        AlertDialog editor = builder("合成响应样本").setView(scroll(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            String value = sample.getText().toString().trim();
            try {
                if (!value.isEmpty()) audioCandidates(value);
                store.putString(SYNTHESIS_SAMPLE, value);
                editor.dismiss();
                render();
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
    }

    private void chooseAudioField() {
        List<String> paths = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        paths.add(""); labels.add("自动识别");
        try {
            for (String path : audioCandidates(store.getString(SYNTHESIS_SAMPLE, "")).keySet()) {
                if (!path.isEmpty()) { paths.add(path); labels.add(path); }
            }
        } catch (Exception ignored) { }
        String current = settings().audioField;
        if (!current.isEmpty() && !paths.contains(current)) { paths.add(current); labels.add(current); }
        show(builder("音频地址字段").setSingleChoiceItems(labels.toArray(new String[0]), paths.indexOf(current), (choice, index) -> {
            store.putString(SpeechSettings.AUDIO_FIELD, paths.get(index));
            settingChanged(SpeechSettings.AUDIO_FIELD);
            choice.dismiss();
            render();
        }).setNeutralButton("手动填写", (choice, index) -> main.post(() ->
                editAudioField())).setNegativeButton("取消", null).create(), null);
    }

    private void editAudioField() {
        if (closed) return;
        LinearLayout form = form();
        EditText field = input(form, "音频地址字段", settings().audioField, false);
        AlertDialog editor = builder("音频地址字段").setView(scroll(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            store.putString(SpeechSettings.AUDIO_FIELD, field.getText().toString().trim());
            settingChanged(SpeechSettings.AUDIO_FIELD);
            editor.dismiss();
            render();
        });
    }

    private void editable(String title, String key, String fallback, boolean secret) {
        editable(content, title, key, fallback, secret);
    }

    private void editable(LinearLayout target, String title, String key, String fallback, boolean secret) {
        String value = editableValue(key, fallback);
        option(target, title, secret && !value.isEmpty() ? "已设置" : value, () -> {
            LinearLayout form = form();
            EditText input = input(form, title, editableValue(key, fallback), secret);
            AlertDialog editor = builder(title).setView(scroll(form)).setNegativeButton("取消", null)
                    .setPositiveButton("保存", null).create();
            show(editor, null);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                String candidate = input.getText().toString().trim();
                try {
                    if (SpeechSettings.RATE.equals(key) || SpeechSettings.PITCH.equals(key)) {
                        candidate = SpeechSettings.validateLocalRateOrPitch(candidate);
                    }
                    if (SpeechSettings.GET_TEMPLATE.equals(key)) ThirdPartyRequestTemplate.parse(candidate, "{text}", "{voice}");
                    if (SpeechSettings.VOICE_GET_TEMPLATE.equals(key) && !candidate.isEmpty()) ThirdPartyRequestTemplate.parse(candidate, new String[0]);
                    if (SpeechSettings.HEADERS.equals(key) || SpeechSettings.VOICE_HEADERS.equals(key)) {
                        org.json.JSONObject headers = new org.json.JSONObject(candidate.isEmpty() ? "{}" : candidate);
                        java.util.Iterator<String> names = headers.keys();
                        while (names.hasNext()) if (!(headers.get(names.next()) instanceof String)) throw new IllegalArgumentException("请求头的值必须是字符串");
                    }
                    store.putString(key, candidate);
                    settingChanged(key);
                    editor.dismiss();
                    render();
                } catch (Exception failure) { input.setError(error(failure)); }
            });
        });
    }

    private String editableValue(String key, String fallback) {
        if (SpeechSettings.RATE.equals(key)) return settings().rateText();
        if (SpeechSettings.PITCH.equals(key)) return settings().pitchText();
        return store.getString(key, fallback);
    }

    private void parameters() {
        SpeechSettings settings = settings();
        final ThirdPartyRequestTemplate template;
        try { template = ThirdPartyRequestTemplate.parse(settings.getTemplate, "{text}", "{voice}"); }
        catch (Exception failure) { toast(error(failure)); return; }
        LinearLayout form = form();
        List<ParameterRow> rows = new ArrayList<>();
        int[] nextId = {0};
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = new TextView(context);
        count.setTag("speech.parameters.count");
        toolbar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        form.addView(toolbar, matchWrap());
        Runnable updateCount = () -> count.setText(rows.isEmpty() ? "无参数" : rows.size() + " 个参数");
        LinearLayout fields = form();
        form.addView(fields, matchWrap());
        Consumer<ThirdPartyRequestTemplate.Parameter> add = parameter -> {
            ParameterRow row = new ParameterRow();
            row.container = form();
            int index = nextId[0]++;
            LinearLayout heading = new LinearLayout(context);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(context);
            label.setText("参数");
            heading.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            icon(heading, "移除参数", "speech.parameter.remove." + index, android.R.drawable.ic_menu_delete, () -> {
                rows.remove(row);
                fields.removeView(row.container);
                updateCount.run();
            });
            row.container.addView(heading, matchWrap());
            row.name = input(row.container, "参数名", parameter.name, false);
            row.name.setTag("speech.parameter.name." + index);
            row.name.setSingleLine(true);
            row.source = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
            row.source.setContentDescription("参数值来源");
            row.source.setTag("speech.parameter.source." + index);
            row.source.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                    new String[]{"固定值", "待合成文本", "已选音色"}));
            row.container.addView(MiuixFormControls.viewFor(row.source), matchWrap());
            row.fixedValue = form();
            row.fixedValue.setTag("speech.parameter.fixed." + index);
            row.container.addView(row.fixedValue, matchWrap());
            boolean bound = "{text}".equals(parameter.value) || "{voice}".equals(parameter.value);
            row.value = input(row.fixedValue, "参数值", bound ? "" : parameter.value, false);
            row.value.setTag("speech.parameter.value." + index);
            row.source.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    row.fixedValue.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
            row.source.setSelection("{text}".equals(parameter.value) ? 1 : "{voice}".equals(parameter.value) ? 2 : 0);
            row.fixedValue.setVisibility(bound ? View.GONE : View.VISIBLE);
            rows.add(row);
            fields.addView(row.container, matchWrap());
            updateCount.run();
        };
        for (ThirdPartyRequestTemplate.Parameter parameter : template.parameters) add.accept(parameter);
        updateCount.run();
        icon(toolbar, "添加参数", "speech.parameter.add", android.R.drawable.ic_input_add,
                () -> add.accept(new ThirdPartyRequestTemplate.Parameter("", "")));
        AlertDialog editor = builder("合成参数").setView(scroll(form))
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            List<ThirdPartyRequestTemplate.Parameter> parameters = new ArrayList<>();
            for (ParameterRow row : rows) {
                String name = row.name.getText().toString().trim();
                if (name.isEmpty()) { row.name.setError("请输入参数名"); return; }
                int source = row.source.getSelectedItemPosition();
                parameters.add(new ThirdPartyRequestTemplate.Parameter(name, source == 1 ? "{text}"
                        : source == 2 ? "{voice}" : row.value.getText().toString()));
            }
            try {
                store.putString(SpeechSettings.GET_TEMPLATE, template.withParameters(parameters));
                settingChanged(SpeechSettings.GET_TEMPLATE);
                editor.dismiss();
                render();
            } catch (Exception failure) { toast(error(failure)); }
        });
    }

    private void editSample() {
        LinearLayout form = form();
        EditText sample = input(form, "音色列表样本", store.getString(VOICE_SAMPLE, ""), false);
        AlertDialog editor = builder("音色列表样本").setView(scroll(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            try {
                String value = sample.getText().toString();
                VoiceCatalog.parse(value, settings());
                store.putString(VOICE_SAMPLE, value);
                cancelFetch();
                editor.dismiss();
                render();
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
    }

    private void refreshVoices() {
        if (fetch != null) { toast("音色列表正在获取"); return; }
        SpeechSettings settings = settings();
        SpeechHttpClient.Request request = new SpeechHttpClient.Request();
        fetch = request;
        toast("正在获取音色列表");
        try {
            future = WORKER.submit(() -> {
                try {
                    String body = request.fetchText(settings.voiceGetTemplate, settings.voiceHeaders);
                    List<VoiceCatalog.Entry> entries = VoiceCatalog.parse(body, settings);
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null;
                        future = null;
                        store.putString(VOICE_SAMPLE, body);
                        render();
                        chooseVoices(entries);
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (closed || fetch != request) return;
                        fetch = null;
                        future = null;
                        toast(error(failure));
                    });
                } finally { request.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            fetch = null;
            request.close();
            toast("音色请求队列已满");
        }
    }

    private void selectVoice() {
        try { chooseVoices(VoiceCatalog.parse(store.getString(VOICE_SAMPLE, ""), settings())); }
        catch (Exception ignored) {
            show(builder("音色").setItems(new String[]{"获取音色列表", "手动填写音色编号"}, (choice, index) -> {
                if (index == 0) refreshVoices(); else manualVoice();
            }).setNegativeButton("取消", null).create(), null);
        }
    }

    private void chooseVoices(List<VoiceCatalog.Entry> entries) {
        String[] labels = new String[entries.size()];
        int selected = -1;
        String current = settings().selectedVoice;
        for (int i = 0; i < entries.size(); i++) {
            VoiceCatalog.Entry entry = entries.get(i);
            labels[i] = entry.name + " · " + entry.id;
            if (entry.id.equals(current)) selected = i;
        }
        show(builder("音色").setSingleChoiceItems(labels, selected, (choice, index) -> {
            VoiceCatalog.Entry entry = entries.get(index);
            store.putString(SpeechSettings.SELECTED_VOICE, entry.id);
            store.putString(SpeechSettings.SELECTED_VOICE_NAME, entry.name);
            stopPreview();
            choice.dismiss();
            render();
        }).setNeutralButton("手动填写", (choice, index) -> main.post(this::manualVoice))
                .setNegativeButton("取消", null).create(), null);
    }

    private void manualVoice() {
        if (closed) return;
        LinearLayout form = form();
        EditText value = input(form, "音色编号", settings().selectedVoice, false);
        AlertDialog editor = builder("音色编号").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        show(editor, null);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            String candidate = value.getText().toString().trim();
            if (candidate.isEmpty()) { value.setError("请输入音色编号"); return; }
            store.putString(SpeechSettings.SELECTED_VOICE, candidate);
            store.putString(SpeechSettings.SELECTED_VOICE_NAME, "");
            stopPreview();
            editor.dismiss();
            render();
        });
    }

    private void testSpeech() {
        LinearLayout form = form();
        EditText text = input(form, "试听文本", "你好", false);
        TextView status = new TextView(context);
        status.setTag("speech.preview.status");
        form.addView(status, matchWrap());
        android.widget.Button start = button(form, "试听", "speech.preview.start");
        AlertDialog test = builder("试听").setView(form).setNegativeButton("关闭", null).create();
        start.setOnClickListener(ignored -> {
            stopPreview();
            long generation = previewGeneration;
            start.setEnabled(false);
            status.setText("正在生成语音");
            preview = SpeechSynthesis.synthesize(context, text.getText().toString(), options(), (result, failure) -> {
                if (closed || generation != previewGeneration || !test.isShowing()) { delete(result); return; }
                preview = null;
                start.setEnabled(true);
                if (result == null) { status.setText(failure); return; }
                previewFile = result.file;
                try {
                    MediaPlayer preparing = new MediaPlayer();
                    player = preparing;
                    preparing.setDataSource(result.file.getAbsolutePath());
                    preparing.setOnPreparedListener(ready -> {
                        if (closed || generation != previewGeneration || player != ready || !test.isShowing()) return;
                        ready.start();
                        status.setText("正在播放");
                    });
                    preparing.setOnCompletionListener(completed -> {
                        if (generation != previewGeneration || player != completed) return;
                        stopPreview();
                        status.setText("试听完成");
                    });
                    preparing.setOnErrorListener((failed, what, extra) -> {
                        if (generation == previewGeneration && player == failed) {
                            stopPreview();
                            status.setText("试听播放失败");
                        }
                        return true;
                    });
                    status.setText("正在准备播放");
                    preparing.prepareAsync();
                } catch (Exception error) { stopPreview(); status.setText("试听播放失败"); }
            });
        });
        show(test, this::stopPreview);
    }

    private void stopPreview() {
        previewGeneration++;
        if (preview != null) { preview.close(); preview = null; }
        if (player != null) { player.release(); player = null; }
        if (previewFile != null) { previewFile.delete(); previewFile = null; }
    }

    private void settingChanged(String key) {
        stopPreview();
        if (key.startsWith("tts_voice_")) cancelFetch();
    }

    private void cancelFetch() {
        SpeechHttpClient.Request request = fetch;
        fetch = null;
        if (request != null) request.cancel();
        if (future != null) future.cancel(true);
        future = null;
    }

    private LocalOptions options() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : new String[]{SpeechSettings.PROVIDER, SpeechSettings.GET_TEMPLATE, SpeechSettings.HEADERS,
                SpeechSettings.AUDIO_FIELD, SpeechSettings.VOICE_GET_TEMPLATE, SpeechSettings.VOICE_HEADERS,
                SpeechSettings.VOICE_LIST_PATH, SpeechSettings.VOICE_ID_PATH, SpeechSettings.VOICE_NAME_PATH,
                SpeechSettings.SELECTED_VOICE, SpeechSettings.SELECTED_VOICE_NAME, "tts_engine", "tts_language", SpeechSettings.RATE, SpeechSettings.PITCH}) {
            String value = store.getString(key, null);
            if (value != null) values.put(key, value);
        }
        return new LocalOptions(values);
    }

    private SpeechSettings settings() { return new SpeechSettings(options()); }

    private static String parameterNames(String template) {
        try {
            List<String> names = new ArrayList<>();
            for (ThirdPartyRequestTemplate.Parameter parameter : ThirdPartyRequestTemplate.parse(template, "{text}", "{voice}").parameters) names.add(parameter.name);
            return names.isEmpty() ? "无参数" : String.join("、", names);
        } catch (Exception ignored) { return "未设置"; }
    }

    private void option(String title, String value, Runnable action) {
        option(content, title, value, action);
    }

    private void option(LinearLayout target, String title, String value, Runnable action) {
        if (miuix) {
            target.addView(new MiuixSettingsViews.Option(context, title, "", value, true, action).getView(), matchWrap());
        } else {
            LinearLayout row = form();
            row.setTag("settings.option." + title);
            TextView label = new TextView(context);
            label.setText(title);
            label.setTextSize(15);
            row.addView(label, matchWrap());
            if (value != null && !value.isEmpty()) {
                TextView detail = new TextView(context);
                detail.setTag("settings.value." + title);
                detail.setText(value);
                detail.setMaxLines(3);
                row.addView(detail, matchWrap());
            }
            row.setOnClickListener(ignored -> action.run());
            target.addView(row, matchWrap());
        }
    }

    private EditText input(LinearLayout form, String label, String initial, boolean secret) {
        EditText input = miuix ? MiuixFormControls.input(context, label, initial, secret) : new EditText(context);
        input.setContentDescription(label);
        input.setTag("settings.input." + label);
        input.setText(initial);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setMaxLines(6);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2 * 1024 * 1024)});
        form.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private android.widget.Button button(LinearLayout form, String text, String tag) {
        android.widget.Button button = miuix ? MiuixFormControls.button(context) : new android.widget.Button(context);
        button.setText(text);
        button.setTag(tag);
        form.addView(MiuixFormControls.viewFor(button), matchWrap());
        return button;
    }

    private void icon(LinearLayout row, String label, String tag, int resource, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(resource);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setTag(tag);
        button.setOnClickListener(ignored -> action.run());
        row.addView(miuix ? MiuixExtraControls.iconAction(context, button, resource) : button,
                new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private MiuixDialogBuilder builder(String title) {
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, miuix);
        builder.setTitle(title);
        return builder;
    }
    private void show(Dialog dialog, Runnable dismiss) { presenter.accept(dialog, dismiss); }
    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(12, 8, 12, 8);
        return form;
    }
    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private static String error(Throwable failure) { return failure.getMessage() == null ? "请求或解析失败" : failure.getMessage(); }
    private void toast(String value) { android.widget.Toast.makeText(context, value, android.widget.Toast.LENGTH_SHORT).show(); }
    private static void delete(LocalSpeechSynthesis.Result result) { if (result != null) result.file.delete(); }
    @Override public void close() {
        closed = true;
        cancelFetch();
        stopPreview();
    }
    private static final class ParameterRow {
        LinearLayout container;
        LinearLayout fixedValue;
        EditText name;
        EditText value;
        Spinner source;
    }
}
