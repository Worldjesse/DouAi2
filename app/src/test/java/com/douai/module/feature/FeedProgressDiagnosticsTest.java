package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.widget.ChapterOuterSeekBar;
import com.ss.android.ugc.aweme.feed.widget.LineProgressBar;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class FeedProgressDiagnosticsTest {
    @Test public void capturesProgressOutsideCardAndItsModuleOwnedAncestor() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(activity);
        FrameLayout card = new FrameLayout(activity);
        page.addView(card);
        FrameLayout overlay = new FrameLayout(activity);
        page.addView(overlay);
        LineProgressBar progress = new LineProgressBar(activity);
        overlay.addView(progress);
        activity.setContentView(page);

        String snapshot = join(FeedProgressDiagnostics.capture(page, card,
                view -> view == overlay ? "BOTTOM_TOOLS alpha=-2" : null));
        assertTrue(snapshot.contains("class=" + LineProgressBar.class.getName()));
        assertTrue(snapshot.contains("inCard=false"));
        assertTrue(snapshot.contains("appliedStyle=BOTTOM_TOOLS alpha=-2"));
        assertTrue(snapshot.contains("ancestor[0]"));
        assertEquals(View.VISIBLE, overlay.getVisibility());
        activity.finish();
    }

    @Test public void capturesZeroSizeAndClippingWithoutChangingTheViews() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout card = new FrameLayout(context);
        card.setClipChildren(true);
        card.setClipToPadding(false);
        card.setPadding(12, 13, 14, 15);
        ChapterOuterSeekBar progress = new ChapterOuterSeekBar(context);
        progress.setVisibility(View.INVISIBLE);
        progress.setAlpha(0.25f);
        progress.setTranslationX(7f);
        card.addView(progress, new FrameLayout.LayoutParams(0, 0));
        card.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        card.layout(0, 0, 100, 100);

        String snapshot = join(FeedProgressDiagnostics.capture(card, card, view -> null));
        assertTrue(snapshot.contains("measured=0x0 layout=0x0"));
        assertTrue(snapshot.contains("visibility=4 alpha=0.25"));
        assertTrue(snapshot.contains("translation=7.0,0.0,0.0"));
        assertTrue(snapshot.contains("globalVisible=false:"));
        assertTrue(snapshot.contains("clipChildren=true clipToPadding=false"));
        assertTrue(snapshot.contains("inCard=true"));
        assertEquals(View.INVISIBLE, progress.getVisibility());
        assertEquals(0.25f, progress.getAlpha(), 0f);
        assertEquals(0, progress.getLayoutParams().height);
    }

    @Test public void keepsCurrentCardAndPageSeekbarWhenCachedCardsFillTheCandidateBudget() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout page = new FrameLayout(context);
        for (int index = 0; index < 10; index++) page.addView(new LineProgressBar(context));
        FrameLayout card = new FrameLayout(context);
        card.addView(new ChapterOuterSeekBar(context));
        page.addView(card);
        page.addView(new com.ss.android.ugc.aweme.feed.ui.seekbar.CustomizedUISeekBar(context));
        String snapshot = join(FeedProgressDiagnostics.capture(page, card, view -> null));
        assertTrue(snapshot.contains("Feed progress[0] class=" + ChapterOuterSeekBar.class.getName()));
        assertTrue(snapshot.contains("Feed progress[1] class="
                + com.ss.android.ugc.aweme.feed.ui.seekbar.CustomizedUISeekBar.class.getName()));
    }

    @Test public void neverReadsTextDescriptionOrTagsAndOnlyIncludesHostResourceNames() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout page = new PrivateFrameLayout(context);
        TextView text = new TextView(context);
        text.setText("private video caption 12345");
        text.setContentDescription("private account 67890");
        text.setTag("private card tag");
        page.addView(text);
        LineProgressBar progress = new LineProgressBar(context);
        progress.setContentDescription("private progress description");
        progress.setTag("private progress tag");
        page.addView(progress);
        ResourceView foreignMount = new ResourceView(context, "private.package", "private_name");
        foreignMount.setId(2131378139);
        page.addView(foreignMount);

        String snapshot = join(FeedProgressDiagnostics.capture(page, page, view -> null));
        assertFalse(snapshot.contains("private"));
        assertTrue(snapshot.contains(LineProgressBar.class.getName()));
        assertFalse(snapshot.contains("class=" + ResourceView.class.getName()));
        assertFalse(snapshot.contains("unavailable"));
    }

    @Test public void recognizesMountsOnlyWhenBothPackageAndExactResourceNameMatch() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout page = new FrameLayout(context);
        ResourceView progressMount = new ResourceView(context, HostResourceCatalog.VERIFIED_PACKAGE, "i_k");
        progressMount.setId(2131378139);
        page.addView(progressMount);
        ResourceView chapterMount = new ResourceView(context, HostResourceCatalog.VERIFIED_PACKAGE, "i_f");
        chapterMount.setId(2131378134);
        page.addView(chapterMount);
        ResourceView changedName = new ResourceView(context, HostResourceCatalog.VERIFIED_PACKAGE, "changed");
        changedName.setId(2131378139);
        page.addView(changedName);
        ResourceView foreignPackage = new ResourceView(context, "com.example", "i_f");
        foreignPackage.setId(2131378134);
        page.addView(foreignPackage);

        List<String> snapshot = FeedProgressDiagnostics.capture(page, page, view -> null);
        assertEquals(2, snapshot.stream().filter(line -> line.contains(" class=" + ResourceView.class.getName())).count());
        assertTrue(join(snapshot).contains("res=i_k"));
        assertTrue(join(snapshot).contains("res=i_f"));
        assertFalse(join(snapshot).contains("res=changed"));
    }

    @Test public void outputAndTraversalAreBoundedAndStyleFailuresAreContained() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout page = new FrameLayout(context);
        for (int index = 0; index < 20; index++) {
            FrameLayout parent = page;
            for (int depth = 0; depth < 20; depth++) {
                FrameLayout child = new FrameLayout(context);
                parent.addView(child);
                parent = child;
            }
            parent.addView(new LineProgressBar(context));
        }
        String longStyle = new String(new char[10000]).replace('\0', 'x');
        List<String> snapshot = FeedProgressDiagnostics.capture(page, page, view -> longStyle);
        assertEquals(6, snapshot.stream().filter(line -> line.startsWith("Feed progress[")
                && !line.contains("ancestor") && line.contains(" class=")).count());
        assertTrue(snapshot.size() <= 84);
        assertTrue(snapshot.stream().allMatch(line -> line.length() <= 1800));
        assertTrue(snapshot.stream().mapToInt(String::length).sum() <= 18000);
        assertFalse(join(snapshot).contains("ancestor[12]"));
        assertTrue(join(FeedProgressDiagnostics.capture(page, page, view -> {
            throw new IllegalStateException("private exception payload");
        })).contains("appliedStyle=unavailable"));

        FrameLayout deepRoot = new FrameLayout(context);
        FrameLayout deepest = deepRoot;
        for (int depth = 0; depth < 65; depth++) {
            FrameLayout child = new FrameLayout(context);
            deepest.addView(child);
            deepest = child;
        }
        deepest.addView(new LineProgressBar(context));
        assertTrue(join(FeedProgressDiagnostics.capture(deepRoot, deepRoot, view -> null)).contains("candidates=0"));
        FrameLayout wideRoot = new FrameLayout(context);
        for (int index = 0; index < 4096; index++) wideRoot.addView(new View(context));
        wideRoot.addView(new LineProgressBar(context));
        assertTrue(join(FeedProgressDiagnostics.capture(wideRoot, wideRoot, view -> null)).contains("candidates=0"));
    }

    private static String join(List<String> lines) { return String.join("\n", lines); }

    private static final class PrivateFrameLayout extends FrameLayout {
        PrivateFrameLayout(Context context) { super(context); }
        @Override public CharSequence getContentDescription() { throw new AssertionError("description read"); }
        @Override public Object getTag() { throw new AssertionError("tag read"); }
    }

    private static final class ResourceView extends View {
        private final Resources testResources;
        ResourceView(Context context, String packageName, String resourceName) {
            super(context);
            Resources original = context.getResources();
            testResources = new Resources(original.getAssets(), original.getDisplayMetrics(), original.getConfiguration()) {
                @Override public String getResourcePackageName(int id) { return packageName; }
                @Override public String getResourceEntryName(int id) {
                    if (id == ResourceView.this.getId()) return resourceName;
                    String known = com.douai.module.scan.HostAdaptation.PROFILE_408.getFeedResources().get(id);
                    if (known == null) throw new Resources.NotFoundException();
                    return known;
                }
            };
        }
        @Override public Resources getResources() {
            return testResources == null ? super.getResources() : testResources;
        }
    }
}
