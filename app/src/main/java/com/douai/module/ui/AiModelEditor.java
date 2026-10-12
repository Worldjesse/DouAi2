package com.douai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AiProviderPresets;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.ChatCompletionClient;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.ui.miuix.MiuixSettingsViews;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

/** Owns one model draft, its persistence, child dialogs, and cancellable catalog request. */
final class AiModelEditor implements AutoCloseable {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private final Context context;
    private final SettingsStore repository;
    private final boolean useMiuix;
    private final Runnable onChanged;
    private final ExecutorService requests;
    private final List<Dialog> children = new ArrayList<>();
    private AlertDialog dialog;
    private ChatCompletionClient.ModelCatalogRequest modelRequest;
    private Future<?> modelFetch;
    private boolean closed;
    private Runnable bindButtons;

    AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged) {
        this(context, repository, original, useMiuix, onChanged, Executors.newSingleThreadExecutor());
    }

    AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged, ExecutorService requests) {
        this.context = context;
        this.repository = repository;
        this.useMiuix = useMiuix;
        this.onChanged = onChanged;
        this.requests = requests;
        build(original);
    }

    AlertDialog dialog() { return dialog; }

    void show(BiConsumer<Dialog, Runnable> presenter) {
        if (closed) return;
        try {
            presenter.accept(dialog, this::close);
            bindButtons.run();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        cancelCatalog();
        for (Dialog child : new ArrayList<>(children)) child.dismiss();
        children.clear();
        requests.shutdownNow();
        if (dialog.isShowing()) dialog.dismiss();
    }

    private void cancelCatalog() {
        ChatCompletionClient.ModelCatalogRequest pending = modelRequest;
        modelRequest = null;
        if (pending != null) pending.cancel();
        if (modelFetch != null) modelFetch.cancel(true);
        modelFetch = null;
    }

    private void showChild(Dialog child) {
        if (closed || !dialog.isShowing()) return;
        children.removeIf(existing -> !existing.isShowing());
        children.add(child);
        child.setOwnerActivity(dialog.getOwnerActivity());
        child.setOnDismissListener(ignored -> children.remove(child));
        child.show();
    }

    private AiModelSettings aiSettings() { return AiModelSettings.read(repository); }
    private void saveAiSettings(AiModelSettings settings) { settings.save(repository); }

    private void build(AiModelConfig original) {
        LinearLayout form = form();
        EditText name = formInput(form, "配置名称", original.name, false);
        Spinner provider = !useMiuix ? new Spinner(context) : MiuixFormControls.spinner(context);
        List<String> providers = new ArrayList<>();
        for (AiProviderPresets.Preset preset : AiProviderPresets.ALL) providers.add(preset.name);
        provider.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, providers));
        provider.setTag("ai.provider");
        int providerIndex = 0;
        for (int i = 0; i < AiProviderPresets.ALL.size(); i++) {
            if (AiProviderPresets.ALL.get(i).id.equals(original.provider)) providerIndex = i;
        }
        provider.setSelection(providerIndex);
        addField(form, "供应商", provider);

        Spinner protocol = !useMiuix ? new Spinner(context) : MiuixFormControls.spinner(context);
        protocol.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                AiModelConfig.PROTOCOL_LABELS));
        protocol.setTag("ai.protocol");
        protocol.setSelection(protocolIndex(original.protocol));
        addField(form, "接口协议", protocol);

        EditText endpoint = formInput(form, "API 基础地址", original.apiEndpoint, false);
        EditText key = formInput(form, "API 密钥", original.apiKey, true);
        EditText modelName = formInput(form, "模型名称", original.modelName, false);
        EditText modelsEndpoint = formInput(form, "模型列表地址（可选）", original.modelsEndpoint, false);
        Button fetchModels = formButton();
        fetchModels.setText("获取模型列表");
        fetchModels.setTag("ai.fetch_models");
        form.addView(MiuixFormControls.viewFor(fetchModels), matchWrap());

        CheckBox allowHttp = checkField(form, "允许不安全 HTTP", original.allowInsecureHttp);
        String[] systemPrompt = {original.systemPrompt};
        TextView memory = text(promptPreview(original.systemPrompt), 14, MUTED, false);
        memory.setTag("ai.prompt.primary");
        memory.setContentDescription("一级提示词");
        memory.setMaxLines(3);
        memory.setEllipsize(android.text.TextUtils.TruncateAt.END);
        memory.setPadding(0, dp(8), 0, dp(8));
        memory.setMinimumHeight(dp(48));
        memory.setClickable(true);
        memory.setFocusable(true);
        MiuixSettingsViews.Option[] promptRow = new MiuixSettingsViews.Option[1];
        Runnable editMemory = () -> showChild(new PromptEditorDialog(context, "一级提示词", systemPrompt[0], value -> {
            systemPrompt[0] = value;
            memory.setText(promptPreview(value));
            if (promptRow[0] != null) promptRow[0].setValue(promptPreview(value));
        }, useMiuix));
        if (useMiuix) {
            promptRow[0] = new MiuixSettingsViews.Option(context, "一级提示词", null,
                    promptPreview(original.systemPrompt), true, editMemory);
            promptRow[0].getView().setTag("ai.prompt.primary");
            form.addView(promptRow[0].getView(), matchWrap());
        } else {
            memory.setOnClickListener(ignored -> editMemory.run());
            addField(form, "一级提示词", memory);
        }
        EditText contextRounds = formInput(form, "上下文轮数（0–50）", Integer.toString(original.contextRounds), false);
        EditText timeout = formInput(form, "超时秒数（5–120）", Integer.toString(original.timeoutSeconds), false);
        CheckBox temperatureEnabled = checkField(form, "设置温度", original.temperatureEnabled);
        EditText temperature = formInput(form, "温度", Double.toString(original.temperature), false);
        CheckBox maxTokensEnabled = checkField(form, "限制最大输出 Token", original.maxTokensEnabled);
        EditText maxTokens = formInput(form, "最大输出 Token", Integer.toString(original.maxTokens), false);
        CheckBox topPEnabled = checkField(form, "设置 Top P", original.topPEnabled);
        EditText topP = formInput(form, "Top P", Double.toString(original.topP), false);
        EditText headers = formInput(form, "自定义请求头 JSON", original.customHeaders, false);
        EditText parameters = formInput(form, "自定义参数 JSON", original.customParameters, false);
        TextView status = text("模型名称可手动填写；获取列表失败不影响保存。", 12, MUTED, false);
        status.setTag("ai.status");
        form.addView(status, matchWrap());
        Callable<AiModelConfig> readDraft = () -> buildAiConfig(original.id, name, provider, protocol, endpoint,
                key, modelName, modelsEndpoint, systemPrompt[0], contextRounds, timeout, allowHttp,
                temperatureEnabled, temperature, maxTokensEnabled, maxTokens,
                topPEnabled, topP, headers, parameters);

        int[] lastProviderSelection = {providerIndex};
        provider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == lastProviderSelection[0]) return;
                lastProviderSelection[0] = position;
                AiProviderPresets.Preset preset = AiProviderPresets.ALL.get(position);
                protocol.setSelection(protocolIndex(preset.protocol));
                if (!preset.apiEndpoint.isEmpty()) endpoint.setText(preset.apiEndpoint);
                if (name.getText().toString().trim().isEmpty()
                        || name.getText().toString().equals(original.name)) name.setText(preset.name);
                if ("ollama".equals(preset.id) || "lmstudio".equals(preset.id)) allowHttp.setChecked(true);
            }
        });

        dialog = new MiuixDialogBuilder(context, useMiuix).setTitle("模型配置")
                .setView(scrolling(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存并启用", null).setNeutralButton("删除", null).create();
        dialog.setOnDismissListener(ignored -> close());
        bindButtons = () -> {
            boolean exists = false;
            for (AiModelConfig configured : aiSettings().configs) exists |= configured.id.equals(original.id);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(exists ? View.VISIBLE : View.GONE);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(ignored -> {
                try { saveAiSettings(aiSettings().delete(original.id)); }
                catch (RuntimeException failure) { toast(safeError(failure)); return; }
                close();
                onChanged.run();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                try {
                    AiModelConfig draft = readDraft.call();
                    draft.validate(true);
                    saveAiSettings(aiSettings().upsert(draft, true));
                    close();
                    onChanged.run();
                } catch (Exception failure) { toast(safeError(failure)); }
            });
        };

        fetchModels.setOnClickListener(ignored -> {
            try {
                if (closed || modelRequest != null) return;
                AiModelConfig draft = readDraft.call();
                draft.validate(false);
                fetchModels.setEnabled(false);
                status.setText("正在获取模型列表…");
                ChatCompletionClient.ModelCatalogRequest request = new ChatCompletionClient.ModelCatalogRequest();
                modelRequest = request;
                modelFetch = requests.submit(() -> {
                    try {
                        List<String> fetched = request.fetch(draft);
                        status.post(() -> {
                            if (closed || !dialog.isShowing() || modelRequest != request) return;
                            modelRequest = null;
                            modelFetch = null;
                            fetchModels.setEnabled(true);
                            if (fetched.isEmpty()) { status.setText("接口未返回模型，请手动填写模型名称。"); return; }
                            String[] values = fetched.toArray(new String[0]);
                            showChild(new MiuixDialogBuilder(context, useMiuix).setTitle("选择模型")
                                    .setItems(values, (child, selected) -> modelName.setText(values[selected]))
                                    .setNegativeButton("取消", null).create());
                            status.setText("已获取 " + fetched.size() + " 个模型");
                        });
                    } catch (Exception failure) {
                        status.post(() -> {
                            if (closed || !dialog.isShowing() || modelRequest != request) return;
                            modelRequest = null;
                            modelFetch = null;
                            fetchModels.setEnabled(true);
                            status.setText("获取失败：" + safeError(failure));
                        });
                    }
                });
            } catch (Exception failure) {
                cancelCatalog();
                fetchModels.setEnabled(true);
                toast(safeError(failure));
            }
        });

        Button test = formButton();
        test.setText("测试对话");
        test.setTag("ai.test_chat");
        form.addView(MiuixFormControls.viewFor(test), matchWrap());
        test.setOnClickListener(ignored -> {
            try {
                AiModelConfig draft = readDraft.call();
                draft.validate(true);
                showChild(new AiTestChatDialog(context, draft, useMiuix));
            } catch (Exception failure) { toast(safeError(failure)); }
        });
    }

    private void addField(LinearLayout parent, String label, View input) {
        TextView heading = text(label, 14, TEXT, false);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        parent.addView(heading, params);
        input.setContentDescription(label);
        parent.addView(MiuixFormControls.viewFor(input), matchWrap());
    }

    private CheckBox checkField(LinearLayout parent, String label, boolean checked) {
        CheckBox box = !useMiuix ? new CheckBox(context)
                : MiuixFormControls.checkbox(context, label, checked);
        box.setText(label);
        box.setTextColor(TEXT);
        box.setChecked(checked);
        parent.addView(MiuixFormControls.viewFor(box), matchWrap());
        return box;
    }

    private static int protocolIndex(String protocol) {
        for (int i = 0; i < AiModelConfig.PROTOCOLS.length; i++) {
            if (AiModelConfig.PROTOCOLS[i].equals(protocol)) return i;
        }
        return 0;
    }

    private AiModelConfig buildAiConfig(String id, EditText name, Spinner provider, Spinner protocol,
                                       EditText endpoint, EditText key, EditText model,
                                       EditText modelsEndpoint, String prompt, EditText rounds,
                                       EditText timeout, CheckBox allowHttp, CheckBox temperatureEnabled,
                                       EditText temperature, CheckBox maxTokensEnabled, EditText maxTokens,
                                       CheckBox topPEnabled, EditText topP, EditText headers,
                                       EditText parameters) throws Exception {
        AiProviderPresets.Preset preset = AiProviderPresets.ALL.get(provider.getSelectedItemPosition());
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", name.getText().toString())
                .put("provider", preset.id).put("protocol", AiModelConfig.PROTOCOLS[protocol.getSelectedItemPosition()])
                .put("apiEndpoint", endpoint.getText().toString()).put("apiKey", key.getText().toString())
                .put("modelName", model.getText().toString()).put("modelsEndpoint", modelsEndpoint.getText().toString())
                .put("systemPrompt", prompt)
                .put("contextRounds", Integer.parseInt(rounds.getText().toString().trim()))
                .put("timeoutSeconds", Integer.parseInt(timeout.getText().toString().trim()))
                .put("allowInsecureHttp", allowHttp.isChecked())
                .put("temperatureEnabled", temperatureEnabled.isChecked())
                .put("temperature", Double.parseDouble(temperature.getText().toString().trim()))
                .put("maxTokensEnabled", maxTokensEnabled.isChecked())
                .put("maxTokens", Integer.parseInt(maxTokens.getText().toString().trim()))
                .put("topPEnabled", topPEnabled.isChecked()).put("topP", Double.parseDouble(topP.getText().toString().trim()))
                .put("customHeaders", headers.getText().toString().trim().isEmpty() ? "{}" : headers.getText().toString())
                .put("customParameters", parameters.getText().toString().trim().isEmpty() ? "{}" : parameters.getText().toString()));
    }

    private EditText formInput(LinearLayout parent, String label, String value, boolean secret) {
        TextView heading = text(label, 14, TEXT, false);
        LinearLayout.LayoutParams headingParams = matchWrap();
        headingParams.topMargin = dp(10);
        parent.addView(heading, headingParams);
        EditText input = !useMiuix ? new EditText(context)
                : MiuixFormControls.input(context, label, value, secret);
        input.setContentDescription(label);
        input.setText(value);
        input.setTextSize(14);
        input.setMaxLines(secret ? 1 : 4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        parent.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));
        return form;
    }

    private ScrollView scrolling(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static String promptPreview(String value) {
        if (value.isEmpty()) return "未设置";
        int points = value.codePointCount(0, value.length());
        return value.substring(0, value.offsetByCodePoints(0, Math.min(240, points)));
    }

    private static String safeError(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    private void toast(String message) {
        android.widget.Toast.makeText(context, message == null ? "操作失败" : message,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private Button formButton() {
        return !useMiuix ? new Button(context) : MiuixFormControls.button(context);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
