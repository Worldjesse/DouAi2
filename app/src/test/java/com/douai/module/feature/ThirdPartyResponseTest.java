package com.douai.module.feature;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ThirdPartyResponseTest {
    @Test public void parsesNestedSampleWithNullsEmptyArraysAndExactNumericIds() {
        ThirdPartyResponse response = ThirdPartyResponse.parse("{\"code\":0,\"data\":{"
                + "\"video\":[\"https://media.test/video.mp4\"],\"video_raw\":[\"https://media.test/raw.mp4\"],"
                + "\"images\":[],\"cover\":\"https://media.test/cover.jpg\",\"title\":null,"
                + "\"text\":\"ມວ່ນຜູ້ດຽວ🤪🤍\",\"id\":7696151596120622001,"
                + "\"author\":{\"nickname\":\"@MO-小莫🎀\",\"id\":123456789012345678901234567890},"
                + "\"other\":{\"digg_count\":1944,\"duration\":15046}},\"user\":{\"balance\":28.5455}}");
        assertEquals("7696151596120622001", response.values().get("data.id"));
        assertEquals("123456789012345678901234567890", response.values().get("data.author.id"));
        assertEquals("null", response.values().get("data.title"));
        assertEquals("[]", response.values().get("data.images"));
        assertTrue(response.downloadFields().containsKey("data.images"));
        assertEquals(Arrays.asList("https://media.test/video.mp4"), response.downloadFields().get("data.video"));
        assertTrue(response.values().get("data.text").contains("🤪"));
        assertEquals("28.5455", response.values().get("user.balance"));
        String metadata = response.metadataText(Collections.singleton("data.video"));
        assertTrue(metadata.contains("data.other.digg_count: 1944"));
        assertTrue(metadata.contains("https://media.test/raw.mp4"));
        assertFalse(metadata.contains("https://media.test/video.mp4"));
    }

    @Test public void parsesRootArraysAndObjectArraysWithStablePaths() {
        ThirdPartyResponse response = ThirdPartyResponse.parse("[{\"url\":\"https://media.test/a.jpg\",\"id\":1},"
                + "{\"url\":\"https://media.test/b.jpg\",\"id\":2}]");
        assertEquals("1", response.values().get("[0].id"));
        assertEquals(Arrays.asList("https://media.test/b.jpg"), response.downloadFields().get("[1].url"));
        assertEquals(2, response.downloadFields().size());
    }

    @Test public void textFieldsAndPureLinkResponsesExposeDownloadablePaths() {
        ThirdPartyResponse response = ThirdPartyResponse.parse("title: caption\nid=7696151596120622001\n"
                + "video: https://media.test/a.mp4\nimages: [\"https://media.test/a.jpg\",\"https://media.test/b.jpg\"]\n"
                + "author: person\nmore details");
        assertEquals("caption", response.values().get("title"));
        assertEquals("7696151596120622001", response.values().get("id"));
        assertEquals(2, response.downloadFields().get("images").size());
        assertEquals("person\nmore details", response.values().get("author"));
        assertEquals(Arrays.asList("https://media.test/a.mp4"), response.downloadFields().get("video"));
        assertEquals(2, ThirdPartyResponse.parse("https://media.test/a.mp4\nhttps://media.test/b.mp4")
                .downloadFields().get("links").size());
    }

    @Test public void downloadedTxtFieldStillRetainsItsNonLinkInformation() {
        ThirdPartyResponse response = ThirdPartyResponse.parse("video: Original clip https://media.test/a.mp4");
        String metadata = response.metadataText(Collections.singleton("video"));
        assertTrue(metadata.contains("Original clip"));
        assertFalse(metadata.contains("https://media.test/a.mp4"));
    }

    @Test public void malformedJsonHtmlAndFailedApiStatusAreRejected() {
        for (String input : new String[]{"{\"data\":", "{\"x\":1,}", "{\"x\":1,\"x\":2}",
                "{\"x\":1} trailing", "<!DOCTYPE html><html>error</html>", "", "{\"success\":false,\"message\":\"secret\"}"}) {
            try { ThirdPartyResponse.parse(input); fail(input); }
            catch (IllegalArgumentException | IllegalStateException expected) {
                assertFalse(expected.getMessage().contains("secret"));
            }
        }
    }

    @Test public void providerSpecificSuccessCodesDoNotOverrideSelectedMediaFields() {
        ThirdPartyResponse response = ThirdPartyResponse.parse("{\"success\":true,\"code\":1,"
                + "\"data\":{\"video\":\"https://media.test/a.mp4\"}}");
        assertEquals(Arrays.asList("https://media.test/a.mp4"), response.downloadFields().get("data.video"));
    }

    @Test public void syntheticTxtFieldsPreserveExistingFieldNames() {
        ThirdPartyResponse response = ThirdPartyResponse.parse("https://media.test/intro.mp4\n"
                + "intro text\nlinks: https://media.test/a.mp4\nresponse_links: https://media.test/b.mp4\n"
                + "text: real text\nresponse_text: another text");
        assertEquals(Arrays.asList("https://media.test/a.mp4"), response.downloadFields().get("links"));
        assertEquals(Arrays.asList("https://media.test/b.mp4"), response.downloadFields().get("response_links"));
        assertEquals(Arrays.asList("https://media.test/intro.mp4"), response.downloadFields().get("links[2]"));
        assertEquals("real text", response.values().get("text"));
        assertEquals("another text", response.values().get("response_text"));
        assertEquals("intro text", response.values().get("text[2]"));
    }

    @Test(timeout = 10000) public void largeMultilineTxtAccumulatesWithoutRepeatedStringCopying() {
        String body = "caption: start\n" + "x\n".repeat(300000);
        ThirdPartyResponse response = ThirdPartyResponse.parse(body);
        assertEquals(600005, response.values().get("caption").length());
    }

    @Test public void pathsDoNotCollideWithLiteralDotsOrArrayBrackets() {
        ThirdPartyResponse response = ThirdPartyResponse.parse("{\"a.b\":\"literal\",\"a\":{\"b\":\"nested\"},"
                + "\"items[0]\":\"literal-array\",\"items\":[\"array\"]}");
        assertEquals("literal", response.values().get("a\\.b"));
        assertEquals("nested", response.values().get("a.b"));
        assertEquals("literal-array", response.values().get("items\\[0\\]"));
        assertEquals("array", response.values().get("items[0]"));
    }
}
