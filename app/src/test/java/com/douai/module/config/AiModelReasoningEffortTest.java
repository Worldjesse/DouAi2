package com.douai.module.config;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiModelReasoningEffortTest {
    @Test public void oldProfilesUseTheModelDefaultWithoutEnablingAnExplicitEffort() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            AiModelConfig old = AiModelConfig.fromJson(profile(protocol));
            old.validate(true);
            assertEquals(AiModelConfig.REASONING_DEFAULT, old.reasoningEffort);
            assertFalse(old.hasReasoningEffort());
            assertEquals(AiModelConfig.REASONING_DEFAULT, old.toJson().getString("reasoningEffort"));
        }
    }

    @Test public void everyEffortRoundTripsAndCopiesWithoutChangingTheResponseMode() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            for (String effort : AiModelConfig.reasoningEfforts(protocol)) {
                AiModelConfig original = AiModelConfig.fromJson(profile(protocol)
                        .put("responseMode", AiModelConfig.RESPONSE_STREAM).put("reasoningEffort", effort));
                original.validate(true);
                AiModelConfig restored = AiModelSettings.parse(new AiModelSettings(
                        Collections.singletonList(original), original.id).serialize()).selected();
                assertEquals(effort, restored.reasoningEffort);
                assertEquals(!AiModelConfig.REASONING_DEFAULT.equals(effort), restored.hasReasoningEffort());
                assertTrue(restored.streaming());
                AiModelConfig copy = AiModelConfig.fromJson(restored.toJson().put("id", "copy"));
                copy.validate(true);
                assertEquals(effort, copy.reasoningEffort);
            }
        }
    }

    @Test public void invalidExplicitEffortsAreRejected() throws Exception {
        for (Object value : new Object[]{"", "auto", "HIGH", "ultra", "maximum", false, 2, JSONObject.NULL}) {
            AiModelConfig invalid = AiModelConfig.fromJson(profile(AiModelConfig.CHAT).put("reasoningEffort", value));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> invalid.validate(true));
            assertEquals("推理强度无效", failure.getMessage());
        }
    }

    @Test public void protocolCandidatesUseActualParameterValuesAndRejectInapplicableEfforts() throws Exception {
        assertArrayEquals(new String[]{"default", "none", "minimal", "low", "medium", "high", "xhigh", "max"},
                AiModelConfig.reasoningEfforts(AiModelConfig.CHAT));
        assertArrayEquals(AiModelConfig.reasoningEfforts(AiModelConfig.CHAT), AiModelConfig.reasoningEfforts(AiModelConfig.RESPONSES));
        assertArrayEquals(new String[]{"default", "low", "medium", "high", "xhigh", "max"},
                AiModelConfig.reasoningEfforts(AiModelConfig.ANTHROPIC));
        assertArrayEquals(new String[]{"default", "minimal", "low", "medium", "high"},
                AiModelConfig.reasoningEfforts(AiModelConfig.GEMINI));
        for (String protocol : AiModelConfig.PROTOCOLS) {
            String[] values = AiModelConfig.reasoningEfforts(protocol), labels = AiModelConfig.reasoningLabels(protocol);
            assertEquals("模型默认", labels[0]);
            for (int index = 1; index < values.length; index++) assertEquals(values[index], labels[index]);
            for (String effort : AiModelConfig.REASONING_EFFORTS) {
                if (java.util.Arrays.asList(values).contains(effort)) continue;
                AiModelConfig invalid = AiModelConfig.fromJson(profile(protocol).put("reasoningEffort", effort));
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> invalid.validate(true));
                assertTrue(failure.getMessage().contains("当前接口协议不适用"));
            }
        }
    }

    @Test public void explicitEffortRejectsTheSameProtocolCustomFieldButDefaultKeepsAdvancedParameters() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            String parameters = conflictingParameters(protocol);
            AiModelConfig advanced = AiModelConfig.fromJson(profile(protocol).put("customParameters", parameters));
            advanced.validate(true);
            assertEquals(new JSONObject(parameters).toString(), advanced.parameters().toString());
            AiModelConfig explicit = AiModelConfig.fromJson(advanced.toJson().put("reasoningEffort", "high"));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> explicit.validate(true));
            assertTrue(failure.getMessage().contains("冲突"));
            assertTrue(failure.getMessage().contains("模型默认"));
        }
    }

    @Test public void explicitEffortPreservesSiblingParametersAndDoesNotMutateTheirJson() throws Exception {
        String[] parameters = {"{\"seed\":9}", "{\"reasoning\":{\"summary\":\"auto\"}}",
                "{\"output_config\":{\"format\":{\"type\":\"json_schema\"}}}",
                "{\"generationConfig\":{\"candidateCount\":1,\"thinkingConfig\":{\"includeThoughts\":false}}}"};
        for (int index = 0; index < AiModelConfig.PROTOCOLS.length; index++) {
            AiModelConfig profile = AiModelConfig.fromJson(profile(AiModelConfig.PROTOCOLS[index])
                    .put("reasoningEffort", "medium").put("customParameters", parameters[index]));
            profile.validate(true);
            assertEquals(new JSONObject(parameters[index]).toString(), profile.parameters().toString());
            assertEquals(parameters[index], profile.customParameters);
        }
    }

    @Test public void explicitEffortRejectsNonObjectAncestorsInsteadOfDroppingCustomValues() throws Exception {
        String[] protocols = {AiModelConfig.RESPONSES, AiModelConfig.ANTHROPIC, AiModelConfig.GEMINI, AiModelConfig.GEMINI};
        String[] parameters = {"{\"reasoning\":null}", "{\"output_config\":\"vendor value\"}",
                "{\"generationConfig\":[]}", "{\"generationConfig\":{\"thinkingConfig\":false}}"};
        for (int index = 0; index < protocols.length; index++) {
            AiModelConfig advanced = AiModelConfig.fromJson(profile(protocols[index]).put("customParameters", parameters[index]));
            advanced.validate(true);
            AiModelConfig explicit = AiModelConfig.fromJson(advanced.toJson().put("reasoningEffort", "low"));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> explicit.validate(true));
            assertTrue(failure.getMessage().contains("必须是 JSON 对象"));
        }
    }

    @Test public void explicitGeminiThinkingLevelRejectsAnyBudgetWhileDefaultPreservesIt() throws Exception {
        for (Object budget : new Object[]{0, 1024, JSONObject.NULL}) {
            JSONObject parameters = new JSONObject().put("generationConfig", new JSONObject()
                    .put("thinkingConfig", new JSONObject().put("thinkingBudget", budget).put("includeThoughts", false)));
            AiModelConfig advanced = AiModelConfig.fromJson(profile(AiModelConfig.GEMINI)
                    .put("customParameters", parameters.toString()));
            advanced.validate(true);
            assertEquals(parameters.toString(), advanced.parameters().toString());
            AiModelConfig explicit = AiModelConfig.fromJson(advanced.toJson().put("reasoningEffort", "high"));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> explicit.validate(true));
            assertTrue(failure.getMessage().contains("generationConfig.thinkingConfig.thinkingBudget"));
            assertTrue(failure.getMessage().contains("冲突"));
        }
    }

    private static JSONObject profile(String protocol) throws Exception {
        return new JSONObject().put("id", "effort").put("name", "配置").put("protocol", protocol)
                .put("apiEndpoint", "https://example.test/v1").put("modelName", "fixture-model");
    }

    private static String conflictingParameters(String protocol) {
        switch (protocol) {
            case AiModelConfig.CHAT: return "{\"reasoning_effort\":\"vendor-specific\"}";
            case AiModelConfig.RESPONSES: return "{\"reasoning\":{\"effort\":\"vendor-specific\"}}";
            case AiModelConfig.ANTHROPIC: return "{\"output_config\":{\"effort\":\"vendor-specific\"}}";
            default: return "{\"generationConfig\":{\"thinkingConfig\":{\"thinkingLevel\":\"vendor-specific\"}}}";
        }
    }
}
