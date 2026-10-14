package com.douai.module.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.ui.miuix.MiuixSettingsViews;

/** Offline help with a searchable index and one owned reading window. */
final class HelpDialog {
    private final Context context;
    private final boolean miuix;
    private final LinearLayout content;
    private final LinearLayout topics;
    private final ScrollView scroll;
    private final EditText search;
    private final AlertDialog dialog;
    private HelpContent.Section current;
    private int indexScroll;

    HelpDialog(Context context, boolean miuix) {
        this.context = context;
        this.miuix = miuix;
        content = column();
        content.setTag("help.page");
        topics = column();
        search = miuix ? MiuixFormControls.input(context, "搜索帮助", "", false) : new EditText(context);
        search.setTag("help.search");
        search.setHint("搜索帮助");
        search.setContentDescription("搜索帮助");
        search.setSingleLine(true);
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (current == null) {
                    indexScroll = 0;
                    renderTopics();
                    scroll.post(() -> { if (current == null) scroll.scrollTo(0, 0); });
                }
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        scroll = new ScrollView(context);
        scroll.setTag("help.scroll");
        scroll.addView(content);
        dialog = new MiuixDialogBuilder(context, miuix).setTitle("帮助").setView(scroll)
                .setNegativeButton("返回", null).setOnKeyListener((owner, code, event) -> {
                    if (code != KeyEvent.KEYCODE_BACK || current == null) return false;
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) showIndex();
                    return true;
                }).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(view -> { if (current == null) dialog.dismiss(); else showIndex(); }));
        showIndex();
    }

    AlertDialog dialog() { return dialog; }

    private void showIndex() {
        current = null;
        dialog.setTitle("帮助");
        content.removeAllViews();
        content.addView(MiuixFormControls.viewFor(search), matchWrap());
        content.addView(topics, matchWrap());
        renderTopics();
        scroll.post(() -> { if (current == null) scroll.scrollTo(0, indexScroll); });
    }

    private void renderTopics() {
        topics.removeAllViews();
        for (HelpContent.Section section : HelpContent.search(search.getText().toString())) {
            View row;
            if (miuix) {
                row = new MiuixSettingsViews.Option(context, section.title, "", null, true,
                        () -> showSection(section)).getView();
            } else {
                TextView title = text(section.title, 16);
                title.setMinimumHeight(dp(48));
                title.setGravity(android.view.Gravity.CENTER_VERTICAL);
                title.setFocusable(true);
                title.setOnClickListener(ignored -> showSection(section));
                row = title;
            }
            row.setTag("help.topic." + section.id);
            topics.addView(row, matchWrap());
        }
        if (topics.getChildCount() == 0) {
            TextView empty = text("没有匹配的帮助内容", 14);
            empty.setTag("help.empty");
            topics.addView(empty, matchWrap());
        }
    }

    private void showSection(HelpContent.Section section) {
        indexScroll = scroll.getScrollY();
        current = section;
        search.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        dialog.setTitle(section.title);
        content.removeAllViews();
        TextView body = text("", 15);
        SpannableStringBuilder paragraphs = new SpannableStringBuilder();
        for (HelpContent.Part part : section.parts) {
            if (paragraphs.length() > 0) paragraphs.append("\n\n");
            int start = paragraphs.length();
            paragraphs.append(part.title);
            paragraphs.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, paragraphs.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            paragraphs.append("\n").append(part.text);
        }
        body.setText(paragraphs);
        body.setTag("help.body");
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(3), 1.1f);
        content.addView(body, matchWrap());
        scroll.post(() -> { if (current == section) scroll.scrollTo(0, 0); });
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(10), dp(4), dp(10), dp(8));
        return layout;
    }

    private TextView text(String value, int size) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setPadding(dp(8), dp(10), dp(8), dp(10));
        text.setTextColor(android.graphics.Color.rgb(36, 42, 48));
        return text;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
