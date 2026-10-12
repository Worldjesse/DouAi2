package com.douai.module.feature;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProfileListEntryTest {
    @Test public void serializedNativeAvatarAndImDisplayAvatarBothResolve() {
        assertEquals("https://p3.douyinpic.com/avatar.jpg", ProfileListEntry.avatar(new Profile()));
        assertEquals("https://p3.douyinpic.com/avatar.jpg", ProfileListEntry.avatar(new ImProfile()));
    }
    @Test public void missingAndUnsupportedAvatarUrlsRetainIdentityAndUsePlaceholder() {
        ProfileListEntry profile = new ProfileListEntry("100", "Alice", "https://example.invalid/p.png");
        assertEquals("100", profile.uid);
        assertEquals("Alice", profile.name);
        assertEquals("", profile.avatarUrl);
        assertEquals("", ProfileListEntry.avatar(null));
    }
    static final class Profile { @SerializedName("avatar_thumb") final Avatar avatar = new Avatar(); }
    static final class Avatar { @SerializedName("url_list") final List<String> urls = Collections.singletonList("https://p3.douyinpic.com/avatar.jpg"); }
    public static final class ImProfile { public GetterAvatar getDisplayAvatar() { return new GetterAvatar(); } }
    public static final class GetterAvatar { public List<String> getUrlList() { return Collections.singletonList("https://p3.douyinpic.com/avatar.jpg"); } }
}
