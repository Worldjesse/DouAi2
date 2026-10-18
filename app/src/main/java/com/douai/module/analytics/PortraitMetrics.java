package com.douai.module.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure, zero-safe calculations over full distributions. No claims about latent personality. */
public final class PortraitMetrics {
    private PortraitMetrics() { }

    public static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0 : Math.max(0, Math.min(1, (double) numerator / denominator));
    }

    public static long total(List<AnalyticsSnapshot.Rank> distribution) {
        long result = 0;
        for (AnalyticsSnapshot.Rank rank : distribution) result += Math.max(0, rank.count);
        return result;
    }

    public static double diversity(List<AnalyticsSnapshot.Rank> distribution) {
        long total = total(distribution);
        int categories = 0;
        double entropy = 0;
        for (AnalyticsSnapshot.Rank rank : distribution) if (rank.count > 0) {
            categories++;
            double p = (double) rank.count / total;
            entropy -= p * Math.log(p);
        }
        return categories < 2 ? 0 : Math.max(0, Math.min(1, entropy / Math.log(categories)));
    }

    /** Input must be descending. Other preserves the full denominator, including off-screen data. */
    public static List<AnalyticsSnapshot.Rank> headAndTail(List<AnalyticsSnapshot.Rank> distribution, int head) {
        List<AnalyticsSnapshot.Rank> result = new ArrayList<>();
        long tail = 0;
        for (int i = 0; i < distribution.size(); i++) {
            if (i < head) result.add(distribution.get(i)); else tail += distribution.get(i).count;
        }
        if (tail > 0) result.add(new AnalyticsSnapshot.Rank("其他（" + (distribution.size() - head) + " 类）", tail));
        return result;
    }

    static String percent(double value) { return String.format(Locale.ROOT, "%.1f%%", value * 100); }

    static List<PortraitSnapshot.Insight> insights(long views, long features, long unique, int browseDays,
            List<AnalyticsSnapshot.Rank> authors, List<AnalyticsSnapshot.Rank> topics,
            List<AnalyticsSnapshot.Rank> types, List<AnalyticsSnapshot.Rank> sources,
            long repeated, double night, double weekend, long captured, long visible,
            PortraitSnapshot.Comparison comparison) {
        List<PortraitSnapshot.Insight> result = new ArrayList<>();
        if (views > 0) {
            double authorDiversity = diversity(authors), topicDiversity = diversity(topics);
            result.add(new PortraitSnapshot.Insight("content_world", "你的内容版图",
                    "记录涉及 " + authors.size() + " 个作者、" + topics.size() + " 个话题。"
                            + (authors.size() > 1 && topics.size() > 1
                            ? (authorDiversity > topicDiversity ? "作者分布比话题分布更均匀，呈现来源展开、主题相对集中的组合。"
                            : "话题分布至少与作者分布同样均匀，内容主题和接触来源呈现不同的分散程度。") : "继续积累不同作者和话题，才能比较来源与主题的分布。"),
                    "全量作者分布均匀度 " + percent(authorDiversity) + "；全量话题分布均匀度 " + percent(topicDiversity)
                            + "；" + total(topics) + " 次话题出现。",
                    "这是推荐流接触结构。单作品可含多个话题，话题占比以话题出现总次数为分母；不能据此证明喜欢。"));
            String leading = types.isEmpty() ? "未识别" : types.get(0).label;
            double leadingShare = types.isEmpty() ? 0 : ratio(types.get(0).count, views);
            result.add(new PortraitSnapshot.Insight("content_shape", "你遇见的内容形态",
                    leading + "占本期记录的 " + percent(leadingShare) + "，共观察到 " + types.size() + " 种内容形态。",
                    "浏览记录 " + views + " 次，去重作品 " + unique + " 个；主类型 " + leading + "。",
                    "实况图集和直播卡片分别统计。内容时长不等于观看时长，不能由类型推断投入程度。"));
            result.add(new PortraitSnapshot.Insight("daily_rhythm", "你的浏览节奏",
                    "本期有浏览记录的日期为 " + browseDays + " 天；凌晨占 " + percent(night) + "，周末占 " + percent(weekend) + "。",
                    "凌晨定义 00:00–05:59；周末为周六和周日；分母均为本期 " + views + " 次浏览记录。",
                    "时段和话题的各自占比不能合成为‘深夜喜欢某话题’。周末占比未按工作日/周末天数归一。"));
            result.add(new PortraitSnapshot.Insight("familiarity", "重逢与新遇见",
                    "有 " + repeated + " 个作品在不同日期再次出现，占去重作品的 " + percent(ratio(repeated, unique)) + "。",
                    "跨日重复作品 " + repeated + " / 本期去重作品 " + unique + "；总浏览 " + views + " 次。",
                    "重复出现可能来自推荐、主动返回或不同入口；它不是主动回看率，也不能还原保存意图。"));
        }
        if (features > 0) {
            long manual = 0, automatic = 0, unknown = 0;
            for (AnalyticsSnapshot.Rank rank : sources) {
                if ("主动操作".equals(rank.label)) manual += rank.count;
                if ("自动触发".equals(rank.label)) automatic += rank.count;
                if ("来源未知".equals(rank.label)) unknown += rank.count;
            }
            result.add(new PortraitSnapshot.Insight("tool_style", "工具参与的方式",
                    "模块功能共记录 " + features + " 次，其中主动入口 " + manual + " 次，自动触发 " + automatic + " 次。",
                    "来源未知 " + unknown + " 次；功能次数与浏览次数分别统计。",
                    "入口、请求、受理和成功是不同阶段，次数不可直接当作任务数。自动操作不能代表主动认可。"));
        }
        if (views > 0 || features > 0) result.add(new PortraitSnapshot.Insight("change", "这段时间的变化",
                "本期浏览 " + comparison.currentViews + " 次、功能 " + comparison.currentFeatures
                        + " 次；等长前期分别为 " + comparison.previousViews + " 次、" + comparison.previousFeatures + " 次。"
                        + (!comparison.previousComplete ? "前期记录覆盖不足，暂不判断增长或下降。" : "本期包含今天未结束的记录，变化仍会继续。"),
                comparison.currentStart + " 至 " + comparison.currentEnd + " 对比 "
                        + comparison.previousStart + " 至 " + comparison.previousEnd + "。",
                comparison.limitation));
        if (!comparison.currentTopics.isEmpty() || !comparison.previousTopics.isEmpty()) {
            String currentTopic = comparison.currentTopics.isEmpty() ? "暂无话题记录"
                    : comparison.currentTopics.get(0).label + "（" + comparison.currentTopics.get(0).count + " 次出现）";
            String previousTopic = comparison.previousTopics.isEmpty() ? "暂无话题记录"
                    : comparison.previousTopics.get(0).label + "（" + comparison.previousTopics.get(0).count + " 次出现）";
            result.add(new PortraitSnapshot.Insight("topic_change", "话题主线的迁移",
                    "本期出现最多的话题是 " + currentTopic + "；等长前期为 " + previousTopic + "。"
                            + (!comparison.previousComplete ? "前期覆盖不完整，只并列已记录内容，暂不判断偏好变化。" : "榜首变化反映接触结构，不能直接等同兴趣变化。"),
                    comparison.previousStart + " 至 " + comparison.previousEnd + " → " + comparison.currentStart + " 至 " + comparison.currentEnd
                            + "；榜单来自全量话题统计，一条作品可有多个话题。",
                    comparison.limitation + " 本期含今天未结束的记录；尾部合并为其他，未上榜不等于从未接触。"));
        }
        if (captured > 0) result.add(new PortraitSnapshot.Insight("observed_attention", "有记录的停留片段",
                "最近保留范围内捕获 " + captured + " 个可见片段，累计确认可见 " + (visible / 1000) + " 秒。",
                "只有前台、页面可见且仍指向同一作品的有界采样才累积；片段不等于独立作品。",
                "可见不等于注视或喜欢。旧数据无停留补算；后台、遮挡、心跳间隙不计；每段最多 30 分钟。"));
        return result;
    }
}
