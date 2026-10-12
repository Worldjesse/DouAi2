package com.douai.module.ui;

import android.app.Dialog;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.feature.ChatCompletionClient;
import com.douai.module.feature.ChatImage;
import com.douai.module.feature.ChatImageLoader;
import com.douai.module.feature.ChatMediaLoader;
import com.douai.module.feature.TestChatMemory;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixChatEditorSurface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Model test conversation using a fixed profile snapshot and optional bounded private history. */
final class AiTestChatDialog extends Dialog {
    interface Session {
        String reply(String message, List<ChatImage> images) throws Exception;
        void cancel();
        default String reply(String message, List<ChatImage> images, String document) throws Exception {
            return reply(message, images);
        }
        default void clear() { }
        default List<TestChatMemory.Turn> savedTurns() { return Collections.emptyList(); }
    }

    interface SessionFactory { Session create(); }

    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final int ACCENT = Color.rgb(0, 151, 136);
    private static final int SURFACE = Color.rgb(255, 255, 255);
    private static final int CANVAS = Color.rgb(246, 248, 248);
    private static final int LINE = Color.rgb(226, 232, 231);
    private static final int ERROR = Color.rgb(174, 49, 57);
    private static final int MAX_VISIBLE_TURNS = 100;
    private static final int MAX_VISIBLE_IMAGE_BYTES = 16 * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SessionFactory sessions;
    private final ExecutorService worker;
    private final LinearLayout messages;
    private final ScrollView scroll;
    private final EditText input;
    private final ImageButton send;
    private final ImageButton attach;
    private final TextView state;
    private final LinearLayout attachments;
    private final HorizontalScrollView attachmentScroll;
    private final List<ChatImage> draftImages = new ArrayList<>();
    private final List<Document> draftDocuments = new ArrayList<>();
    private final boolean useMiuix;
    private Dialog attachmentMenu;
    private Dialog memoryDialog;
    private final ArrayDeque<List<ChatImage>> visibleImages = new ArrayDeque<>();
    private int visibleImageBytes;
    private Future<?> imageLoad;
    private HostImagePicker picker;
    private int imageGeneration;
    private boolean loadingImage;
    private Session session;
    private Future<?> request;
    private RequestTurn pending;
    private int generation;
    private boolean busy;
    private boolean disposed;
    private MiuixChatEditorSurface miuixSurface;

    AiTestChatDialog(Context context, AiModelConfig config) {
        this(context, config, true);
    }

    AiTestChatDialog(Context context, AiModelConfig config, boolean useMiuix) {
        this(context, config, () -> {
            ChatCompletionClient client = new ChatCompletionClient(new TestChatMemory(context, config),
                    diagnostic -> android.util.Log.i("DouAi.ModelTest", diagnostic));
            AiModelSettings snapshot = new AiModelSettings(Collections.singletonList(config), config.id);
            return new Session() {
                @Override public String reply(String message, List<ChatImage> images) throws Exception {
                    return client.reply("model-test", message, images, snapshot);
                }
                @Override public void cancel() { client.cancelCurrentRequest(); }
                @Override public String reply(String message, List<ChatImage> images, String document) throws Exception {
                    return client.reply("model-test", message, images, snapshot, "", true, document);
                }
                @Override public void clear() { client.clearHistory(); }
                @Override public List<TestChatMemory.Turn> savedTurns() { return client.savedTurns(); }
            };
        }, Executors.newSingleThreadExecutor(), useMiuix);
    }

    AiTestChatDialog(Context context, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker) {
        this(context, config, sessions, worker, false);
    }

