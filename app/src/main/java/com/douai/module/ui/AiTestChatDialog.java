package com.douai.module.ui;

import android.app.Dialog;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Bitmap;
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
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

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

    interface ThumbnailLoader { Bitmap load(ChatImage image); }

    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private static final int ACCENT = Color.rgb(0, 151, 136);
    private static final int SURFACE = Color.rgb(255, 255, 255);
    private static final int CANVAS = Color.rgb(246, 248, 248);
    private static final int LINE = Color.rgb(226, 232, 231);
    private static final int ERROR = Color.rgb(174, 49, 57);
    private static final int MAX_VISIBLE_TURNS = 100;
    private static final int MAX_VISIBLE_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final ExecutorService HISTORY_IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "DouAi-chat-history");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SessionFactory sessions;
    private final ExecutorService worker;
    private final ExecutorService historyWorker;
    private final ThumbnailLoader thumbnailLoader;
    private final Map<ChatImage, Bitmap> thumbnails = new IdentityHashMap<>();
    private final List<Future<?>> thumbnailLoads = new ArrayList<>();
    private final ListView messages;
    private final List<ChatMessage> transcript = new ArrayList<>();
    private final TranscriptRows messageRows = new TranscriptRows();
    private final EditText input;
    private final ImageButton send;
    private final ImageButton attach;
    private final TextView state;
    private final LinearLayout attachments;
    private final HorizontalScrollView attachmentScroll;
    private final List<ChatImage> draftImages = new ArrayList<>();
    private final List<Document> draftDocuments = new ArrayList<>();
    private final boolean useMiuix;
    private final String assistantName;
    private ImageButton contactEdit;
    private ImageView contactAvatar;
    private Bitmap contactAvatarBitmap;
    private Dialog attachmentMenu;
    private Dialog memoryDialog;
    private final ArrayDeque<List<ChatImage>> visibleImages = new ArrayDeque<>();
    private int visibleImageBytes;
    private Future<?> imageLoad;
    private Future<?> restoreLoad;
    private Future<?> historyClear;
    private HostImagePicker picker;
    private int imageGeneration;
    private boolean loadingImage;
    private Session session;
    private Future<?> request;
    private RequestTurn pending;
    private int generation;
    private int restoreGeneration;
    private int clearGeneration;
    private int thumbnailGeneration;
    private boolean busy;
    private boolean disposed;
    private MiuixChatEditorSurface miuixSurface;

    AiTestChatDialog(Context context, AiModelConfig config) {
        this(context, config, true);
    }

    AiTestChatDialog(Context context, AiModelConfig config, boolean useMiuix) {
        this(context, config, useMiuix, false);
    }

    AiTestChatDialog(Context context, AiModelConfig config, boolean useMiuix, boolean messageContact) {
        this(context, config, () -> {
            AiModelSettings snapshot = new AiModelSettings(Collections.singletonList(config), config.id);
            return new Session() {
                private volatile ChatCompletionClient client;
                private synchronized ChatCompletionClient client() {
                    if (client == null) client = new ChatCompletionClient(messageContact
                            ? TestChatMemory.messageConversation(context, config) : new TestChatMemory(context, config),
                            diagnostic -> android.util.Log.i(messageContact ? "DouAi.AiContact" : "DouAi.ModelTest", diagnostic));
                    return client;
                }
                @Override public String reply(String message, List<ChatImage> images) throws Exception {
                    return client().reply(messageContact ? "message-contact" : "model-test", message, images, snapshot);
                }
                @Override public void cancel() {
                    ChatCompletionClient active = client;
                    if (active != null) active.cancelCurrentRequest();
                }
                @Override public String reply(String message, List<ChatImage> images, String document) throws Exception {
                    return client().reply(messageContact ? "message-contact" : "model-test", message, images, snapshot, "", true, document);
                }
                @Override public void clear() { client().clearHistory(); }
                @Override public List<TestChatMemory.Turn> savedTurns() { return client().savedTurns(); }
            };
        }, Executors.newSingleThreadExecutor(), HISTORY_IO, useMiuix, ChatImageLoader::thumbnail, messageContact);
    }

    AiTestChatDialog(Context context, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker) {
        this(context, config, sessions, worker, HISTORY_IO, false);
    }

    AiTestChatDialog(Context context, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, boolean useMiuix) {
        this(context, config, sessions, worker, HISTORY_IO, useMiuix);
    }

    AiTestChatDialog(Context context, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, ExecutorService historyWorker) {
        this(context, config, sessions, worker, historyWorker, false);
    }

    AiTestChatDialog(Context context, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, ExecutorService historyWorker, boolean useMiuix) {
        this(context, config, sessions, worker, historyWorker, useMiuix, ChatImageLoader::thumbnail);
    }

    AiTestChatDialog(Context baseContext, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, ExecutorService historyWorker, boolean useMiuix,
                     ThumbnailLoader thumbnailLoader) {
        this(baseContext, config, sessions, worker, historyWorker, useMiuix, thumbnailLoader, false);
    }

    AiTestChatDialog(Context baseContext, AiModelConfig config, SessionFactory sessions,
                     ExecutorService worker, ExecutorService historyWorker, boolean useMiuix,
                     ThumbnailLoader thumbnailLoader, boolean messageContact) {
        super(ModuleTheme.themedContext(baseContext), ModuleTheme.dialogTheme(baseContext));
        Context context = getContext();
        config.validate(true);
        this.sessions = sessions;
        this.worker = worker;
        this.historyWorker = historyWorker;
        this.thumbnailLoader = thumbnailLoader;
        this.useMiuix = useMiuix;
        assistantName = messageContact ? config.messageContactDisplayName() : "AI";
        session = sessions.create();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTitle(messageContact ? assistantName : "测试对话");

        LinearLayout root = new LinearLayout(context);
        root.setTag("ai.chat");
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(background(SURFACE, dp(20), LINE));
        root.setElevation(dp(4));
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (messageContact) {
            ImageView avatar = new ImageView(context);
            contactAvatar = avatar;
            avatar.setTag("ai.chat.contact_avatar");
            avatar.setContentDescription(assistantName + "头像");
            Bitmap bitmap = AiContactAvatar.load(config.messageContactAvatar);
            contactAvatarBitmap = bitmap;
            if (bitmap == null) avatar.setImageResource(android.R.drawable.sym_def_app_icon);
            else avatar.setImageBitmap(bitmap);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(36), dp(36));
            avatarParams.rightMargin = dp(8);
            header.addView(avatar, avatarParams);
        }
        TextView title = label(messageContact ? assistantName : "测试对话", 18, TEXT);
        title.setTag("ai.chat.title");
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (messageContact) {
            contactEdit = icon("修改联系人", "ai.chat.contact_edit", android.R.drawable.ic_menu_edit);
            contactEdit.setVisibility(View.GONE);
            header.addView(contactEdit, square());
        }
        ImageButton memory = icon("查看一级提示词", "ai.chat.memory", android.R.drawable.ic_menu_info_details);
        if (messageContact) memory.setVisibility(View.GONE);
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

        messages = new ListView(context);
        messages.setTag("ai.chat.messages");
        messages.setDividerHeight(0);
        messages.setClipToPadding(false);
        messages.setPadding(dp(8), dp(4), dp(8), dp(4));
        messages.setBackground(background(CANVAS, dp(12), LINE));
        messages.setAdapter(messageRows);
        LinearLayout.LayoutParams transcriptParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        transcriptParams.topMargin = dp(10);
        root.addView(messages, transcriptParams);

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
        ModuleTheme.bind(root);
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

    void setContactEditAction(Runnable action) {
        if (contactEdit == null) return;
        contactEdit.setVisibility(action == null ? View.GONE : View.VISIBLE);
        contactEdit.setOnClickListener(action == null ? null : ignored -> action.run());
        if (miuixSurface != null) miuixSurface.refresh();
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

    private void sendMessage(String prompt, List<ChatImage> images, List<Document> documents, ChatMessage retryAnswer) {
        if (disposed || busy || loadingImage
                || (prompt.isEmpty() && images.isEmpty() && documents.isEmpty())) return;
        cancelRestore();
        ChatMessage answer = retryAnswer;
        if (answer == null) {
            int imageBytes = imageBytes(images);
            while (!visibleImages.isEmpty() && (visibleImages.size() >= MAX_VISIBLE_TURNS
                    || visibleImageBytes + imageBytes > MAX_VISIBLE_IMAGE_BYTES)) {
                List<ChatImage> removed = visibleImages.removeFirst();
                visibleImageBytes -= imageBytes(removed);
                for (ChatImage image : removed) thumbnails.remove(image);
                transcript.remove(0);
                transcript.remove(0);
            }
            visibleImages.addLast(images);
            visibleImageBytes += imageBytes;
            ChatMessage user = addMessage("我", prompt, true, images);
            for (Document document : documents) user.documentLabels.add(document.name);
            answer = addMessage(assistantName, "正在回复…", false, Collections.emptyList());
        }
        answer.text = "正在回复…";
        answer.retry = false;
        messageRows.notifyDataSetChanged();
        if (prompt.equals(input.getText().toString().trim())) input.setText("");
        if (draftImages.equals(images)) { draftImages.clear(); renderAttachments(); }
        if (draftDocuments.equals(documents)) { draftDocuments.clear(); renderAttachments(); }
        int ticket = ++generation;
        Session active = session;
        Future<?> clearBarrier = historyClear;
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
                if (clearBarrier != null) clearBarrier.get();
                reply = active.reply(prompt, images, documentText(documents, images));
            }
            catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                error = cancelled;
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
        ChatMessage answer = turn.answer;
        if (failure == null) {
            answer.text = reply;
            answer.color = TEXT;
            state.setText("");
        } else {
            answer.text = turn.stopRequested ? "已停止" : errorMessage(failure);
            answer.color = turn.stopRequested ? MUTED : ERROR;
            state.setText(turn.stopRequested ? "已停止" : "发送失败");
            restoreDraft(turn.prompt, turn.images, turn.documents);
            addRetry(answer, turn.prompt, turn.images, turn.documents);
        }
        messageRows.notifyDataSetChanged();
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
        cancelRestore();
        cancelThumbnails();
        Session previous = session;
        session = sessions.create();
        transcript.clear();
        messageRows.notifyDataSetChanged();
        visibleImages.clear();
        visibleImageBytes = 0;
        draftImages.clear();
        draftDocuments.clear();
        renderAttachments();
        int ticket = ++clearGeneration;
        state.setText("");
        updateSend();
        // Keep the clear operation ordered after any in-flight history read, including on close.
        historyClear = historyWorker.submit(() -> {
            RuntimeException error = null;
            try { previous.clear(); }
            catch (RuntimeException failure) { error = failure; }
            RuntimeException failure = error;
            main.post(() -> {
                if (disposed || ticket != clearGeneration) return;
                if (!busy) state.setText(failure == null ? "" : "无法清空已保存的对话");
                updateSend();
            });
        });
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
        if (contactAvatar != null) contactAvatar.setImageDrawable(null);
        if (contactAvatarBitmap != null) contactAvatarBitmap.recycle();
        contactAvatarBitmap = null;
        cancelRequest();
        cancelImageLoad();
        cancelRestore();
        cancelThumbnails();
        if (attachmentMenu != null) attachmentMenu.dismiss();
        if (memoryDialog != null) memoryDialog.dismiss();
        worker.shutdownNow();
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

    private void addRetry(ChatMessage answer, String prompt, List<ChatImage> images, List<Document> documents) {
        answer.retry = true;
        answer.retryPrompt = prompt;
        answer.retryImages = new ArrayList<>(images);
        answer.retryDocuments = new ArrayList<>(documents);
    }

    private ChatMessage addMessage(String role, String text, boolean user, List<ChatImage> images) {
        ChatMessage message = new ChatMessage(role, text, user, images);
        transcript.add(message);
        messageRows.notifyDataSetChanged();
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
        int imageBudget = ChatCompletionClient.MAX_IMAGES_PER_MESSAGE - draftImages.size();
        imageLoad = worker.submit(() -> {
            LoadedMedia media = null;
            Exception error = null;
            try {
                ChatMediaLoader.Kind type = kind == AttachmentKind.IMAGE ? ChatMediaLoader.Kind.IMAGE
                        : kind == AttachmentKind.VIDEO ? ChatMediaLoader.Kind.VIDEO : ChatMediaLoader.Kind.DOCUMENT;
                ChatMediaLoader.Document document = ChatMediaLoader.loadAttachment(getContext(), uri, type, imageBudget);
                media = new LoadedMedia(document.name, document.text, document.images);
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
        if (documentText(next, draftImages).length() > 32768) throw new IllegalArgumentException("附件文本总计不能超过 32768 个字符");
        draftDocuments.add(document);
        renderAttachments();
        updateSend();
    }

    private void addMedia(LoadedMedia media) {
        addAttachment(media.name, media.text, media.images);
    }

    void addAttachment(String name, String text, List<ChatImage> images) {
        if (disposed || busy) return;
        if (draftImages.size() + images.size() > ChatCompletionClient.MAX_IMAGES_PER_MESSAGE)
            throw new IllegalArgumentException("每条消息最多包含 4 张图片或视频帧");
        if (!text.isEmpty() && draftDocuments.size() >= 4)
            throw new IllegalArgumentException("每条消息最多添加 4 个文件或视频说明");
        if (!text.isEmpty()) {
            Document document = new Document(name, text, images);
            List<Document> next = new ArrayList<>(draftDocuments);
            next.add(document);
            List<ChatImage> combined = new ArrayList<>(draftImages);
            combined.addAll(images);
            if (documentText(next, combined).length() > 32768)
                throw new IllegalArgumentException("附件文本总计不能超过 32768 个字符");
            draftDocuments.add(document);
        }
        draftImages.addAll(images);
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
            remove.setOnClickListener(ignored -> {
                Document group = null;
                for (Document document : draftDocuments) if (document.images.contains(image)) { group = document; break; }
                if (group != null) { draftImages.removeAll(group.images); draftDocuments.remove(group); }
                else draftImages.remove(image);
                renderAttachments(); updateSend();
            });
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
            remove.setOnClickListener(ignored -> {
                draftDocuments.remove(document); draftImages.removeAll(document.images);
                renderAttachments(); updateSend();
            });
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
        Bitmap cached = thumbnails.get(image);
        if (cached != null) view.setImageBitmap(cached);
        else {
            thumbnailLoads.removeIf(Future::isDone);
            int ticket = thumbnailGeneration;
            WeakReference<ImageView> target = new WeakReference<>(view);
            thumbnailLoads.add(historyWorker.submit(() -> {
                Bitmap bitmap = thumbnail(image);
                main.post(() -> {
                    if (disposed || ticket != thumbnailGeneration) {
                        if (bitmap != null) bitmap.recycle();
                        return;
                    }
                    ImageView current = target.get();
                    if (bitmap == null) return;
                    if (!retains(image)) { bitmap.recycle(); return; }
                    Bitmap existing = thumbnails.get(image);
                    if (existing == null) thumbnails.put(image, bitmap);
                    else { bitmap.recycle(); }
                    if (current != null) current.setImageBitmap(thumbnails.get(image));
                    if (miuixSurface != null) miuixSurface.refresh();
                });
            }));
        }
        return view;
    }

    private boolean retains(ChatImage image) {
        if (draftImages.contains(image)) return true;
        for (List<ChatImage> images : visibleImages) if (images.contains(image)) return true;
        return false;
    }

    private Bitmap thumbnail(ChatImage image) {
        if (Thread.currentThread().isInterrupted()) return null;
        try { return thumbnailLoader.load(image); }
        catch (RuntimeException | OutOfMemoryError unavailable) { return null; }
    }

    private void cancelThumbnails() {
        ++thumbnailGeneration;
        for (Future<?> load : thumbnailLoads) load.cancel(true);
        thumbnailLoads.clear();
        thumbnails.clear();
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
        final ChatMessage answer;
        volatile boolean stopRequested;
        boolean started;

        RequestTurn(int ticket, String prompt, List<ChatImage> images, List<Document> documents, ChatMessage answer) {
            this.ticket = ticket;
            this.prompt = prompt;
            this.images = images;
            this.documents = documents;
            this.answer = answer;
        }
    }

    private static final class ChatMessage {
        final String role;
        final boolean user;
        final List<ChatImage> images;
        String text;
        final List<String> documentLabels = new ArrayList<>();
        int color = TEXT;
        boolean retry;
        String retryPrompt;
        List<ChatImage> retryImages = Collections.emptyList();
        List<Document> retryDocuments = Collections.emptyList();

        ChatMessage(String role, String text, boolean user, List<ChatImage> images) {
            this.role = role;
            this.text = text;
            this.user = user;
            this.images = new ArrayList<>(images);
        }
    }

    private final class TranscriptRows extends BaseAdapter {
        @Override public int getCount() { return transcript.size(); }
        @Override public ChatMessage getItem(int position) { return transcript.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            MessageRow row = recycled instanceof MessageRow ? (MessageRow) recycled : new MessageRow(getContext());
            row.bind(getItem(position));
            return row;
        }
    }

    private final class MessageRow extends LinearLayout {
        private final LinearLayout bubble;
        private final TextView role;
        private final TextView body;
        private final TextView document;
        private final LinearLayout images;
        private final ImageButton retry;

        MessageRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            bubble = new LinearLayout(context);
            bubble.setOrientation(VERTICAL);
            bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
            role = label("", 12, MUTED);
            role.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            bubble.addView(role, wrap());
            body = label("", 15, TEXT);
            body.setTextIsSelectable(true);
            body.setPadding(0, dp(4), 0, 0);
            bubble.addView(body, wrap());
            document = label("", 13, MUTED);
            document.setTag("ai.chat.document");
            bubble.addView(document, wrap());
            images = new LinearLayout(context);
            images.setOrientation(VERTICAL);
            images.setTag("ai.chat.message.images");
            bubble.addView(images, wrap());
            retry = icon("重新发送", "ai.chat.retry", android.R.drawable.ic_menu_rotate);
            bubble.addView(retry, square());
            addView(bubble, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(ChatMessage message) {
            int color = message.user ? ACCENT : MUTED;
            role.setText(message.role);
            role.setTextColor(color);
            body.setTag(message.user ? "ai.chat.user" : "ai.chat.answer");
            body.setText(message.text);
            body.setTextColor(message.color);
            document.setVisibility(message.documentLabels.isEmpty() ? GONE : VISIBLE);
            document.setText(message.documentLabels.isEmpty() ? ""
                    : "已保存的附件：" + TextUtils.join("、", message.documentLabels));
            images.removeAllViews();
            for (ChatImage image : message.images) {
                images.addView(preview(image), new LayoutParams(dp(128), dp(96)));
            }
            retry.setVisibility(message.retry ? VISIBLE : GONE);
            retry.setOnClickListener(message.retry ? ignored -> sendMessage(message.retryPrompt,
                    message.retryImages, message.retryDocuments, message) : null);
            bubble.setBackground(background(message.user ? Color.rgb(225, 243, 237) : SURFACE, dp(14),
                    message.user ? Color.rgb(210, 235, 227) : LINE));
            setGravity(message.user ? Gravity.END : Gravity.START);
            removeView(bubble);
            LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (message.user) params.leftMargin = dp(24);
            else params.rightMargin = dp(24);
            params.bottomMargin = dp(10);
            addView(bubble, params);
        }
    }

    private void restoreConversation() {
        int ticket = ++restoreGeneration;
        Session active = session;
        restoreLoad = historyWorker.submit(() -> {
            List<TestChatMemory.Turn> saved;
            try { saved = active.savedTurns(); }
            catch (RuntimeException failure) { saved = Collections.emptyList(); }
            List<TestChatMemory.Turn> retained = new ArrayList<>();
            Map<ChatImage, Bitmap> decoded = new IdentityHashMap<>();
            int bytes = 0;
            for (int index = saved.size() - 1; index >= 0 && retained.size() < MAX_VISIBLE_TURNS; index--) {
                if (Thread.currentThread().isInterrupted()) { recycle(decoded); return; }
                TestChatMemory.Turn turn = saved.get(index);
                int imageBytes = imageBytes(turn.images);
                if (bytes + imageBytes > MAX_VISIBLE_IMAGE_BYTES) continue;
                bytes += imageBytes;
                retained.add(0, turn);
                for (ChatImage image : turn.images) decoded.put(image, thumbnail(image));
            }
            main.post(() -> {
                if (disposed || ticket != restoreGeneration) { recycle(decoded); return; }
                restoreLoad = null;
                for (Map.Entry<ChatImage, Bitmap> item : decoded.entrySet()) {
                    if (item.getValue() != null) thumbnails.put(item.getKey(), item.getValue());
                }
                for (TestChatMemory.Turn turn : retained) {
                    visibleImages.addLast(turn.images);
                    visibleImageBytes += imageBytes(turn.images);
                    ChatMessage user = addMessage("我", turn.prompt, true, turn.images);
                    if (!turn.document.isEmpty()) {
                        user.documentLabels.add("文本");
                    }
                    addMessage(assistantName, turn.answer, false, Collections.emptyList());
                }
                scrollToBottom();
                updateSend();
            });
        });
    }

    private void cancelRestore() {
        ++restoreGeneration;
        if (restoreLoad != null) restoreLoad.cancel(true);
        restoreLoad = null;
    }

    private static void recycle(Map<ChatImage, Bitmap> images) {
        for (Bitmap bitmap : images.values()) if (bitmap != null) bitmap.recycle();
    }

    private static String documentText(List<Document> documents, List<ChatImage> images) {
        StringBuilder text = new StringBuilder();
        for (Document document : documents) {
            if (text.length() > 0) text.append("\n\n");
            text.append("[文件：").append(document.name).append("]\n");
            if (!document.images.isEmpty()) {
                List<String> positions = new ArrayList<>();
                for (ChatImage image : document.images) {
                    int position = images.indexOf(image);
                    if (position >= 0) positions.add(String.valueOf(position + 1));
                }
                text.append("对应本条消息的图片序号（从 1 开始）：")
                        .append(TextUtils.join("、", positions)).append("。\n");
            }
            text.append(document.text);
        }
        return text.toString();
    }

    private static final class Document {
        final String name;
        final String text;
        final List<ChatImage> images;
        Document(String name, String text) {
            this(name, text, Collections.emptyList());
        }
        Document(String name, String text, List<ChatImage> images) {
            this.name = name == null || name.isEmpty() ? "文件" : name;
            this.text = text == null ? "" : text;
            this.images = new ArrayList<>(images);
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

    private void scrollToBottom() {
        if (messages.getCount() > 0) messages.post(() -> messages.setSelection(messages.getCount() - 1));
    }

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
