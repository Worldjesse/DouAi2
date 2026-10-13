package com.douai.module.feature;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** Bounded, read-only snapshots of progress geometry and module-owned styles. */
final class FeedProgressDiagnostics {
    private static final int MAX_NODES = 4096;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_CANDIDATES = 6;
    private static final int MAX_ANCESTORS = 12;
    private static final int MAX_LINE_CHARS = 1800;
    private static final int MAX_OUTPUT_CHARS = 18000;
    private static final String[] PROGRESS_CLASSES = {
            "com.ss.android.ugc.aweme.feed.ui.seekbar.CustomizedUISeekBar",
            "com.ss.android.ugc.aweme.feed.widget.LineProgressBar",
            "com.ss.android.ugc.aweme.shortvideo.ui.NewVideoPlayerProgressbar",
            "com.ss.android.ugc.aweme.feed.widget.ChapterOuterSeekBar",
            "com.ss.android.ugc.aweme.o.oseekbar.view.OSeekBar",
            "com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView"
    };

    private FeedProgressDiagnostics() { }

    static List<String> capture(View windowRoot, View cardRoot, Function<View, String> appliedStyle) {
        if (windowRoot == null) return Collections.emptyList();
        try {
            List<View> widgets = new ArrayList<>();
            List<View> mounts = new ArrayList<>();
            collect(windowRoot, cardRoot, 0, new int[]{MAX_NODES}, widgets, mounts);
            List<View> candidates = new ArrayList<>(widgets);
            for (View mount : mounts) {
                if (candidates.size() == MAX_CANDIDATES) break;
                candidates.add(mount);
            }
            Output output = new Output();
            if (candidates.isEmpty()) {
                output.add("Feed progress: candidates=0, searchLimit=" + MAX_NODES + "/" + MAX_DEPTH);
                return output.lines;
            }
            // Emit all candidates first so an ancestor budget cannot omit another progress variant.
            for (int index = 0; index < candidates.size(); index++) {
                output.add(snapshot("Feed progress[" + index + "]", candidates.get(index), cardRoot, appliedStyle));
            }
            for (int index = 0; index < candidates.size(); index++) {
                View ancestor = parent(candidates.get(index));
                int depth = 0;
                while (ancestor != null && depth < MAX_ANCESTORS) {
                    if (!output.add(snapshot("Feed progress[" + index + "] ancestor[" + depth + "]",
                            ancestor, cardRoot, appliedStyle))) return output.lines;
                    ancestor = parent(ancestor);
                    depth++;
                }
                if (ancestor != null) output.add("Feed progress[" + index + "]: ancestorLimit=" + MAX_ANCESTORS);
            }
            return output.lines;
        } catch (Throwable ignored) {
            return Collections.singletonList("Feed progress: snapshot unavailable");
        }
    }