    AiTestChatDialog(Context context, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, boolean useMiuix) {
        super(context, android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        config.validate(true);
        this.sessions = sessions;
        this.worker = worker;
        this.useMiuix = useMiuix;
        session = sessions.create();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTitle("测试对话");

        LinearLayout root = new LinearLayout(context);
        root.setTag("ai.chat");
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(background(SURFACE, dp(20), LINE));
        root.setElevation(dp(4));
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("测试对话", 18, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton memory = icon("查看一级提示词", "ai.chat.memory", android.R.drawable.ic_menu_info_details);
        memory.setOnClickListener(ignored -> {
            TextView background = label(config.systemPrompt.isEmpty() ? "未设置" : config.systemPrompt, 14, TEXT);
            background.setTextIsSelectable(true);
            background.setPadding(dp(16), dp(12), dp(16), dp(12));
            background.setTag("ai.chat.memory.text");
            ScrollView body = new ScrollView(getContext());
            body.addView(background);
            memoryDialog = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("一级提示词")
                    .setView(body).setNegativeButton("关闭", null).create();
            memoryDialog.show();
            if (ownerActivity() != null) HostDialogBounds.install(ownerActivity(), memoryDialog, false);
        });
        header.addView(memory, square());
        ImageButton clear = icon("清空对话", "ai.chat.clear", android.R.drawable.ic_menu_delete);
        clear.setOnClickListener(ignored -> clearConversation());
        header.addView(clear, square());
        ImageButton close = icon("关闭", "ai.chat.close", android.R.drawable.ic_menu_close_clear_cancel);
        close.setOnClickListener(ignored -> dismiss());
        header.addView(close, square());
        root.addView(header, wrap());

        TextView model = label(config.name + " · " + config.modelName, 13, MUTED);
        model.setTag("ai.chat.model");
        model.setMaxLines(2);
        model.setPadding(0, dp(2), 0, dp(8));
        root.addView(model, wrap());
        state = label("", 12, MUTED);
        state.setTag("ai.chat.state");
        state.setMinHeight(dp(28));
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.setPadding(dp(10), 0, dp(10), 0);
        state.setBackground(background(CANVAS, dp(8), LINE));
        root.addView(state, wrap());

        scroll = new ScrollView(context);
        scroll.setTag("ai.chat.scroll");
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(8), dp(4), dp(8), dp(4));
        scroll.setBackground(background(CANVAS, dp(12), LINE));
        messages = new LinearLayout(context);
        messages.setTag("ai.chat.messages");
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(messages, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams transcriptParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        transcriptParams.topMargin = dp(10);
        root.addView(scroll, transcriptParams);

        attachmentScroll = new HorizontalScrollView(context);
        attachmentScroll.setTag("ai.chat.attachments");
        attachmentScroll.setPadding(dp(6), dp(4), dp(6), dp(4));
        attachmentScroll.setBackground(background(CANVAS, dp(12), LINE));
        attachments = new LinearLayout(context);
        attachments.setOrientation(LinearLayout.HORIZONTAL);
        attachmentScroll.addView(attachments);
        attachmentScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams attachmentParams = wrap();
        attachmentParams.topMargin = dp(8);
        root.addView(attachmentScroll, attachmentParams);

        LinearLayout composer = new LinearLayout(context);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(0, dp(10), 0, 0);
        attach = icon("添加附件", "ai.chat.attach", android.R.drawable.ic_menu_gallery);
        attach.setOnClickListener(ignored -> chooseAttachment());
        composer.addView(attach, square());
        input = new EditText(context);
        input.setTag("ai.chat.input");
        input.setContentDescription("消息");
        input.setHint("输入消息");
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(1);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(background(CANVAS, dp(14), LINE));
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        input.setMaxLines(metrics.heightPixels / metrics.density < 480 ? 2 : 4);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        composer.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        send = icon("发送", "ai.chat.send", android.R.drawable.ic_menu_send);
        send.setOnClickListener(ignored -> {
            if (busy) stopRequest();
            else sendMessage(input.getText().toString().trim(), new ArrayList<>(draftImages),
                    new ArrayList<>(draftDocuments), null);
        });
        composer.addView(send, square());
        LinearLayout.LayoutParams composerParams = wrap();
        composerParams.topMargin = dp(8);
        root.addView(composer, composerParams);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateSend(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        restoreConversation();
        Activity activity = ownerActivity();
        if (useMiuix && activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            setOwnerActivity(activity);
            miuixSurface = new MiuixChatEditorSurface(activity, root, this::dismiss);
            setContentView(miuixSurface.getView());
        } else setContentView(root);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.42f;
            window.setAttributes(attributes);
            window.setGravity(Gravity.CENTER);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
                sizeWindow(insets);
                return insets;
            });
        }
        updateSend();
    }

    @Override protected void onStart() {
        super.onStart();
        if (miuixSurface != null) miuixSurface.bindBackNavigation(this);
        Window window = getWindow();
        if (window != null) sizeWindow(window.getDecorView().getRootWindowInsets());
    }

