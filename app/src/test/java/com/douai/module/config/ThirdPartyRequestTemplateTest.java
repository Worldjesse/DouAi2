package com.douai.module.config;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ThirdPartyRequestTemplateTest {
    @Test public void arbitraryNamesAndDecodedValuesCanBeEditedInOneGetTemplate() {
        ThirdPartyRequestTemplate template = ThirdPartyRequestTemplate.parse(
                "https://api.example.test/parse?ID=123&link={url}&token=a%2Bb+%26%3D");
        assertEquals("https://api.example.test/parse", template.baseUrl);
        assertEquals(3, template.parameters.size());
        assertParameter(template.parameters.get(0), "ID", "123");
        assertParameter(template.parameters.get(1), "link", "{url}");
        assertParameter(template.parameters.get(2), "token", "a+b &=");

        String saved = template.withParameters(List.of(
                new ThirdPartyRequestTemplate.Parameter("id", "new &=+ value"),
                new ThirdPartyRequestTemplate.Parameter("video_link", "{url}")));
        ThirdPartyRequestTemplate restored = ThirdPartyRequestTemplate.parse(saved);
        assertEquals(2, restored.parameters.size());
        assertParameter(restored.parameters.get(0), "id", "new &=+ value");
        assertParameter(restored.parameters.get(1), "video_link", "{url}");
        assertTrue(saved.contains("video_link={url}"));
        assertFalse(saved.contains("app_id"));
    }

    @Test public void zeroParametersAndRemovingAllParametersKeepOnlyTheBaseUrl() {
        String address = "https://api.example.test/parse";
        ThirdPartyRequestTemplate template = ThirdPartyRequestTemplate.parse(address);
        assertTrue(template.parameters.isEmpty());
        assertEquals(address, template.withParameters(Collections.emptyList()));
        assertEquals(address, ThirdPartyRequestTemplate.parse(address + "?ID=123&link={url}")
                .withParameters(Collections.emptyList()));
    }

    @Test public void speechPlaceholdersAreExplicitAndSurviveQueryAndPathEditing() {
        ThirdPartyRequestTemplate parsed = ThirdPartyRequestTemplate.parse(
                "https://api.example.test/{voice}?content={text}&speaker={voice}&ID=123", "{text}", "{voice}");
        assertEquals("https://api.example.test/{voice}", parsed.baseUrl);
        String saved = parsed.withParameters(List.of(new ThirdPartyRequestTemplate.Parameter("q", "{text}"),
                new ThirdPartyRequestTemplate.Parameter("voice_id", "{voice}")));
        assertEquals("https://api.example.test/{voice}?q={text}&voice_id={voice}", saved);
        assertThrows(IllegalArgumentException.class, () -> ThirdPartyRequestTemplate.parse(saved));
        assertThrows(IllegalArgumentException.class, () -> ThirdPartyRequestTemplate.parse(
                "https://{voice}.example.test/api?text={text}", "{text}", "{voice}"));
        assertThrows(IllegalArgumentException.class, () -> parsed.withParameters(List.of(
                new ThirdPartyRequestTemplate.Parameter("unknown", "{token}"))));
    }

    @Test public void independentGetCanHaveNoDynamicPlaceholders() {
        ThirdPartyRequestTemplate parsed = ThirdPartyRequestTemplate.parse("https://api.example.test/voices?key=abc",
                new String[0]);
        assertEquals("https://api.example.test/voices?key=abc", parsed.withParameters(parsed.parameters));
        assertThrows(IllegalArgumentException.class, () -> ThirdPartyRequestTemplate.parse(
                "https://api.example.test/voices?key={url}", new String[0]));
    }

    @Test public void pathLinkPlaceholderAndDuplicateOrEmptyValuesSurviveParameterEditing() {
        ThirdPartyRequestTemplate template = ThirdPartyRequestTemplate.parse(
                "https://api.example.test/parse/{url}?format=&tag=one&tag=two");
        assertEquals("https://api.example.test/parse/{url}", template.baseUrl);
        assertEquals(3, template.parameters.size());
        assertParameter(template.parameters.get(0), "format", "");
        assertParameter(template.parameters.get(1), "tag", "one");
        assertParameter(template.parameters.get(2), "tag", "two");
        assertEquals("https://api.example.test/parse/{url}?format=&tag=one&tag=two",
                template.withParameters(template.parameters));
    }

    @Test public void encodedNamesAndInterleavedDuplicatesRetainDecodedNamesAndOriginalOrder() {
        ThirdPartyRequestTemplate template = ThirdPartyRequestTemplate.parse(
                "https://api.example.test/parse?%49D=first&access+key=token&ID=second&percent%2Bname=value");
        assertEquals(4, template.parameters.size());
        assertParameter(template.parameters.get(0), "ID", "first");
        assertParameter(template.parameters.get(1), "access key", "token");
        assertParameter(template.parameters.get(2), "ID", "second");
        assertParameter(template.parameters.get(3), "percent+name", "value");
        ThirdPartyRequestTemplate restored = ThirdPartyRequestTemplate.parse(
                template.withParameters(template.parameters));
        for (int i = 0; i < template.parameters.size(); i++) {
            assertParameter(restored.parameters.get(i), template.parameters.get(i).name,
                    template.parameters.get(i).value);
        }
    }

    @Test public void rejectsInvalidEndpointsAndEmptyParameterNames() {
        for (String endpoint : new String[]{"", "ftp://example.test/parse", "relative/path",
                "https://user:secret@example.test/parse", "https://example.test/parse#fragment",
                "https://{url}/parse", "https://example.test/parse?unknown={token}",
                "https://example.test/parse?q=%ZZ"}) {
            assertThrows(IllegalArgumentException.class, () -> ThirdPartyRequestTemplate.parse(endpoint));
        }
        ThirdPartyRequestTemplate template = ThirdPartyRequestTemplate.parse("https://api.example.test/parse");
        assertThrows(IllegalArgumentException.class, () -> template.withParameters(
                List.of(new ThirdPartyRequestTemplate.Parameter(" ", "value"))));
    }

    @Test public void migratesLegacyAppIdAndTrailingLinkPrefixWhileKeepingConfiguredEmptyValues() {
        ThirdPartyDownloadSettings old = settings(Map.of(
                ThirdPartyDownloadSettings.GET_TEMPLATE, "https://api.example.test/parse?ID={app_id}&link=",
                ThirdPartyDownloadSettings.APP_ID, "old ID+",
                ThirdPartyDownloadSettings.APP_SECRET, "secret"));
        ThirdPartyRequestTemplate template = ThirdPartyRequestTemplate.parse(old.getTemplate);
        assertParameter(template.parameters.get(0), "ID", "old ID+");
        assertParameter(template.parameters.get(1), "link", "{url}");
        assertEquals(ThirdPartyDownloadSettings.SIGNATURE_MD5, old.signatureMode);
        assertEquals("Sign", old.signatureHeader);

        ThirdPartyDownloadSettings current = settings(Map.of(
                ThirdPartyDownloadSettings.GET_TEMPLATE, "https://api.example.test/parse?ID=",
                ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_NONE,
                ThirdPartyDownloadSettings.APP_SECRET, "stale-secret"));
        assertEquals("https://api.example.test/parse?ID=", current.getTemplate);
        assertEquals(ThirdPartyDownloadSettings.SIGNATURE_NONE, current.signatureMode);
        assertEquals("https://api.example.test/parse?token=a=", settings(Map.of(
                ThirdPartyDownloadSettings.GET_TEMPLATE, "https://api.example.test/parse?token=a="))
                .getTemplate);
    }

    @Test public void legacyAppIdWithoutSavedTemplateIsRetainedAndFreshConfigDefaultsToUnsigned() {
        ThirdPartyDownloadSettings legacy = settings(Map.of(ThirdPartyDownloadSettings.APP_ID, "75107541"));
        assertEquals("https://gateway.diadi.cn/api/parse?app_id=75107541&url={url}", legacy.getTemplate);
        ThirdPartyDownloadSettings fresh = settings(Collections.emptyMap());
        assertEquals(ThirdPartyDownloadSettings.DEFAULT_GET_TEMPLATE, fresh.getTemplate);
        assertEquals(ThirdPartyDownloadSettings.SIGNATURE_NONE, fresh.signatureMode);
    }

    private static ThirdPartyDownloadSettings settings(Map<String, ?> values) {
        return new ThirdPartyDownloadSettings(new LocalOptions(values));
    }

    private static void assertParameter(ThirdPartyRequestTemplate.Parameter parameter, String name, String value) {
        assertEquals(name, parameter.name);
        assertEquals(value, parameter.value);
    }
}
