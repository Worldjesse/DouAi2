package com.douai.module.feature;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class VoiceCatalogTest {
    @Test public void typicalNestedVoiceArraysInferIdAndName() {
        List<VoiceCatalog.Entry> entries = parse("{\"code\":0,\"data\":{\"voices\":["
                + "{\"voice_id\":1,\"voice_name\":\"First\"},{\"id\":\"2\",\"name\":\"Second\"}]}}");
        assertEquals(2, entries.size());
        assertEquals("1", entries.get(0).id);
        assertEquals("First", entries.get(0).name);
        assertThrows(UnsupportedOperationException.class, entries::clear);
    }

    @Test public void arbitraryListAndNestedIdNameFieldsPreserveLargeNumericIds() {
        List<VoiceCatalog.Entry> entries = VoiceCatalog.parse("{\"response\":{\"sounds\":[{\"binding\":{"
                + "\"ID\":7696151596120622001},\"display\":{\"label\":\"Voice\"}}]}}",
                "response.sounds", "binding.ID", "display.label");
        assertEquals("7696151596120622001", entries.get(0).id);
        assertEquals("Voice", entries.get(0).name);
    }

    @Test public void rootOrNestedMapsUseTheirActualKeysAsVoiceIds() {
        List<VoiceCatalog.Entry> entries = parse("{\"data\":{\"one\":\"Alpha\",\"2\":{\"name\":\"Beta\"}}}");
        assertEquals("one", entries.get(0).id);
        assertEquals("Alpha", entries.get(0).name);
        assertEquals("2", entries.get(1).id);
        assertEquals("Beta", entries.get(1).name);
    }

    @Test public void configuredArrayIndexAndEscapedKeysCanLocateAList() {
        List<VoiceCatalog.Entry> entries = VoiceCatalog.parse("{\"r\":[{\"voice.list\":[{\"ID\":7,\"title\":\"X\"}]}]}",
                "r[0].voice\\.list", "ID", "title");
        assertEquals("7", entries.get(0).id);
        assertEquals("X", entries.get(0).name);
    }

    @Test public void explicitTxtIdsAreKeptAndDuplicatesAreDeduplicated() {
        List<VoiceCatalog.Entry> entries = parse("1: Warm voice\n2, Bright voice\nvoice_3\tLow voice\n1: Duplicate\n");
        assertEquals(3, entries.size());
        assertEquals("Warm voice", entries.get(0).name);
        assertEquals("voice_3", entries.get(2).id);
        assertEquals("Low voice", entries.get(2).name);
    }

    @Test public void actualNumberedVoiceRowsStripThePeriodFromTheId() {
        List<VoiceCatalog.Entry> entries = parse("1. 叶修\n2. 艾玛\n223. 瓦瓦\n");
        assertEquals(3, entries.size());
        assertEquals("1", entries.get(0).id);
        assertEquals("叶修", entries.get(0).name);
        assertEquals("223", entries.get(2).id);
        assertEquals("瓦瓦", entries.get(2).name);
    }

    @Test public void invalidOrAbsentConfiguredFieldsFailWithoutProviderBodyLeakage() {
        for (String body : new String[]{"", "{", "{\"success\":false,\"message\":\"private-token\"}",
                "<html>private-token</html>", "{\"data\":[]}", "{\"code\":403,\"message\":\"private-token\"}"}) {
            Exception failure = assertThrows(IllegalArgumentException.class, () -> parse(body));
            assertFalse(failure.getMessage().contains("private-token"));
            assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> VoiceCatalog.parse("{\"data\":[{\"id\":1,\"name\":\"A\"}]}",
                "absent", "", ""));
        assertThrows(IllegalArgumentException.class, () -> VoiceCatalog.parse("[{\"id\":1,\"name\":\"A\"}]",
                "", "absent", ""));
        assertThrows(IllegalArgumentException.class, () -> VoiceCatalog.parse("1: Warm", "data", "id", "name"));
    }

    private static List<VoiceCatalog.Entry> parse(String body) { return VoiceCatalog.parse(body, "", "", ""); }
}
