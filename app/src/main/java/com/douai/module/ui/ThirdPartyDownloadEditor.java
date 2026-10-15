package com.douai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.config.SettingsStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;
import com.douai.module.config.ThirdPartyRequestTemplate;
import com.douai.module.feature.ThirdPartyGetClient;
import com.douai.module.feature.ThirdPartyResponse;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixExtraControls;
import com.douai.module.ui.miuix.MiuixFormControls;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

/** Dialogs for one third-party GET configuration and its response fields. */
final class ThirdPartyDownloadEditor {
    private static final int MAX_SAMPLE_CHARS = 2 * 1024 * 1024;
    private final Context context;
    private final SettingsStore repository;
    private final boolean useMiuix;
    private final BiConsumer<Dialog, Runnable> presenter;

    ThirdPartyDownloadEditor(Context context, SettingsStore repository, boolean useMiuix,
                             BiConsumer<Dialog, Runnable> presenter) {
        this.context = ModuleTheme.themedContext(context);
        this.repository = repository;
        this.useMiuix = useMiuix;
        this.presenter = presenter;
    }

    String valuePreview(String key, String fallback, boolean secret) {
        String value = ThirdPartyDownloadSettings.GET_TEMPLATE.equals(key)
                ? requestSettings().getTemplate : repository.getString(key, fallback);
        return value.isEmpty() ? "未设置" : secret ? "已设置" : value;
    }

    String signatureMode() { return requestSettings().signatureMode; }

    String signaturePreview() {
        return ThirdPartyDownloadSettings.SIGNATURE_MD5.equals(signatureMode()) ? "MD5（PHP 参数排序）" : "无签名";
    }

