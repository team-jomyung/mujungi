package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.dialog.InstructionTemplates;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.voice.ProviderRequestFactory.InstructionsAppend;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 공급자 요청 팩토리의 실호출 관측 선 형식 (U11 FR12.1 · BR11.1 · BR11.2 · 편차 (U11-a), 설계 개정 A1 · A6). 키 · 식별자는 쓰지 않는다. */
@Tag("u11")
class ProviderRequestFactoryWireTest {

    private static final JsonCodec JSON = JsonCodec.shared();
    private final ProviderRequestFactory factory = new ProviderRequestFactory();

    private JsonNode wire(Object request) {
        return JSON.read(JSON.write(request), JsonNode.class);
    }

    private static Set<String> keys(JsonNode node, String prefix) {
        Set<String> keys = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            keys.add(path);
            if (field.getValue().isObject()) {
                keys.addAll(keys(field.getValue(), path));
            }
        }
        return keys;
    }

    @Test
    void sessionStartIsNestedWithExactlyTheObservedKeys() {
        JsonNode start = wire(factory.sessionStart(new ChildContext("하늘", 8, List.of("공룡"))));

        assertThat(keys(start, "")).containsExactlyInAnyOrder("type", "session", "session.model", "session.instructions",
                "session.audio", "session.audio.format", "session.audio.format.type", "session.audio.format.rate");
        assertThat(start.get("type").asString()).isEqualTo("session.start");
        assertThat(start.get("session").get("model").asString()).isEqualTo(ProviderRequestFactory.MODEL);
        assertThat(start.get("session").get("audio").get("format").get("type").asString()).isEqualTo("audio/pcm");
        assertThat(start.get("session").get("audio").get("format").get("rate").asInt()).isEqualTo(24_000);
    }

    @Test
    void theSampleRateIsAParameterAndNothingElseChanges() {
        ChildContext context = new ChildContext("하늘", 8, List.of());

        JsonNode sixteen = wire(factory.sessionStart(context, 16_000));

        assertThat(sixteen.get("session").get("audio").get("format").get("rate").asInt()).isEqualTo(16_000);
        assertThat(keys(sixteen, "")).isEqualTo(keys(wire(factory.sessionStart(context)), ""));
        assertThatThrownBy(() -> factory.sessionStart(context, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void childInfoIsOnlyASentenceInsideTheInstructionsNeverAField() {
        JsonNode start = wire(factory.sessionStart(new ChildContext("하늘", 8, List.of("공룡", "우주"))));

        String wire = start.toString();
        assertThat(wire).doesNotContain("childContext", "delegation", "displayName", "interests");
        String instructions = start.get("session").get("instructions").asString();
        assertThat(instructions).contains("하늘", "공룡", "우주");
    }

    @Test
    void onlyFourRequestTypesExistAndEveryOneIsOnTheSupportedList() {
        List<Object> requests = List.of(factory.sessionStart(new ChildContext("하늘", 8, List.of())),
                factory.inputAudio(new byte[AudioFrameCodec.INPUT_PAYLOAD_BYTES]),
                factory.instructionsAppend("mj-1", DialogInstructions.Correction.PRESSURE), factory.sessionClose());

        Set<String> types = new HashSet<>();
        for (Object request : requests) {
            types.add(wire(request).get("type").asString());
        }

        assertThat(types).containsExactlyInAnyOrder("session.start", "session.input_audio.append",
                "session.instructions.append", "session.close");
        assertThat(types).isEqualTo(ProviderRequestFactory.SENT_TYPES);
        assertThat(types).doesNotContain("response.create", "session.response.cancel", "session.response.create",
                "session.instructions.correct");
        assertThat(Arrays.stream(ProviderRequestFactory.class.getMethods()).map(java.lang.reflect.Method::getName))
                .as("응답 요청을 만드는 입구가 없다").doesNotContain("responseCreate");
    }

    @Test
    void instructionsAppendAlwaysCarriesDelegationIdEvenWhenItIsNull() {
        InstructionsAppend request = factory.instructionsAppend("mj-7", DialogInstructions.Correction.INCOMPLETE);

        String text = JSON.write(request);
        JsonNode append = wire(request);

        assertThat(text).as("이 프로젝트 코덱은 null 필드를 지우지만 delegation_id는 항상 쓴다").contains("\"delegation_id\":null");
        assertThat(keys(append, "")).containsExactlyInAnyOrder("type", "event_id", "delegation_id", "content");
        assertThat(append.get("type").asString()).isEqualTo("session.instructions.append");
        assertThat(append.get("event_id").asString()).isEqualTo("mj-7");
        assertThat(append.get("delegation_id").isNull()).isTrue();
        assertThat(append.get("content").asString()).isEqualTo(InstructionTemplates.correctionContent(DialogInstructions.Correction.INCOMPLETE));
        assertThat(JSON.write(factory.sessionClose())).isEqualTo("{\"type\":\"session.close\"}");
        assertThat(text).doesNotContain("\"text\"");
    }

    @Test
    void practiceInstructionsGoOutAsInstructionsAppendAfterTheValueTypeCheckWithTheScopeClosed() {
        JsonNode append = wire(factory.practiceInstruction("mj-3", new PracticeInstruction("목표 문장을 한 번 더 안내한다.")));

        assertThat(keys(append, "")).containsExactlyInAnyOrder("type", "event_id", "delegation_id", "content");
        assertThat(append.get("type").asString()).isEqualTo("session.instructions.append");
        assertThat(append.get("content").asString()).isEqualTo("목표 문장을 한 번 더 안내한다. " + InstructionTemplates.SCOPE_CLOSE);
    }

    @Test
    void everyInstructionTemplateClosesItsScopeAndStartsWithAShortInterjectionWhereItSpeaksFreely() {
        List<String> contents = new java.util.ArrayList<>();
        for (DialogInstructions.Correction correction : DialogInstructions.Correction.values()) {
            contents.add(factory.instructionsAppend("e", correction).content());
        }
        for (InstructionTemplates.ResponseStart start : InstructionTemplates.ResponseStart.values()) {
            contents.add(factory.responseStart("e", start).content());
        }
        contents.add(factory.practiceInstruction("e", new PracticeInstruction("하나 둘")).content());
        contents.add(factory.practiceInstruction("e", PracticeInstruction.forSpeech(new PracticeSpeech(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), 1L, kr.mujoong.realtime.practice.PracticePorts.OutputKind.SCENARIO,
                "친구", "안녕"))).content());

        assertThat(contents).allSatisfy(content -> {
            assertThat(content).as("범위를 닫는 문장(설계 개정 A6)").endsWith(InstructionTemplates.SCOPE_CLOSE);
            assertThat(content).contains("이번 한 번만", "그 뒤에는 평소처럼");
            assertThat(content.codePointCount(0, content.length())).as("500자 안쪽(500토큰 이하)").isLessThan(500);
        });
        assertThat(DialogInstructions.Correction.values()).allSatisfy(correction ->
                assertThat(InstructionTemplates.correctionContent(correction)).contains(InstructionTemplates.INTERJECTION_LEAD));
        assertThat(InstructionTemplates.ResponseStart.values()).allSatisfy(start ->
                assertThat(start.content()).contains(InstructionTemplates.INTERJECTION_LEAD));
        assertThat(InstructionTemplates.scoped(InstructionTemplates.ResponseStart.FIRST.content()))
                .as("이미 닫힌 지시는 한 번 더 닫지 않는다").isEqualTo(InstructionTemplates.ResponseStart.FIRST.content());
    }

    @Test
    void inputAudioMustBeExactlyOneFrameAndGoesOutUntransformed() {
        byte[] pcm = new byte[AudioFrameCodec.INPUT_PAYLOAD_BYTES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i * 3);
        }

        JsonNode append = wire(factory.inputAudio(pcm));

        assertThat(Base64.getDecoder().decode(append.get("audio").asString())).isEqualTo(pcm);
        assertThatThrownBy(() -> factory.inputAudio(new byte[AudioFrameCodec.INPUT_PAYLOAD_BYTES - 1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.inputAudio(null)).isInstanceOf(NullPointerException.class);
    }
}
