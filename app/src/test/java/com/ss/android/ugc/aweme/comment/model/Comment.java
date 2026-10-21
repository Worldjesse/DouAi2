package com.ss.android.ugc.aweme.comment.model;

/** Named host DTO fixture for diagnostic type-boundary tests; no APK implementation is copied. */
public final class Comment {
    public String text;
    public Comment(String text) { this.text = text; }
    @Override public String toString() { throw new AssertionError("Comment.toString must not be called"); }
    @Override public int hashCode() { throw new AssertionError("Comment.hashCode must not be called"); }
}