    void chooseSignature(Runnable changed) {
        ThirdPartyDownloadSettings config = requestSettings();
        String[] modes = {ThirdPartyDownloadSettings.SIGNATURE_NONE, ThirdPartyDownloadSettings.SIGNATURE_MD5};
        show(builder("签名方式").setSingleChoiceItems(new String[]{"无签名", "MD5（PHP 参数排序）"},
                ThirdPartyDownloadSettings.SIGNATURE_MD5.equals(config.signatureMode) ? 1 : 0, (dialog, which) -> {
                    // Keep the resolved legacy link binding when an explicit signature mode is first saved.
                    repository.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, config.getTemplate);
                    repository.putString(ThirdPartyDownloadSettings.SIGNATURE_MODE, modes[which]);
                    dialog.dismiss();
                    changed.run();
                }).setNegativeButton("取消", null).create(), null);
    }

    String parametersPreview() {
        try {
            List<String> names = new ArrayList<>();
            for (ThirdPartyRequestTemplate.Parameter parameter : requestTemplate().parameters) {
                names.add(parameter.name);
            }
            return names.isEmpty() ? "无请求参数" : String.join("、", names);
        } catch (Exception failure) { return "待设置"; }
    }

    String samplePreview() {
        try {
            ThirdPartyResponse response = response();
            return response.values().size() + " 个信息字段，" + response.downloadFields().size() + " 个链接字段";
        } catch (Exception failure) {
            return "待解析";
        }
    }

    String fieldsPreview(boolean naming) {
        List<String> fields = selected(naming);
        return fields.isEmpty() ? "未选择" : String.join(naming ? " → " : "、", fields);
    }

    void editValue(String title, String key, String fallback, boolean secret, Runnable changed) {
        LinearLayout form = form();
        EditText input = input(form, title, ThirdPartyDownloadSettings.GET_TEMPLATE.equals(key)
                ? requestSettings().getTemplate : repository.getString(key, fallback), secret,
                ThirdPartyDownloadSettings.HEADERS.equals(key) ? 32768 : 8192);
        AlertDialog dialog = builder(title).setView(scroll(form))
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        show(dialog, null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            String value = input.getText().toString();
            if (!secret) value = value.trim();
            try {
                if (ThirdPartyDownloadSettings.HEADERS.equals(key)) {
                    JSONObject headers = new JSONObject(value.isEmpty() ? "{}" : value);
                    java.util.Iterator<String> names = headers.keys();
                    while (names.hasNext()) {
                        if (!(headers.get(names.next()) instanceof String)) {
                            throw new IllegalArgumentException("请求头的值必须是字符串");
                        }
                    }
                    value = headers.toString();
                }
                if (ThirdPartyDownloadSettings.GET_TEMPLATE.equals(key)) {
                    ThirdPartyRequestTemplate.parse(value);
                    saveTemplate(value);
                } else {
                    if (ThirdPartyDownloadSettings.SIGNATURE_HEADER.equals(key)
                            && !value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                        throw new IllegalArgumentException("请输入有效的请求头名称");
                    }
                    repository.putString(key, value);
                }
                changed.run();
                dialog.dismiss();
            } catch (Exception failure) { input.setError(error(failure)); }
        });
    }

    void editParameters(Runnable changed) {
        final ThirdPartyRequestTemplate template;
        try { template = requestTemplate(); }
        catch (Exception failure) { toast(error(failure)); return; }
        LinearLayout form = form();
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = text("", 14);
        count.setTag("third_party.parameters.count");
        toolbar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        form.addView(toolbar, matchWrap());
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        form.addView(rows, matchWrap());
        List<ParameterRow> draft = new ArrayList<>();
        int[] nextId = {0};
        Runnable updateCount = () -> count.setText(draft.isEmpty() ? "无请求参数" : draft.size() + " 个请求参数");
        for (ThirdPartyRequestTemplate.Parameter parameter : template.parameters) {
            addParameterRow(rows, draft, nextId[0]++, parameter, updateCount);
        }
        updateCount.run();
        icon(toolbar, "添加参数", "third_party.parameter.add", android.R.drawable.ic_input_add, () -> {
            addParameterRow(rows, draft, nextId[0]++, new ThirdPartyRequestTemplate.Parameter("", ""), updateCount);
            updateCount.run();
        });
        AlertDialog dialog = builder("请求参数").setView(scroll(form))
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        show(dialog, null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            List<ThirdPartyRequestTemplate.Parameter> parameters = new ArrayList<>();
            for (ParameterRow row : draft) {
                String name = row.name.getText().toString().trim();
                if (name.isEmpty()) { row.name.setError("请输入参数名"); return; }
                row.name.setError(null);
                parameters.add(new ThirdPartyRequestTemplate.Parameter(name,
                        row.source.getSelectedItemPosition() == 1 ? "{url}" : row.value.getText().toString()));
            }
            try {
                saveTemplate(template.withParameters(parameters));
                changed.run();
                dialog.dismiss();
            } catch (Exception failure) { toast(error(failure)); }
        });
    }

    private void addParameterRow(LinearLayout rows, List<ParameterRow> draft, int id,
                                 ThirdPartyRequestTemplate.Parameter parameter, Runnable updateCount) {
        ParameterRow row = new ParameterRow();
        row.container = new LinearLayout(context);
        row.container.setOrientation(LinearLayout.VERTICAL);
        row.container.setPadding(0, dp(8), 0, dp(12));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("参数", 14);
        heading.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        icon(heading, "移除参数", "third_party.parameter.remove." + id, android.R.drawable.ic_menu_delete, () -> {
            draft.remove(row);
            rows.removeView(row.container);
            updateCount.run();
        });
        row.container.addView(heading, matchWrap());
        row.name = input(row.container, "参数名", parameter.name, false, 1024);
        row.name.setTag("third_party.parameter.name." + id);
        row.name.setMaxLines(1);
        row.name.setSingleLine(true);
        if (!useMiuix) row.container.addView(text("参数值来源", 14), matchWrap());
        row.source = useMiuix ? MiuixFormControls.spinner(context) : new Spinner(context);
        row.source.setContentDescription("参数值来源");
        row.source.setTag("third_party.parameter.source." + id);
        row.source.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"固定值", "作品链接"}));
        row.container.addView(MiuixFormControls.viewFor(row.source), matchWrap());
        row.fixedValue = new LinearLayout(context);
        row.fixedValue.setOrientation(LinearLayout.VERTICAL);
        row.container.addView(row.fixedValue, matchWrap());
        row.value = input(row.fixedValue, "参数值", "{url}".equals(parameter.value) ? "" : parameter.value, false, 32768);
        row.value.setTag("third_party.parameter.value." + id);
        row.source.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long itemId) {
                row.fixedValue.setVisibility(position == 1 ? View.GONE : View.VISIBLE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        boolean workUrl = "{url}".equals(parameter.value);
        row.source.setSelection(workUrl ? 1 : 0);
        row.fixedValue.setVisibility(workUrl ? View.GONE : View.VISIBLE);
        draft.add(row);
        rows.addView(row.container, matchWrap());
    }

    private static final class ParameterRow {
        LinearLayout container;
        LinearLayout fixedValue;
        EditText name;
        Spinner source;
        EditText value;
    }

    void editSample(Runnable changed) {
        LinearLayout form = form();
        EditText sample = input(form, "JSON / TXT 响应", sample(), false, MAX_SAMPLE_CHARS);
        sample.setTag("third_party.sample");
        sample.setMaxLines(12);
        Button parse = button(form, "解析预览", "third_party.parse");
        TextView preview = output(form, "third_party.preview");
        parse.setOnClickListener(ignored -> {
            try {
                preview.setText(preview(ThirdPartyResponse.parse(sample.getText().toString())));
                sample.setError(null);
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
        AlertDialog dialog = builder("响应样本").setView(scroll(form))
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        show(dialog, null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
            String value = sample.getText().toString().trim();
            try {
                ThirdPartyResponse.parse(value);
                repository.putString(ThirdPartyDownloadSettings.SAMPLE, value);
                changed.run();
                dialog.dismiss();
            } catch (Exception failure) { sample.setError(error(failure)); }
        });
    }

    void chooseDownloadFields(Runnable changed) {
        final ThirdPartyResponse response;
        try { response = response(); }
        catch (Exception failure) { toast(error(failure)); return; }
        LinkedHashSet<String> candidates = new LinkedHashSet<>(response.downloadFields().keySet());
        for (Map.Entry<String, String> value : response.values().entrySet()) {
            if ("[]".equals(value.getValue())) candidates.add(value.getKey());
        }
        List<String> selected = selected(false);
        candidates.addAll(selected);
        LinearLayout form = form();
        Map<String, CheckBox> choices = new LinkedHashMap<>();
        for (String path : candidates) {
            List<String> urls = response.downloadFields().get(path);
            String label = path + " · " + (urls == null ? "暂无链接" : urls.size() + " 个链接");
            CheckBox choice = useMiuix ? MiuixFormControls.checkbox(context, label, selected.contains(path))
                    : new CheckBox(context);
            choice.setText(label);
            choice.setChecked(selected.contains(path));
            choice.setTag("third_party.field." + path);
            choices.put(path, choice);
            form.addView(MiuixFormControls.viewFor(choice), matchWrap());
        }
        EditText extra = input(form, "其他字段路径，每行一个", "", false, 8192);
        extra.setTag("third_party.extra_fields");
        show(builder("下载字段").setView(scroll(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    LinkedHashSet<String> next = new LinkedHashSet<>();
                    for (Map.Entry<String, CheckBox> choice : choices.entrySet()) {
                        if (choice.getValue().isChecked()) next.add(choice.getKey());
                    }
                    for (String path : extra.getText().toString().split("\\r?\\n")) {
                        if (!path.trim().isEmpty()) next.add(path.trim());
                    }
                    repository.putString(ThirdPartyDownloadSettings.DOWNLOAD_FIELDS,
                            ThirdPartyDownloadSettings.encodeFields(new ArrayList<>(next)));
                    changed.run();
                }).create(), null);
    }

    void chooseNamingFields(Runnable changed) {
        final ThirdPartyResponse response;
        try { response = response(); }
        catch (Exception failure) { toast(error(failure)); return; }
        List<String> draft = new ArrayList<>(selected(true));
        LinearLayout form = form();
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setTag("third_party.naming_rows");
        form.addView(rows, matchWrap());
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            rows.removeAllViews();
            for (int index = 0; index < draft.size(); index++) {
                final int position = index;
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(48));
                TextView name = text((index + 1) + ". " + draft.get(index), 14);
                name.setTag("third_party.naming.path." + index);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                icon(row, "上移", "third_party.naming.up." + index, android.R.drawable.arrow_up_float,
                        () -> { if (position > 0) { java.util.Collections.swap(draft, position, position - 1); render[0].run(); } });
                icon(row, "下移", "third_party.naming.down." + index, android.R.drawable.arrow_down_float,
                        () -> { if (position + 1 < draft.size()) { java.util.Collections.swap(draft, position, position + 1); render[0].run(); } });
                icon(row, "移除字段", "third_party.naming.remove." + index, android.R.drawable.ic_menu_delete,
                        () -> { draft.remove(position); render[0].run(); });
                rows.addView(row, matchWrap());
            }
        };
        render[0].run();
        AlertDialog editor = builder("文件命名字段").setView(scroll(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    repository.putString(ThirdPartyDownloadSettings.NAMING_FIELDS,
                            ThirdPartyDownloadSettings.encodeFields(draft));
                    changed.run();
                }).create();
        button(form, "添加字段", "third_party.naming.add").setOnClickListener(ignored -> {
            List<String> candidates = new ArrayList<>(response.values().keySet());
            candidates.removeIf(path -> "[]".equals(response.values().get(path)));
            candidates.removeAll(draft);
            show(builder("添加命名字段").setSingleChoiceItems(candidates.toArray(new String[0]), -1,
                    (dialog, which) -> {
                        draft.add(candidates.get(which));
                        render[0].run();
                        dialog.dismiss();
                    }).setNeutralButton("输入路径", (dialog, which) ->
                            addNamingPath(draft, render[0], editor)).setNegativeButton("取消", null).create(), null);
        });
        show(editor, null);
    }

    private void addNamingPath(List<String> draft, Runnable render, Dialog parent) {
        // Present after the chooser's dismissal so the new dialog stays in its parent's branch.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!parent.isShowing()) return;
            LinearLayout form = form();
            EditText path = input(form, "字段路径", "", false, 1024);
            AlertDialog dialog = builder("添加命名字段").setView(form).setNegativeButton("取消", null)
                    .setPositiveButton("添加", null).create();
            show(dialog, null);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                String value = path.getText().toString().trim();
                if (value.isEmpty()) { path.setError("请输入字段路径"); return; }
                if (!draft.contains(value)) draft.add(value);
                render.run();
                dialog.dismiss();
            });
        });
    }

    void testGet(Runnable changed) {
        LinearLayout form = form();
        EditText share = input(form, "作品链接或分享文本", "", false, 32768);
        share.setTag("third_party.share");
        Button fetch = button(form, "测试 GET", "third_party.fetch");
        TextView status = output(form, "third_party.status");
        TextView preview = output(form, "third_party.preview");
        Button saveSample = button(form, "保存为响应样本", "third_party.save_sample");
        saveSample.setEnabled(false);
        String[] result = new String[1];
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ThirdPartyGetClient.Request[] pending = new ThirdPartyGetClient.Request[1];
        Future<?>[] future = new Future<?>[1];
        boolean[] closed = {false};
        Handler handler = new Handler(Looper.getMainLooper());
        AlertDialog dialog = builder("测试 GET").setView(scroll(form)).setNegativeButton("关闭", null).create();
        Runnable cancel = () -> {
            closed[0] = true;
            if (pending[0] != null) pending[0].cancel();
            pending[0] = null;
            if (future[0] != null) future[0].cancel(true);
            future[0] = null;
            executor.shutdownNow();
        };
        fetch.setOnClickListener(ignored -> {
            if (closed[0] || pending[0] != null) return;
            String input = share.getText().toString();
            ThirdPartyDownloadSettings config = requestSettings();
            try {
                if (config.getTemplate.contains("{url}")) ThirdPartyGetClient.extractShareUrl(input);
            }
            catch (Exception failure) { share.setError(error(failure)); return; }
            share.setError(null);
            ThirdPartyGetClient.Request request = new ThirdPartyGetClient.Request();
            pending[0] = request;
            result[0] = null;
            fetch.setEnabled(false);
            saveSample.setEnabled(false);
            status.setText("请求中...");
            preview.setText("");
            future[0] = executor.submit(() -> {
                try {
                    String body = request.fetch(config, input);
                    ThirdPartyResponse parsed = ThirdPartyResponse.parse(body);
                    String display = preview(parsed);
                    handler.post(() -> {
                        if (closed[0] || !dialog.isShowing() || pending[0] != request) return;
                        pending[0] = null;
                        future[0] = null;
                        result[0] = body;
                        preview.setText(display);
                        status.setText("解析成功");
                        fetch.setEnabled(true);
                        saveSample.setEnabled(true);
                    });
                } catch (Exception failure) {
                    handler.post(() -> {
                        if (closed[0] || !dialog.isShowing() || pending[0] != request) return;
                        pending[0] = null;
                        future[0] = null;
                        status.setText(error(failure));
                        fetch.setEnabled(true);
                    });
                }
            });
        });
        saveSample.setOnClickListener(ignored -> {
            if (result[0] == null || closed[0]) return;
            repository.putString(ThirdPartyDownloadSettings.SAMPLE, result[0]);
            changed.run();
            status.setText("响应样本已保存");
        });
        try { show(dialog, cancel); }
        catch (RuntimeException failure) { cancel.run(); throw failure; }
    }

    private String sample() {
        return repository.getString(ThirdPartyDownloadSettings.SAMPLE, ThirdPartyDownloadSettings.DEFAULT_SAMPLE);
    }

    private ThirdPartyRequestTemplate requestTemplate() {
        return ThirdPartyRequestTemplate.parse(requestSettings().getTemplate);
    }

    private void saveTemplate(String value) {
        ThirdPartyRequestTemplate.parse(value);
        String mode = signatureMode();
        repository.putString(ThirdPartyDownloadSettings.GET_TEMPLATE, value);
        repository.putString(ThirdPartyDownloadSettings.SIGNATURE_MODE, mode);
    }

    private ThirdPartyDownloadSettings requestSettings() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : new String[]{ThirdPartyDownloadSettings.GET_TEMPLATE,
                ThirdPartyDownloadSettings.APP_ID, ThirdPartyDownloadSettings.APP_SECRET,
                ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_HEADER,
                ThirdPartyDownloadSettings.HEADERS}) {
            String value = repository.getString(key, null);
            if (value != null) values.put(key, value);
        }
        return new ThirdPartyDownloadSettings(new LocalOptions(values));
    }

    private ThirdPartyResponse response() throws Exception { return ThirdPartyResponse.parse(sample()); }

    private List<String> selected(boolean naming) {
        return ThirdPartyDownloadSettings.fields(repository.getString(
                naming ? ThirdPartyDownloadSettings.NAMING_FIELDS : ThirdPartyDownloadSettings.DOWNLOAD_FIELDS,
                naming ? ThirdPartyDownloadSettings.DEFAULT_NAMING_FIELDS : ThirdPartyDownloadSettings.DEFAULT_DOWNLOAD_FIELDS));
    }

    private static String preview(ThirdPartyResponse response) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<String>> field : response.downloadFields().entrySet()) {
            text.append(field.getKey()).append(" · ").append(field.getValue().size()).append(" 个链接\n");
        }
        for (Map.Entry<String, String> field : response.values().entrySet()) {
            text.append(field.getKey()).append(": ").append(compact(field.getValue(), 160)).append('\n');
        }
        return text.length() > 65536 ? text.substring(0, 65536) : text.toString().trim();
    }

    private static String compact(String value, int maximum) {
        return value == null ? "null" : value.length() <= maximum ? value : value.substring(0, maximum) + "...";
    }

    private MiuixDialogBuilder builder(String title) {
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, useMiuix);
        builder.setTitle(title);
        return builder;
    }

    private void show(Dialog dialog, Runnable dismiss) { presenter.accept(dialog, dismiss); }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(4), dp(16), dp(8));
        return form;
    }

    private EditText input(LinearLayout form, String label, String value, boolean secret, int maximum) {
        if (!useMiuix) form.addView(text(label, 14), matchWrap());
        EditText input = useMiuix ? MiuixFormControls.input(context, label, value, secret) : new EditText(context);
        input.setContentDescription(label);
        input.setTag("settings.input." + label);
        input.setText(value);
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setMaxLines(secret ? 1 : 6);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maximum)});
        form.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private Button button(LinearLayout form, String label, String tag) {
        Button button = useMiuix ? MiuixFormControls.button(context) : new Button(context);
        button.setText(label);
        button.setTag(tag);
        form.addView(MiuixFormControls.viewFor(button), matchWrap());
        return button;
    }

    private TextView output(LinearLayout form, String tag) {
        TextView output = text("", 13);
        output.setTextIsSelectable(true);
        output.setTag(tag);
        output.setPadding(0, dp(8), 0, dp(8));
        form.addView(output, matchWrap());
        return output;
    }

    private void icon(LinearLayout row, String label, String tag, int resource, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(resource);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setTag(tag);
        button.setOnClickListener(ignored -> action.run());
        row.addView(useMiuix ? MiuixExtraControls.iconAction(context, button, resource) : button,
                new LinearLayout.LayoutParams(dp(40), dp(48)));
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.rgb(31, 39, 45));
        return text;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private static String error(Throwable failure) {
        String message = failure.getMessage();
        return compact(message == null ? "解析失败" : message, 220);
    }

    private void toast(String message) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show();
    }
}
