package com.douai.module.feature;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class MiniMaxSpeechModelCatalogTest {
    @Test public void readsOnlyTheSpeechRequestModelEnumAndDeduplicatesInSourceOrder() {
        String document = "{\"description\":\"example speech-wrong-description\","
                + "\"example\":{\"model\":\"speech-wrong-example\"},"
                + "\"enum\":[\"speech-wrong-root\"],\"components\":{\"schemas\":{"
                + "\"OtherRequest\":{\"properties\":{\"model\":{\"enum\":[\"speech-wrong-schema\"]}}},"
                + "\"T2aV2Req\":{\"properties\":{"
                + "\"language_boost\":{\"enum\":[\"Chinese\",\"English\"]},"
                + "\"audio_setting\":{\"properties\":{\"format\":{\"enum\":[\"mp3\",\"wav\"]}}},"
                + "\"model\":{\"description\":\"speech-wrong-model-description\",\"enum\":[\"speech-2.8-hd\",\"speech-2.8-turbo\",\"speech-2.8-hd\",\"speech-02-hd\"]}}}}}}";
        assertEquals(List.of("speech-2.8-hd", "speech-2.8-turbo", "speech-02-hd"), MiniMaxSpeechModelCatalog.parse(document));
    }

    @Test public void absentEmptyAndWronglyTypedEnumsFailWithoutScanningOtherFields() {
        for (String document : new String[]{"{}", "{\"enum\":[\"speech-2.8-hd\"]}",
                "{\"components\":{\"schemas\":{\"OtherRequest\":{\"properties\":{\"model\":{\"enum\":[\"speech-2.8-hd\"]}}}}}}",
                modelDocument("[]"), modelDocument("\"speech-2.8-hd\""), modelDocument("null"),
                "private-key private-text"}) {
            assertSafe(assertThrows(IllegalArgumentException.class, () -> MiniMaxSpeechModelCatalog.parse(document)));
        }
    }

    @Test public void invalidIdentifiersCannotBecomeSelectableModels() {
        for (String models : new String[]{"[\"MiniMax-M2.5\"]", "[\"speech-\"]", "[\"Speech-2.8-hd\"]",
                "[42]", "[{}]", "[\"speech-a?private-key\"]", "[\"speech-a\\nprivate-text\"]",
                "[\"speech-" + "a".repeat(130) + "\"]"}) {
            assertSafe(assertThrows(IllegalArgumentException.class, () -> MiniMaxSpeechModelCatalog.parse(modelDocument(models))));
        }
    }

    @Test public void boundsInputBeforeParsingLargeDocument() {
        assertSafe(assertThrows(IllegalArgumentException.class, () -> MiniMaxSpeechModelCatalog.parse(
                modelDocument("[\"speech-2.8-hd\"]") + " ".repeat(SpeechHttpClient.MAX_TEXT_BYTES))));
        assertSafe(assertThrows(IllegalArgumentException.class, () -> MiniMaxSpeechModelCatalog.parse(null)));
    }

    @Test public void shipsASeparateOfficialBuiltInCatalog() {
        List<String> models = MiniMaxSpeechModelCatalog.builtInModels();
        assertEquals(8, models.size());
        assertTrue(models.containsAll(List.of("speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-2.6-turbo",
                "speech-02-hd", "speech-02-turbo", "speech-01-hd", "speech-01-turbo")));
        assertThrows(UnsupportedOperationException.class, () -> models.add("speech-unexpected"));
    }

    private static String modelDocument(String models) {
        return "{\"components\":{\"schemas\":{\"T2aV2Req\":{\"properties\":{\"model\":{\"enum\":" + models + "}}}}}}";
    }

    private static void assertSafe(Exception failure) {
        assertNotNull(failure.getMessage()); assertTrue(failure.getMessage().startsWith("MiniMax 语音"));
        assertFalse(failure.getMessage().contains("private")); assertNull(failure.getCause());
    }
}
