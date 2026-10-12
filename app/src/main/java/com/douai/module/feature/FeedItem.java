package com.douai.module.feature;

/** Feed attributes exposed by a version-specific adapter. */
public final class FeedItem {
    public final boolean advertisement;
    public final boolean live;
    public final boolean imagePost;
    public final boolean recommendationCard;
    public final String searchableText;
    public final long durationMillis;
    public final boolean durationKnown;
    public final long likeCount;
    public final boolean likeCountKnown;

    public FeedItem(boolean advertisement, boolean live, boolean imagePost,
                    boolean recommendationCard, String searchableText,
                    long durationMillis, long likeCount) {
        this.advertisement = advertisement;
        this.live = live;
        this.imagePost = imagePost;
        this.recommendationCard = recommendationCard;
        this.searchableText = searchableText == null ? "" : searchableText;
        this.durationKnown = durationMillis >= 0;
        this.durationMillis = Math.max(0, durationMillis);
        this.likeCountKnown = likeCount >= 0;
        this.likeCount = Math.max(0, likeCount);
    }
}
