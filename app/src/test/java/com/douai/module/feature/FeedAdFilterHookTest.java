package com.douai.module.feature;

import com.douai.module.config.FeatureSettings;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class FeedAdFilterHookTest {
    public static final class FeedItemListFixture {
        public List<AwemeFixture> items;
        public List<AwemeFixture> preloadAds;
        public List<AwemeFixture> preloadAwemes;

        FeedItemListFixture(List<AwemeFixture> items) {
            this.items = items;
        }
    }

    public static final class AwemeFixture {
        private final boolean ad;
        private final boolean live;
        private final boolean image;
        private int awemeType;
        public Object anchorInfo;
        public String desc;
        public Integer duration;
        public Object video;
        public final AwemeStatisticsFixture statistics;

        AwemeFixture(boolean ad) {
            this(ad, false, false, null, "", 20_000, 2_000);
        }

        AwemeFixture(boolean ad, boolean live, boolean image, Object anchorInfo,
                     String desc, Integer duration, long likeCount) {
            this.ad = ad;
            this.live = live;
            this.image = image;
            this.awemeType = image ? 68 : 0;
            this.anchorInfo = anchorInfo;
            this.desc = desc;
            this.duration = duration;
            this.statistics = new AwemeStatisticsFixture(likeCount);
        }

        public boolean getIsAdAweme() {
            return ad;
        }

        public boolean isLive() {
            return live;
        }

        public boolean isImage() {
            return image;
        }

        public int getAwemeType() {
            return awemeType;
        }
    }

    public static final class AwemeStatisticsFixture {
        public final long d;

        AwemeStatisticsFixture(long likeCount) {
            this.d = likeCount;
        }
    }

    public static final class VideoDurationFixture {
        public int videoLength;
        VideoDurationFixture(int videoLength) { this.videoLength = videoLength; }
    }

    @Test public void longVideoAdapterUsesVideoLengthWhenAwemeDurationIsZero() throws Throwable {
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        FeatureSettings current = new FeatureSettings(false, false, true,
                false, false, false, false, false, "", false, 0, false, 0,
                false, false, false, false, false);
        FeedAdFilterHook hook = FeedAdFilterHook.install(createFramework(installed), createReport(),
                getClass().getClassLoader(), current, ignored -> { });
        hook.setOptions(new com.douai.module.config.LocalOptions(Collections.singletonMap("hide_long_videos", true)));
        AwemeFixture longVideo = new AwemeFixture(false);
        longVideo.duration = 0;
        longVideo.video = new VideoDurationFixture(121_000);
        AwemeFixture ordinary = new AwemeFixture(false);
        FeedItemListFixture page = new FeedItemListFixture(Arrays.asList(longVideo, ordinary));
        try {
            installed.get().intercept(createChain(page, new ApiResponseFixture("https://api.example/aweme/v1/feed/")));
            assertEquals(Collections.singletonList(ordinary), page.items);
        } finally { hook.close(); }
    }

    public static final class ApiResponseFixture {
        private final String url;

        ApiResponseFixture(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }

    public static final class ResponseWrapperFixture {
        public static ResponseWrapperFixture success(Object body, ApiResponseFixture response) {
            return new ResponseWrapperFixture();
        }
    }

    public static final class FeedApiFixture {
        public bolts.Task fetchRecommendFeedImmediate() {
            return null;
        }
    }

    @Test
    public void registeredCallbackTracksSettingsAndLeavesOtherUrlsUntouched() throws Throwable {
        AtomicReference<XposedInterface.Hooker> installedHooker = new AtomicReference<>();
        XposedInterface framework = createFramework(installedHooker);
        FeedAdFilterHook hook = FeedAdFilterHook.install(framework, createReport(),
                getClass().getClassLoader(), false, ignored -> { });

        AwemeFixture regular = new AwemeFixture(false);
        AwemeFixture ad = new AwemeFixture(true);
        List<AwemeFixture> source = Arrays.asList(regular, ad);
        FeedItemListFixture firstPage = new FeedItemListFixture(source);
        installedHooker.get().intercept(createChain(firstPage,
                new ApiResponseFixture("https://api.example/aweme/v2/feed/")));
        assertEquals(source, firstPage.items);

        hook.setEnabled(true);
        FeedItemListFixture filteredPage = new FeedItemListFixture(source);
        installedHooker.get().intercept(createChain(filteredPage,
                new ApiResponseFixture("https://api.example/aweme/v2/feed/?count=6")));
        assertEquals(Collections.singletonList(regular), filteredPage.items);
        assertEquals(1, hook.getFilteredPages());

        FeedItemListFixture otherEndpoint = new FeedItemListFixture(source);
        installedHooker.get().intercept(createChain(otherEndpoint,
                new ApiResponseFixture("https://api.example/aweme/v1/search/")));
        assertEquals(source, otherEndpoint.items);
        assertFalse(otherEndpoint.items.isEmpty());
        hook.close();
    }

    @Test
    public void runtimeClassifierUsesAllFeedAttributesAndThresholds() throws Throwable {
        AtomicReference<XposedInterface.Hooker> installedHooker = new AtomicReference<>();
        FeedAdFilterHook hook = FeedAdFilterHook.install(createFramework(installedHooker), createReport(),
                getClass().getClassLoader(), filterSettings(false, true, false, false,
                        false, "", false, 0, false, 0), ignored -> { });
        AwemeFixture keep = new AwemeFixture(false);

        assertFiltered(hook, installedHooker.get(),
                new AwemeFixture(false, true, false, null, "", 20_000, 2_000), keep);
        hook.setSettings(filterSettings(false, false, true, false,
                false, "", false, 0, false, 0));
        assertFiltered(hook, installedHooker.get(),
                new AwemeFixture(false, false, true, null, "", 20_000, 2_000), keep);
        hook.setSettings(filterSettings(false, false, false, true,
                false, "", false, 0, false, 0));
        AwemeFixture recommendation = new AwemeFixture(false);
        recommendation.awemeType = 145;
        assertFiltered(hook, installedHooker.get(), recommendation, keep);
        hook.setSettings(filterSettings(false, false, false, false,
                true, "spoiler", false, 0, false, 0));
        assertFiltered(hook, installedHooker.get(),
                new AwemeFixture(false, false, false, null, "Possible SPOILER ahead", 20_000, 2_000), keep);
        hook.setSettings(filterSettings(false, false, false, false,
                false, "", true, 15, false, 0));
        assertFiltered(hook, installedHooker.get(),
                new AwemeFixture(false, false, false, null, "", 14_999, 2_000), keep);
        hook.setSettings(filterSettings(false, false, false, false,
                false, "", false, 0, true, 1_000));
        assertFiltered(hook, installedHooker.get(),
                new AwemeFixture(false, false, false, null, "", 20_000, 999), keep);
        hook.close();
    }

    @Test
    public void separatesPhotoPostsFromRecommendationCardsAndKeepsOrdinaryAnchors() throws Throwable {
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        FeedAdFilterHook hook = FeedAdFilterHook.install(createFramework(installed), createReport(),
                getClass().getClassLoader(), filterSettings(false, false, true, false,
                        false, "", false, 0, false, 0), ignored -> { });
        AwemeFixture photo = new AwemeFixture(false, false, true, null, "", 20_000, 2_000);
        AwemeFixture recommendation = new AwemeFixture(false);
        recommendation.awemeType = 145;
        AwemeFixture merchant = new AwemeFixture(false);
        merchant.awemeType = 140;
        AwemeFixture anchoredVideo = new AwemeFixture(false, false, false,
                new Object(), "", 20_000, 2_000);
        List<AwemeFixture> source = Arrays.asList(photo, recommendation, merchant, anchoredVideo);
        FeedItemListFixture imageFiltered = new FeedItemListFixture(source);
        installed.get().intercept(createChain(imageFiltered,
                new ApiResponseFixture("https://api.example/aweme/v2/feed/")));
        assertEquals(Arrays.asList(recommendation, merchant, anchoredVideo), imageFiltered.items);

        hook.setSettings(filterSettings(false, false, false, true,
                false, "", false, 0, false, 0));
        FeedItemListFixture cardFiltered = new FeedItemListFixture(source);
        installed.get().intercept(createChain(cardFiltered,
                new ApiResponseFixture("https://api.example/aweme/v2/feed/")));
        assertEquals(Arrays.asList(photo, anchoredVideo), cardFiltered.items);
        hook.close();
    }

    @Test
    public void recommendationCardAndMerchantFiltersCanBeEnabledSeparately() throws Throwable {
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        FeedAdFilterHook hook = FeedAdFilterHook.install(createFramework(installed), createReport(),
                getClass().getClassLoader(), recommendationSettings(true, false), ignored -> { });
        AwemeFixture recommendation = new AwemeFixture(false);
        recommendation.awemeType = 145;
        AwemeFixture merchant = new AwemeFixture(false);
        merchant.awemeType = 140;
        AwemeFixture regular = new AwemeFixture(false);

        assertFeedKeeps(hook, installed.get(), Arrays.asList(recommendation, merchant, regular),
                Arrays.asList(merchant, regular));

        hook.setSettings(recommendationSettings(false, true));
        assertFeedKeeps(hook, installed.get(), Arrays.asList(recommendation, merchant, regular),
                Arrays.asList(recommendation, regular));
        hook.close();
    }

    @Test
    public void filtersPreloadedEntriesWithoutEmptyingTheMainFeed() throws Throwable {
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        FeedAdFilterHook hook = FeedAdFilterHook.install(createFramework(installed), createReport(),
                getClass().getClassLoader(), filterSettings(true, false, false, false,
                        false, "", false, 0, false, 0), ignored -> { });
        AwemeFixture ad = new AwemeFixture(true);
        AwemeFixture keep = new AwemeFixture(false);
        List<AwemeFixture> mainAds = Collections.singletonList(ad);
        FeedItemListFixture page = new FeedItemListFixture(mainAds);
        page.preloadAds = Collections.singletonList(ad);
        page.preloadAwemes = Arrays.asList(keep, ad);
        installed.get().intercept(createChain(page,
                new ApiResponseFixture("https://api.example/aweme/v2/feed/")));
        assertEquals(mainAds, page.items);
        assertEquals(Collections.emptyList(), page.preloadAds);
        assertEquals(Collections.singletonList(keep), page.preloadAwemes);
        assertEquals(1, hook.getFilteredPages());
        hook.close();
    }

    @Test
    public void restoresEveryChangedListWhenTheHostResponseThrows() throws Throwable {
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        FeedAdFilterHook hook = FeedAdFilterHook.install(createFramework(installed), createReport(),
                getClass().getClassLoader(), filterSettings(true, false, false, false,
                        false, "", false, 0, false, 0), ignored -> { });
        AwemeFixture ad = new AwemeFixture(true);
        AwemeFixture keep = new AwemeFixture(false);
        List<AwemeFixture> source = Arrays.asList(keep, ad);
        FeedItemListFixture page = new FeedItemListFixture(source);
        page.preloadAds = source;
        page.preloadAwemes = source;
        RuntimeException failure = new RuntimeException("host response failed");
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> {
                    if ("getArg".equals(method.getName())) return args[0].equals(0) ? page
                            : new ApiResponseFixture("https://api.example/aweme/v2/feed/");
                    if ("proceed".equals(method.getName())) throw failure;
                    throw new UnsupportedOperationException(method.getName());
                });
        try {
            installed.get().intercept(chain);
            org.junit.Assert.fail("Expected the host failure");
        } catch (RuntimeException expected) {
            org.junit.Assert.assertSame(failure, expected);
        }
        org.junit.Assert.assertSame(source, page.items);
        org.junit.Assert.assertSame(source, page.preloadAds);
        org.junit.Assert.assertSame(source, page.preloadAwemes);
        hook.close();
    }

    private static void assertFiltered(FeedAdFilterHook hook, XposedInterface.Hooker hooker,
                                      AwemeFixture hidden, AwemeFixture keep) throws Throwable {
        FeedItemListFixture page = new FeedItemListFixture(Arrays.asList(hidden, keep));
        hooker.intercept(createChain(page, new ApiResponseFixture("https://api.example/aweme/v2/feed/")));
        assertEquals(Collections.singletonList(keep), page.items);
    }

    private static FeatureSettings filterSettings(boolean ads, boolean lives, boolean images,
                                                  boolean cards, boolean keywordEnabled, String keyword,
                                                  boolean durationEnabled, int durationSeconds,
                                                  boolean likesEnabled, int minimumLikes) {
        return new FeatureSettings(false, false, true, ads, lives, images, cards,
                keywordEnabled, keyword, durationEnabled, durationSeconds,
                likesEnabled, minimumLikes, false, false, false, false, false);
    }

    private static FeatureSettings recommendationSettings(boolean cards, boolean merchants) {
        return new FeatureSettings(false, false, true, false, false, false, cards, merchants,
                false, "", false, 0, false, 0,
                false, false, false, false, false);
    }

    private static void assertFeedKeeps(FeedAdFilterHook hook, XposedInterface.Hooker hooker,
                                       List<AwemeFixture> input,
                                       List<AwemeFixture> expected) throws Throwable {
        FeedItemListFixture page = new FeedItemListFixture(input);
        hooker.intercept(createChain(page,
                new ApiResponseFixture("https://api.example/aweme/v2/feed/")));
        assertEquals(expected, page.items);
    }

    private static HostScanReport createReport() {
        List<RuleScanResult> results = Arrays.asList(
                uniqueMethod("main_feed_api_v1", FeedApiFixture.class, "fetchRecommendFeedImmediate",
                        "()Lbolts/Task;"),
                uniqueMethod("retrofit_success_response", ResponseWrapperFixture.class, "success",
                        "(Ljava/lang/Object;Lcom/douai/module/feature/FeedAdFilterHookTest$ApiResponseFixture;)"
                                + "Lcom/douai/module/feature/FeedAdFilterHookTest$ResponseWrapperFixture;"),
                uniqueMethod("retrofit_response_url", ApiResponseFixture.class, "getUrl", "()Ljava/lang/String;"),
                uniqueMethod("aweme_ad_flag", AwemeFixture.class, "getIsAdAweme", "()Z"),
                uniqueMethod("aweme_is_live", AwemeFixture.class, "isLive", "()Z"),
                uniqueMethod("aweme_is_image", AwemeFixture.class, "isImage", "()Z"),
                uniqueMethod("aweme_type", AwemeFixture.class, "getAwemeType", "()I"),
                new RuleScanResult("main_feed_response_model", "feed_filter", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", descriptor(FeedItemListFixture.class),
                                "", ""))),
                uniqueClass("aweme_feed_attributes", AwemeFixture.class),
                uniqueClass("aweme_statistics_digg_count", AwemeStatisticsFixture.class));
        return new HostScanReport("test-host", results, false);
    }

    private static RuleScanResult uniqueMethod(String id, Class<?> owner, String name, String methodDescriptor) {
        return new RuleScanResult(id, "feed_filter", Collections.singletonList(new HostCandidate(
                "test.apk", "classes.dex", descriptor(owner), name, methodDescriptor)));
    }

    private static RuleScanResult uniqueClass(String id, Class<?> type) {
        return new RuleScanResult(id, "feed_filter", Collections.singletonList(new HostCandidate(
                "test.apk", "classes.dex", descriptor(type), "", "")));
    }

    private static String descriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static XposedInterface createFramework(AtomicReference<XposedInterface.Hooker> installed) {
        AtomicReference<XposedInterface.HookHandle> handle = new AtomicReference<>();
        handle.set((XposedInterface.HookHandle) Proxy.newProxyInstance(
                XposedInterface.HookHandle.class.getClassLoader(),
                new Class<?>[]{XposedInterface.HookHandle.class},
                (proxy, method, args) -> null));
        AtomicReference<XposedInterface.HookBuilder> builder = new AtomicReference<>();
        builder.set((XposedInterface.HookBuilder) Proxy.newProxyInstance(
                XposedInterface.HookBuilder.class.getClassLoader(),
                new Class<?>[]{XposedInterface.HookBuilder.class},
                (proxy, method, args) -> {
                    if ("intercept".equals(method.getName())) {
                        installed.set((XposedInterface.Hooker) args[0]);
                        return handle.get();
                    }
                    return builder.get();
                }));
        return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> "hook".equals(method.getName()) ? builder.get() : null);
    }

    private static XposedInterface.Chain createChain(Object body, ApiResponseFixture response) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> {
                    if ("getArg".equals(method.getName())) return args[0].equals(0) ? body : response;
                    if ("proceed".equals(method.getName())) return body;
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