    private static void collect(View view, View cardRoot, int depth, int[] remaining,
                                List<View> widgets, List<View> mounts) {
        if (view == null || remaining[0]-- <= 0 || depth > MAX_DEPTH) return;
        try {
            if (isProgressClass(view)) retainCandidate(widgets, view, cardRoot);
            else if (mounts.size() < MAX_CANDIDATES && isProgressMount(view)) mounts.add(view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount() && remaining[0] > 0; index++) {
                    collect(group.getChildAt(index), cardRoot, depth + 1, remaining, widgets, mounts);
                }
            }
        } catch (Throwable ignored) {
            // A host-specific getter must not prevent snapshots of sibling branches.
        }
    }

    private static void retainCandidate(List<View> widgets, View view, View cardRoot) {
        int priority = priority(view, cardRoot);
        int index = 0;
        while (index < widgets.size() && priority(widgets.get(index), cardRoot) <= priority) index++;
        if (index >= MAX_CANDIDATES) return;
        widgets.add(index, view);
        if (widgets.size() > MAX_CANDIDATES) widgets.remove(MAX_CANDIDATES);
    }

    private static int priority(View view, View cardRoot) {
        if (isWithin(view, cardRoot)) return 0;
        // The page seekbar follows cached feed cards in the window tree; keep it ahead of those cards.
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (PROGRESS_CLASSES[0].equals(type.getName())) return 1;
            if (PROGRESS_CLASSES[5].equals(type.getName())) return 1;
        }
        return 2;
    }

    private static boolean isProgressClass(View view) {
        int depth = 0;
        for (Class<?> type = view.getClass(); type != null && depth++ < MAX_DEPTH; type = type.getSuperclass()) {
            for (String name : PROGRESS_CLASSES) if (name.equals(type.getName())) return true;
        }
        return false;
    }

    private static boolean isProgressMount(View view) {
        int id = view.getId();
        String name = id == 2131378120 ? "i=8" : id == 2131378117 ? "i=5" : null;
        return name != null && name.equals(resourceName(view));
    }

    private static String resourceName(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID || !HostResourceCatalog.VERIFIED_PACKAGE.equals(
                    view.getResources().getResourcePackageName(id))) return "?";
            return field(view.getResources().getResourceEntryName(id), 128);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String snapshot(String prefix, View view, View cardRoot, Function<View, String> appliedStyle) {
        StringBuilder value = new StringBuilder(prefix);
        try {
            value.append(" class=").append(field(view.getClass().getName(), 256))
                    .append(" id=0x").append(Integer.toHexString(view.getId()))
                    .append(" res=").append(resourceName(view))
                    .append(" inCard=").append(isWithin(view, cardRoot))
                    .append(" visibility=").append(view.getVisibility())
                    .append(" alpha=").append(view.getAlpha())
                    .append(" measured=").append(view.getMeasuredWidth()).append('x').append(view.getMeasuredHeight())
                    .append(" layout=").append(view.getWidth()).append('x').append(view.getHeight())
                    .append(" bounds=").append(view.getLeft()).append(',').append(view.getTop())
                    .append(',').append(view.getRight()).append(',').append(view.getBottom())
                    .append(" translation=").append(view.getTranslationX()).append(',').append(view.getTranslationY())
                    .append(',').append(view.getTranslationZ());
            Rect visible = new Rect();
            boolean hasVisibleRect = view.getGlobalVisibleRect(visible);
            value.append(" globalVisible=").append(hasVisibleRect).append(':')
                    .append(visible.left).append(',').append(visible.top).append(',')
                    .append(visible.right).append(',').append(visible.bottom);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                value.append(" clipChildren=").append(group.getClipChildren())
                        .append(" clipToPadding=").append(group.getClipToPadding());
            }
            String style = null;
            if (appliedStyle != null) {
                try { style = appliedStyle.apply(view); }
                catch (Throwable ignored) { style = "unavailable"; }
            }
            value.append(" appliedStyle=").append(style == null ? "none" : field(style, 256));
        } catch (Throwable ignored) {
            value.append(" unavailable");
        }
        return value.toString();
    }

    private static View parent(View view) {
        try { return view.getParent() instanceof View ? (View) view.getParent() : null; }
        catch (Throwable ignored) { return null; }
    }

    private static boolean isWithin(View view, View root) {
        if (root == null) return false;
        for (int depth = 0; view != null && depth <= MAX_DEPTH; depth++, view = parent(view)) {
            if (view == root) return true;
        }
        return false;
    }

    private static String field(String value, int limit) {
        if (value == null) return "?";
        StringBuilder result = new StringBuilder(Math.min(value.length(), limit));
        for (int index = 0; index < value.length() && index < limit; index++) {
            char character = value.charAt(index);
            result.append(character < ' ' || character == 127 ? '?' : character);
        }
        return result.toString();
    }

    private static final class Output {
        final List<String> lines = new ArrayList<>();
        int characters;

        boolean add(String line) {
            String bounded = field(line, MAX_LINE_CHARS);
            if (characters + bounded.length() > MAX_OUTPUT_CHARS) return false;
            lines.add(bounded);
            characters += bounded.length();
            return true;
        }
    }
}