    private void sizeWindow(WindowInsets insets) {
        Window window = getWindow();
        if (window == null) return;
        android.util.DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int hostWidth = metrics.widthPixels;
        int hostHeight = metrics.heightPixels;
        Activity owner = ownerActivity();
        if (Build.VERSION.SDK_INT >= 30 && owner != null) {
            Rect bounds = owner.getWindowManager().getCurrentWindowMetrics().getBounds();
            hostWidth = bounds.width();
            hostHeight = bounds.height();
        }
        int horizontal = 0;
        int vertical = 0;
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
                horizontal = safe.left + safe.right;
                vertical = safe.top + safe.bottom;
            } else {
                horizontal = insets.getSystemWindowInsetLeft() + insets.getSystemWindowInsetRight();
                vertical = insets.getSystemWindowInsetTop() + insets.getSystemWindowInsetBottom();
            }
        }
        int width = Math.max(1, Math.min(dp(560), hostWidth - horizontal - dp(24)));
        int height = Math.max(1, Math.min(dp(720), Math.min(Math.round(hostHeight * 0.84f),
                hostHeight - vertical - dp(32))));
        window.setGravity(Gravity.CENTER);
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.width != width || attributes.height != height) window.setLayout(width, height);
    }

    private void sendMessage(String prompt, List<ChatImage> images, List<Document> documents, TextView retryAnswer) {
        if (disposed || busy || loadingImage || (prompt.isEmpty() && images.isEmpty() && documents.isEmpty())) return;
        TextView answer = retryAnswer;
        if (answer == null) {
            int imageBytes = imageBytes(images);
            while (!visibleImages.isEmpty() && (visibleImages.size() >= MAX_VISIBLE_TURNS
                    || visibleImageBytes + imageBytes > MAX_VISIBLE_IMAGE_BYTES)) {
                visibleImageBytes -= imageBytes(visibleImages.removeFirst());
                messages.removeViewAt(0);
                messages.removeViewAt(0);
            }
            visibleImages.addLast(images);
            visibleImageBytes += imageBytes;
            TextView user = addMessage("我", prompt, true, images);
            for (Document document : documents) {
                TextView name = label(document.name, 13, MUTED);
                name.setTag("ai.chat.document");
                ((LinearLayout) user.getParent()).addView(name, wrap());
            }
            answer = addMessage("AI", "正在回复…", false, Collections.emptyList());
        }
        LinearLayout bubble = (LinearLayout) answer.getParent();
        View retry = bubble.findViewWithTag("ai.chat.retry");
        if (retry != null) bubble.removeView(retry);
        answer.setText("正在回复…");
        answer.setTextColor(MUTED);
        if (prompt.equals(input.getText().toString().trim())) input.setText("");
        if (draftImages.equals(images)) { draftImages.clear(); renderAttachments(); }
        if (draftDocuments.equals(documents)) { draftDocuments.clear(); renderAttachments(); }
        int ticket = ++generation;
        Session active = session;
        RequestTurn turn = new RequestTurn(ticket, prompt, images, documents, answer);
        pending = turn;
        busy = true;
        state.setText("正在回复…");
        updateSend();
        scrollToBottom();
        request = worker.submit(() -> {
            String reply = null;
            Exception error = null;
            try {
                synchronized (turn) {
                    if (turn.stopRequested) throw new java.util.concurrent.CancellationException();
                    turn.started = true;
                }
                reply = active.reply(prompt, images, documentText(documents));
            }
            catch (Exception failure) { error = failure; }
            String result = reply;
            Exception failure = error;
            main.post(() -> finishRequest(turn, result, failure));
        });
    }

    private void finishRequest(RequestTurn turn, String reply, Exception failure) {
        // A cancelled/closed conversation must not receive a late response.
        if (disposed || turn.ticket != generation) return;
        busy = false;
        request = null;
        pending = null;
        TextView answer = turn.answer;
        if (failure == null) {
            answer.setText(reply);
            answer.setTextColor(TEXT);
            state.setText("");
        } else {
            answer.setText(turn.stopRequested ? "已停止" : errorMessage(failure));
            answer.setTextColor(turn.stopRequested ? MUTED : ERROR);
            state.setText(turn.stopRequested ? "已停止" : "发送失败");
            restoreDraft(turn.prompt, turn.images, turn.documents);
            addRetry(answer, turn.prompt, turn.images, turn.documents);
        }
        updateSend();
        scrollToBottom();
    }

    private void stopRequest() {
        if (!busy || pending.stopRequested) return;
        synchronized (pending) {
            pending.stopRequested = true;
            if (pending.started && request != null) request.cancel(true);
        }
        session.cancel();
        state.setText("正在停止…");
        updateSend();
    }

    private void clearConversation() {
        if (disposed) return;
        cancelRequest();
        cancelImageLoad();
        session.clear();
        session = sessions.create();
        messages.removeAllViews();
        visibleImages.clear();
        visibleImageBytes = 0;
        draftImages.clear();
        draftDocuments.clear();
        renderAttachments();
        state.setText("");
        updateSend();
    }

    private void cancelRequest() {
        ++generation;
        if (request != null) request.cancel(true);
        session.cancel();
        request = null;
        pending = null;
        busy = false;
    }

    @Override public void dismiss() {
        dispose();
        super.dismiss();
    }

    private void dispose() {
        if (disposed) return;
        disposed = true;
        cancelRequest();
        cancelImageLoad();
        if (attachmentMenu != null) attachmentMenu.dismiss();
        if (memoryDialog != null) memoryDialog.dismiss();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (miuixSurface != null) {
            miuixSurface.close();
            miuixSurface = null;
        }
    }

    private void restoreDraft(String prompt, List<ChatImage> images, List<Document> documents) {
        if (input.getText().toString().isEmpty()) {
            input.setText(prompt);
            input.setSelection(input.length());
        }
        if (draftImages.isEmpty()) { draftImages.addAll(images); renderAttachments(); }
        if (draftDocuments.isEmpty()) { draftDocuments.addAll(documents); renderAttachments(); }
    }

    private void addRetry(TextView answer, String prompt, List<ChatImage> images, List<Document> documents) {
        ImageButton retry = icon("重新发送", "ai.chat.retry", android.R.drawable.ic_menu_rotate);
        retry.setOnClickListener(ignored -> sendMessage(prompt, images, documents, answer));
        ((LinearLayout) answer.getParent()).addView(retry, square());
    }

    private TextView addMessage(String role, String text, boolean user, List<ChatImage> images) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(user ? Gravity.END : Gravity.START);
        LinearLayout bubble = new LinearLayout(getContext());
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        bubble.setBackground(background(user ? Color.rgb(225, 243, 237) : SURFACE, dp(14),
                user ? Color.rgb(210, 235, 227) : LINE));
        TextView name = label(role, 12, user ? ACCENT : MUTED);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bubble.addView(name, wrap());
        TextView message = label(text, 15, TEXT);
        message.setTag(user ? "ai.chat.user" : "ai.chat.answer");
        message.setTextIsSelectable(true);
        message.setPadding(0, dp(4), 0, 0);
        bubble.addView(message, wrap());
        for (ChatImage image : images) {
            ImageView preview = preview(image);
            bubble.addView(preview, new LinearLayout.LayoutParams(dp(128), dp(96)));
        }
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (user) bubbleParams.leftMargin = dp(24);
        else bubbleParams.rightMargin = dp(24);
        row.addView(bubble, bubbleParams);
        LinearLayout.LayoutParams rowParams = wrap();
        rowParams.bottomMargin = dp(10);
        messages.addView(row, rowParams);
        return message;
    }

    private void updateSend() {
        state.setVisibility(TextUtils.isEmpty(state.getText()) ? View.GONE : View.VISIBLE);
        send.setImageResource(busy ? android.R.drawable.ic_menu_close_clear_cancel : android.R.drawable.ic_menu_send);
        send.setContentDescription(busy ? "停止请求" : "发送");
        send.setTooltipText(busy ? "停止请求" : "发送");
        send.setEnabled(!disposed && !loadingImage && (busy ? !pending.stopRequested
                : !input.getText().toString().trim().isEmpty() || !draftImages.isEmpty() || !draftDocuments.isEmpty()));
        send.setAlpha(send.isEnabled() ? 1f : 0.4f);
        attach.setEnabled(!disposed && !busy && !loadingImage
                && (draftImages.size() < ChatCompletionClient.MAX_IMAGES_PER_MESSAGE || draftDocuments.size() < 4));
        attach.setAlpha(attach.isEnabled() ? 1f : 0.4f);
        if (miuixSurface != null) miuixSurface.refresh();
    }

    private void chooseAttachment() {
        if (disposed || busy || loadingImage) return;
        attachmentMenu = new MiuixDialogBuilder(getContext(), useMiuix).setTitle("添加附件")
                .setItems(new String[]{"图片", "视频", "文件"}, (dialog, which) -> {
                    attachmentMenu = null;
                    selectAttachment(AttachmentKind.values()[which]);
                }).setNegativeButton("取消", null).create();
        attachmentMenu.show();
    }

    private void selectAttachment(AttachmentKind kind) {
        if (disposed || busy || loadingImage) return;
        if (picker != null) picker.close();
        String[] mime = kind == AttachmentKind.IMAGE ? new String[]{"image/*"}
                : kind == AttachmentKind.VIDEO ? new String[]{"video/*"}
                : new String[]{"text/*", "application/json", "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"};
        picker = HostImagePicker.launch(ownerActivity(), mime, uri -> {
            picker = null;
            if (uri != null && !disposed) loadMedia(uri, kind);
        });
        if (picker == null) state.setText("无法打开附件选择器");
        updateSend();
    }

    private void loadMedia(android.net.Uri uri, AttachmentKind kind) {
        loadingImage = true;
        state.setText("正在读取附件…");
        updateSend();
        int ticket = ++imageGeneration;
        imageLoad = worker.submit(() -> {
            LoadedMedia media = null;
            Exception error = null;
            try {
                if (kind == AttachmentKind.IMAGE) {
                    media = new LoadedMedia("图片", "", Collections.singletonList(ChatImageLoader.load(getContext(), uri)));
                } else if (kind == AttachmentKind.VIDEO) {
                    int frames = Math.max(1, ChatCompletionClient.MAX_IMAGES_PER_MESSAGE - draftImages.size());
                    media = new LoadedMedia("视频", "视频仅提供抽样画面，不包含音频。",
                            ChatMediaLoader.loadVideo(getContext(), uri, frames));
                } else {
                    ChatMediaLoader.Document document = ChatMediaLoader.loadDocument(getContext(), uri);
                    media = new LoadedMedia(document.name, document.text, document.images);
                }
            }
            catch (Exception failure) { error = failure; }
            catch (OutOfMemoryError failure) { error = new IllegalArgumentException("附件过大，无法读取"); }
            LoadedMedia result = media;
            Exception failure = error;
            main.post(() -> {
                if (disposed || ticket != imageGeneration) return;
                loadingImage = false;
                imageLoad = null;
                if (failure == null) {
                    try { addMedia(result); }
                    catch (IllegalArgumentException rejected) { state.setText(rejected.getMessage()); updateSend(); return; }
                    state.setText("");
                } else state.setText(errorMessage(failure));
                updateSend();
            });
        });
    }

    void addImage(ChatImage image) {
        if (disposed || busy || draftImages.size() >= ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) return;
        draftImages.add(image);
        renderAttachments();
        updateSend();
    }

    void addDocument(String name, String text) {
        if (disposed || busy || draftDocuments.size() >= 4) return;
        Document document = new Document(name, text);
        List<Document> next = new ArrayList<>(draftDocuments);
        next.add(document);
        if (documentText(next).length() > 32768) throw new IllegalArgumentException("附件文本总计不能超过 32768 个字符");
        draftDocuments.add(document);
        renderAttachments();
        updateSend();
    }

    private void addMedia(LoadedMedia media) {
        if (draftImages.size() + media.images.size() > ChatCompletionClient.MAX_IMAGES_PER_MESSAGE)
            throw new IllegalArgumentException("每条消息最多包含 4 张图片或视频帧");
        if (!media.text.isEmpty() && draftDocuments.size() >= 4)
            throw new IllegalArgumentException("每条消息最多添加 4 个文件或视频说明");
        if (!media.text.isEmpty()) addDocument(media.name, media.text);
        draftImages.addAll(media.images);
        renderAttachments();
        updateSend();
    }

    private void renderAttachments() {
        attachments.removeAllViews();
        for (ChatImage image : draftImages) {
            LinearLayout item = new LinearLayout(getContext());
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.addView(preview(image), new LinearLayout.LayoutParams(dp(64), dp(56)));
            ImageButton remove = icon("移除图片", "ai.chat.remove_image", android.R.drawable.ic_menu_close_clear_cancel);
            remove.setOnClickListener(ignored -> { draftImages.remove(image); renderAttachments(); updateSend(); });
            item.addView(remove, square());
            attachments.addView(item);
        }
        for (Document document : draftDocuments) {
            LinearLayout item = new LinearLayout(getContext());
            item.setTag("ai.chat.document_attachment");
            item.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(document.name, 13, TEXT);
            name.setTag("ai.chat.document_name");
            name.setMaxLines(2);
            name.setMaxWidth(dp(160));
            item.addView(name);
            ImageButton remove = icon("移除文件", "ai.chat.remove_document", android.R.drawable.ic_menu_close_clear_cancel);
            remove.setOnClickListener(ignored -> { draftDocuments.remove(document); renderAttachments(); updateSend(); });
            item.addView(remove, square());
            attachments.addView(item);
        }
        attachmentScroll.setVisibility(draftImages.isEmpty() && draftDocuments.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private ImageView preview(ChatImage image) {
        ImageView view = new ImageView(getContext());
        view.setTag("ai.chat.image");
        view.setContentDescription("图片");
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImageBitmap(ChatImageLoader.thumbnail(image));
        return view;
    }

    private void cancelImageLoad() {
        ++imageGeneration;
        if (imageLoad != null) imageLoad.cancel(true);
        imageLoad = null;
        loadingImage = false;
        if (picker != null) picker.close();
        picker = null;
    }

    private Activity ownerActivity() {
        if (getOwnerActivity() != null) return getOwnerActivity();
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private static int imageBytes(List<ChatImage> images) {
        int bytes = 0;
        for (ChatImage image : images) bytes += image.sizeBytes();
        return bytes;
    }

    private static final class RequestTurn {
        final int ticket;
        final String prompt;
        final List<ChatImage> images;
        final List<Document> documents;
        final TextView answer;
        volatile boolean stopRequested;
        boolean started;

        RequestTurn(int ticket, String prompt, List<ChatImage> images, List<Document> documents, TextView answer) {
            this.ticket = ticket;
            this.prompt = prompt;
            this.images = images;
            this.documents = documents;
            this.answer = answer;
        }
    }

    private void restoreConversation() {
        for (TestChatMemory.Turn turn : session.savedTurns()) {
            visibleImages.addLast(turn.images);
            visibleImageBytes += imageBytes(turn.images);
            TextView user = addMessage("我", turn.prompt, true, turn.images);
            if (!turn.document.isEmpty()) {
                TextView document = label("已保存的附件文本", 13, MUTED);
                document.setTag("ai.chat.document");
                ((LinearLayout) user.getParent()).addView(document, wrap());
            }
            addMessage("AI", turn.answer, false, Collections.emptyList());
        }
    }

    private static String documentText(List<Document> documents) {
        StringBuilder text = new StringBuilder();
        for (Document document : documents) {
            if (text.length() > 0) text.append("\n\n");
            text.append("[文件：").append(document.name).append("]\n").append(document.text);
        }
        return text.toString();
    }

    private static final class Document {
        final String name;
        final String text;
        Document(String name, String text) {
            this.name = name == null || name.isEmpty() ? "文件" : name;
            this.text = text == null ? "" : text;
        }
    }

    private enum AttachmentKind { IMAGE, VIDEO, FILE }

    private static final class LoadedMedia {
        final String name;
        final String text;
        final List<ChatImage> images;
        LoadedMedia(String name, String text, List<ChatImage> images) {
            this.name = name;
            this.text = text;
            this.images = images;
        }
    }

    private void scrollToBottom() { scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private ImageButton icon(String description, String tag, int resource) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(resource);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setTag(tag);
        button.setBackground(background(CANVAS, dp(14), LINE));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        return button;
    }

    private static String errorMessage(Exception failure) {
        if (failure instanceof IllegalStateException || failure instanceof IllegalArgumentException) {
            String message = failure.getMessage();
            if (message != null) return "请求失败：" + message.substring(0, Math.min(220, message.length()));
        }
        if (failure instanceof java.net.SocketTimeoutException) return "请求超时，请稍后重试。";
        if (failure instanceof org.json.JSONException) return "无法读取模型回复，请检查接口协议。";
        return "请求失败，请检查网络和模型配置。";
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private GradientDrawable background(int color, int radius, int strokeColor) {
        GradientDrawable shape = background(color, radius);
        shape.setStroke(dp(1), strokeColor);
        return shape;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams square() { return new LinearLayout.LayoutParams(dp(48), dp(48)); }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
