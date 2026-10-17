package kr.mujoong.realtime.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.dialog.DialogInstructions;
import kr.mujoong.realtime.session.ChildContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 공급자 요청 팩토리의 실호출 관측 선 형식 (U11 FR12.1 · BR11.1 · BR11.2 · 편차 (U11-a)). 키 · 식별자는 쓰지 않는다. */
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
    void onlyFiveRequestTypesExistAndEveryOneIsOnTheSupportedList() {
        List<Object> requests = List.of(factory.sessionStart(new ChildContext("하늘", 8, List.of())),
                factory.inputAudio(new byte[AudioFrameCodec.INPUT_PAYLOAD_BYTES]),
                factory.instructionsAppend(DialogInstructions.Correction.PRESSURE), factory.responseCreate(),
                factory.sessionClose());

        Set<String> types = new HashSet<>();
        for (Object request : requests) {
            types.add(wire(request).get("type").asString());
        }

        assertThat(types).containsExactlyInAnyOrder("session.start", "session.input_audio.append",
                "session.instructions.append", "response.create", "session.close");
        assertThat(types).isEqualTo(ProviderRequestFactory.SENT_TYPES);
        assertThat(types).doesNotContain("session.response.cancel", "session.response.create",
                "session.instructions.correct");
    }

    @Test
    void instructionsAppendAndResponseCreateCarryOnlyTheirFixedKeys() {
        JsonNode append = wire(factory.instructionsAppend(DialogInstructions.Correction.INCOMPLETE));
        JsonNode create = wire(factory.responseCreate());

        assertThat(keys(append, "")).containsExactlyInAnyOrder("type", "text");
        assertThat(append.get("text").asString()).isEqualTo(DialogInstructions.Correction.INCOMPLETE.text());
        assertThat(keys(create, "")).containsExactly("type");
        assertThat(JSON.write(factory.sessionClose())).isEqualTo("{\"type\":\"session.close\"}");
    }

    @Test
    void practiceInstructionsGoOutAsInstructionsAppendAfterTheValueTypeCheck() {
        JsonNode append = wire(factory.practiceInstruction(new PracticeInstruction("목표 문장을 한 번 더 안내한다.")));

        assertThat(keys(append, "")).containsExactlyInAnyOrder("type", "text");
        assertThat(append.get("type").asString()).isEqualTo("session.instructions.append");
        assertThat(append.get("text").asString()).isEqualTo("목표 문장을 한 번 더 안내한다.");
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
