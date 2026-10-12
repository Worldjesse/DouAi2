package com.douai.module.feature;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HostResourceViewTest {
    @Test public void acceptsOnlyTheVerifiedRuntimeResourceAndRejectsLaterReuse() {
        Context base = RuntimeEnvironment.getApplication();
        FixtureResources resources = new FixtureResources(base.getResources());
        Context context = new ContextWrapper(base) {
            @Override public Resources getResources() { return resources; }
        };
        FrameLayout root = new FrameLayout(context);
        View target = new View(context);
        target.setId(2131379757);
        root.addView(target);
        assertSame(target, HostResourceCatalog.findVerifiedView(root, 2131379757));
        resources.entry = "reused_for_another_action";
        assertNull(HostResourceCatalog.findVerifiedView(root, 2131379757));
        resources.entry = "j_l";
        resources.packageName = "com.douai.module";
        assertNull(HostResourceCatalog.findVerifiedView(root, 2131379757));
    }

    @Test public void unknownAndMissingIdentifiersCannotSelectAView() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout root = new FrameLayout(context);
        View unknown = new View(context);
        unknown.setId(0x7f0a9734);
        root.addView(unknown);
        assertNull(HostResourceCatalog.findVerifiedView(root, unknown.getId()));
        assertNull(HostResourceCatalog.findVerifiedView(root, 2131379757));
        assertNull(HostResourceCatalog.findVerifiedView(null, 2131379757));
    }

    private static final class FixtureResources extends Resources {
        String packageName = HostResourceCatalog.VERIFIED_PACKAGE;
        String entry = "j_l";
        FixtureResources(Resources base) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
        }
        @Override public String getResourcePackageName(int id) { return packageName; }
        @Override public String getResourceEntryName(int id) { return entry; }
    }
}
