package kr.mujoong.realtime.voice;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.mujoong.contract.audio.AudioFrameCodec;
import kr.mujoong.contract.context.ConversationGuide;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
import kr.mujoong.contract.ws.message.SessionState;
import kr.mujoong.contract.ws.message.View;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.StartSessionHandler;
import kr.mujoong.realtime.testing.FakeProviderPort;
import kr.mujoong.realtime.testing.RealtimeItEnvironment;
import kr.mujoong.realtime.runtime.TaskLauncher;
import kr.mujoong.testsupport.device.DeviceSimulatorClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 공급자 전송 최소화 시험 (U1 3.5 Step 30 · SD-20 · NFR3.1 · NFR3.2 · J109 · J141).
 *
 * <p>가짜 공급자 서버가 받은 모든 메시지의 키가 허용 목록(지시문 · childContext 세 필드 · 음성 프레임) 안이고, 세션 패키지의 다른 값(보호자 ·
 * 아이 · 기기 · 세션 id, 종료 표현, 승인 문구, 동의 판)이 요청 어디에도 없으며, 요청 레코드에 허용 밖 필드가 없고(리플렉션), 허용 목록 밖
 * 주소로는 연결을 열지 않는지 본다.
 *
 * <p>3.6 수정(BT-TC-10)에서 공급자로 나가기 직전의 최소화 경계를 더했다: 아이 맥락은 계약 C8 상한(이름 10자 · 관심사 10개 × 20자, 글자는
 * 코드 포인트)으로 자르고 나이는 6 ~ 12로 묶는다 · 아이 맥락 값은 따옴표 · 역슬래시 · 제어 문자로 지시문의 따옴표 밖으로 나갈 수 없다 · 입력
 * 음성은 정확히 960바이트 프레임만 무변환으로 나간다.
 */
@Tag("skeleton")
@Tag("db")
@org.junit.jupiter.api.Tag("service-guardrail")
class ProviderRequestWhitelistIT {

    /** U11(편차 (U11-a)): 중첩 session{model, instructions, audio{format{type, rate}}}. 아이 정보는 지시문 문장 안에만 있다. */
    private static final Set<String> SESSION_START_KEYS = Set.of("type", "session", "session.model",
            "session.instructions", "session.audio", "session.audio.format", "session.audio.format.type",
            "session.audio.format.rate");
    private static final Set<String> INPUT_AUDIO_KEYS = Set.of("type", "audio");
    private static final Set<String> SESSION_CLOSE_KEYS = Set.of("type");
    /**
     * U11(설계 개정 A1): 지시 추가는 {@code type · event_id · delegation_id(null이어도 있다) · content}만 싣는다. 응답 생성 요청 · 응답 취소는 없다 —
     * 공급자 지원 목록 밖이거나 client 위임 세션이 거절한다.
     */
    private static final Set<String> INSTRUCTIONS_APPEND_KEYS = Set.of("type", "event_id", "delegation_id", "content");
    private static final Set<String> FORBIDDEN_FIELDS = Set.of("judgmentCriteria", "knownContent", "question",
            "guardianId", "childId", "deviceId", "sessionId", "email", "consentSnapshot", "practice", "goals");

    @Test
    void everyProviderMessageStaysInsideTheWhitelistAndCarriesNoOtherPackageValue() {
        try (RealtimeItEnvironment env = RealtimeItEnvironment.start()) {
            DeviceSimulatorClient device = env.connectDevice();
            UUID sessionId = UUID.randomUUID();
            StartSessionHandler.StartCommand original = env.startCommand(sessionId,
                    new ChildContext("하늘", 8, List.of("공룡", "우주")));
            var guide=new kr.mujoong.contract.context.ConversationGuide(List.of(kr.mujoong.contract.context.ConversationGuide.Topic.BLOCKS),kr.mujoong.contract.context.ConversationGuide.SourceType.SEEN);
            var command=new StartSessionHandler.StartCommand(original.sessionId(),original.idempotencyKey(),original.guardianId(),original.childId(),original.deviceId(),original.activityType(),original.consentSnapshot(),original.childContext(),original.stopExpressions(),original.approvedContent(),original.approvedSpeech(),original.practiceJson(),guide);
            env.app.bean(StartSessionHandler.class).start(command, null);
            WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
            device.ack(start.opId(), sessionId);
            device.deviceReady(sessionId, true, true);
            env.join(sessionId);
            awaitTrue(() -> env.app.bean(ActivitySessionRepository.class).find(sessionId)
                    .map(row -> row.status() == SessionStatus.ACTIVE).orElse(false), "ACTIVE");
            // LISTENING은 입력 수락을 연 뒤에 나간다. 실행기는 DB에 ACTIVE를 쓴 다음 중계를 열므로 DB의 ACTIVE만 보고 보내면
            // 그 사이 프레임이 NOT_ACCEPTING으로 버려질 수 있다(3.6 재시험 1차 실패 원인, U3 L6 — RealtimeMetricsScrapeIT와 같은 대기)
            device.await(envelope -> envelope.payload() instanceof SessionState state && state.view() == View.LISTENING,
                    WAIT);
            env.provider.await("session.instructions.append", 1, WAIT);
            assertThat(env.provider.received().stream()
                    .filter(m -> "session.instructions.append".equals(m.get("type").asString()))
                    .map(m -> m.get("content").asString()).toList())
                    .anySatisfy(content -> assertThat(content).contains("블록 놀이에 대해 이야기해 줄래?")
                            .doesNotContain("관심사나 일상 질문"));
            byte[] pcm = new byte[960];
            for (int i = 0; i < 3; i++) {
                pcm[0] = (byte) i;
                device.sendAudio(pcm.clone());
            }
            env.provider.await("session.input_audio.append", 3, WAIT);
            env.app.bean(kr.mujoong.realtime.session.EndRequestHandler.class).end(sessionId, sessionId,
                    kr.mujoong.contract.status.EndRequestReason.CORE_ABANDONED, null);
            env.provider.await("session.close", 1, WAIT);

            for (JsonNode message : env.provider.received()) {
                String type = message.get("type").asString();
                Set<String> keys = keys(message, "");
                switch (type) {
                    case "session.start" -> assertThat(SESSION_START_KEYS).containsAll(keys);
                    case "session.input_audio.append" -> assertThat(INPUT_AUDIO_KEYS).containsAll(keys);
                    case "session.close" -> assertThat(SESSION_CLOSE_KEYS).containsAll(keys);
                    case "session.instructions.append", "session.commentary.append" -> assertThat(INSTRUCTIONS_APPEND_KEYS).containsAll(keys);
                    default -> throw new AssertionError("허용 밖 요청 type: " + type);
                }
            }
            List<String> markers = new ArrayList<>(List.of(sessionId.toString(), env.guardianId.toString(),
                    env.childId.toString(), env.deviceId.toString(), command.idempotencyKey().toString(),
                    "무중아 그만할래"));
            command.approvedContent().values().forEach(item -> {
                markers.add(item.text());
                markers.add(item.contentVersionId().toString());
                markers.add(item.audioUrl());
            });
            String everything = String.join("\n", env.provider.rawReceived());
            assertThat(markers).allSatisfy(marker -> assertThat(everything).doesNotContain(marker));
            assertThat(everything).contains("하늘").contains("공룡").contains("블록 놀이에 대해 이야기해 줄래?").contains("사실 확인이나 아이의 발언이 아니다");
            // 입력 음성은 무변환으로 간다(J131)
            assertThat(env.provider.audioHashes()).hasSize(3).doesNotHaveDuplicates();
        }
    }

    @Test
    void theRequestRecordsDeclareOnlyWhitelistedComponentsAndNoFreeFormMaps() {
        assertThat(components(ProviderRequestFactory.SessionStart.class)).containsExactly("type", "session");
        assertThat(components(ProviderRequestFactory.SessionBody.class)).containsExactly("model", "instructions", "audio");
        assertThat(components(ProviderRequestFactory.AudioSpec.class)).containsExactly("format");
        assertThat(components(ProviderRequestFactory.AudioFormat.class)).containsExactly("type", "rate");
        assertThat(components(ProviderRequestFactory.InputAudioAppend.class)).containsExactly("type", "audio");
        assertThat(components(ProviderRequestFactory.SessionClose.class)).containsExactly("type");
        assertThat(components(ProviderRequestFactory.InstructionsAppend.class))
                .containsExactly("type", "eventId", "delegationId", "content");
        for (Class<?> type : List.of(ProviderRequestFactory.SessionStart.class, ProviderRequestFactory.SessionBody.class,
                ProviderRequestFactory.AudioSpec.class, ProviderRequestFactory.AudioFormat.class,
                ProviderRequestFactory.InputAudioAppend.class, ProviderRequestFactory.SessionClose.class,
                ProviderRequestFactory.InstructionsAppend.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertThat(Map.class.isAssignableFrom(component.getType())).as(component.getName()).isFalse();
                assertThat(FORBIDDEN_FIELDS).doesNotContain(component.getName());
                if (Collection.class.isAssignableFrom(component.getType())) {
                    throw new AssertionError("요청 레코드에 컬렉션 필드가 없다(아이 정보는 지시문 문장 안에만 있다): " + component.getName());
                }
            }
        }
        assertThat(components(ConversationGuide.class)).containsExactly("topics", "sourceType");
        assertThat(ConversationGuide.class.getRecordComponents()[0].getGenericType().getTypeName())
                .isEqualTo("java.util.List<kr.mujoong.contract.context.ConversationGuide$Topic>");
        assertThat(ConversationGuide.class.getRecordComponents()[1].getType()).isEqualTo(ConversationGuide.SourceType.class);
        // 공장 공개 메서드는 아이 맥락 · 음성 프레임 · 정리만 받는다(세션 패키지 전체를 받는 입구가 없다). U3: 형식을 지킨 공급자 응답 id ·
        // 고정 교정 범주만 더한다(자유 문자열 입구 없음)
        assertThat(Arrays.stream(ProviderRequestFactory.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(method.getParameterTypes())).map(Class::getSimpleName))
                // U4b: 연습 확장점 지시문은 검사된 값 타입(PracticeInstruction)으로만 받는다(BR10.4)
                .allMatch(name -> Set.of("ChildContext", "byte[]", "int", "Correction", "PracticeInstruction", "String", "ResponseStart",
                                "ActivityType", "PolicyBundle", "ConversationGuide")
                        .contains(name));
        // U16 internal mode/pinned-policy inputs are allowed only at these exact assembly boundaries.
        // The serialized record and actual wire whitelist above remain unchanged.
        assertThat(Arrays.stream(ProviderRequestFactory.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getParameterTypes()).anyMatch(type ->
                        type == kr.mujoong.contract.status.ActivityType.class
                        || type == kr.mujoong.realtime.dialog.PolicyBundle.class))
                .map(method -> method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName).collect(java.util.stream.Collectors.joining(",")) + ")"))
                .containsExactlyInAnyOrder("servicePolicy(ActivityType)", "sessionStart(ChildContext,PolicyBundle)",
                        "responseStart(String,ResponseStart,ActivityType)", "instructionsAppend(String,Correction,ActivityType)",
                        // 서비스 정책과 대화 전 보호자 참고(main #50)를 함께 싣는 조립 경계. 보호자 참고는 열거값만 받는다
                        "sessionStart(ChildContext,PolicyBundle,ConversationGuide)",
                        "responseStart(String,ResponseStart,ActivityType,ConversationGuide)");
    }

    @Test
    void addressesOutsideTheAllowListAreNeverOpened() throws Exception {
        FakeProviderPort loopbackInProd = new FakeProviderPort(URI.create("ws://127.0.0.1:9/fake-provider"));
        FakeProviderPort publicInTest = new FakeProviderPort(URI.create("wss://provider.invalid/v1"));
        List<ProviderPort.ProviderUnavailableException.Reason> failures = new CopyOnWriteArrayList<>();
        ProviderConnector.Callback callback = new ProviderConnector.Callback() {
            @Override
            public void connected(ProviderPort.ProviderSession session) {
                throw new AssertionError("허용 밖 주소로 연결이 열렸다");
            }

            @Override
            public void failed(ProviderPort.ProviderUnavailableException.Reason reason) {
                failures.add(reason);
            }
        };

        new ProviderConnector(loopbackInProd, new ProviderRequestFactory(), Duration.ofSeconds(1), TaskLauncher.inline(),
                "prod").connect(UUID.randomUUID(), new ChildContext("하늘", 8, List.of()), event -> { }, callback);
        new ProviderConnector(publicInTest, new ProviderRequestFactory(), Duration.ofSeconds(1), TaskLauncher.inline(),
                "test").connect(UUID.randomUUID(), new ChildContext("하늘", 8, List.of()), event -> { }, callback);

        assertThat(failures).containsExactly(ProviderPort.ProviderUnavailableException.Reason.HOST_NOT_ALLOWED,
                ProviderPort.ProviderUnavailableException.Reason.HOST_NOT_ALLOWED);
        assertThat(loopbackInProd.openedCount()).isZero();
        assertThat(publicInTest.openedCount()).isZero();
    }

    @Test
    void childContextIsCutToContractLimitsAndActualAgeIsPreserved() {
        ProviderRequestFactory factory = new ProviderRequestFactory();
        String ten = "가나다라마바사아자차";
        List<String> interests = new ArrayList<>(List.of("  ", "가".repeat(ProviderRequestFactory.MAX_INTEREST_LENGTH + 1)));
        for (int i = 1; i <= ProviderRequestFactory.MAX_INTERESTS + 1; i++) {
            interests.add("관심" + i);
        }

        ProviderRequestFactory.SessionStart start = factory.sessionStart(new ChildContext("  " + ten + "카  ", 13, interests));

        // 아이 정보는 지시문 문장 안에만 있다(선 형식 필드 없음)
        String instructions = start.session().instructions();
        assertThat(instructions).contains("이름: \"" + ten + "\"").doesNotContain(ten + "카");
        assertThat(instructions).contains("나이: \"" + 13 + "\"");
        // 빈 관심사는 빼고 10개에서 멈춘다. 21자 관심사는 20자로 자른다
        assertThat(instructions).contains("\"" + "가".repeat(ProviderRequestFactory.MAX_INTEREST_LENGTH) + "\"", "\"관심1\"")
                .doesNotContain("가".repeat(ProviderRequestFactory.MAX_INTEREST_LENGTH + 1), "관심10", "관심11");
        assertThat(instructions).containsOnlyOnce("관심9");
        String wire = JsonCodec.shared().write(start);
        assertThat(wire).contains(ten).doesNotContain(ten + "카").doesNotContain("관심10");

        // 나이 경계: 5 → 6, 6 · 12 그대로, 없으면 지시문에도 없다
        assertThat(factory.sessionStart(new ChildContext("하늘", 5, List.of())).session().instructions())
                .contains("나이: \"" + ProviderRequestFactory.MIN_AGE + "\"");
        assertThat(factory.sessionStart(new ChildContext("하늘", 6, List.of())).session().instructions()).contains("나이: \"6\"");
        assertThat(factory.sessionStart(new ChildContext("하늘", 12, List.of())).session().instructions()).contains("나이: \"12\"");
        ProviderRequestFactory.SessionStart ageless = factory.sessionStart(new ChildContext("하늘", null, List.of()));
        assertThat(ageless.session().instructions()).doesNotContain("나이");
        // 글자 수는 코드 포인트로 센다: 이모지(UTF-16 두 칸)를 반으로 자르지 않는다
        String nineAndDino = "가나다라마바사아자\uD83E\uDD95";
        assertThat(factory.sessionStart(new ChildContext(nineAndDino + "카", 8, List.of())).session().instructions())
                .contains("이름: \"" + nineAndDino + "\"").doesNotContain(nineAndDino + "카");
    }

    @Test
    void childValuesCannotLeaveTheirQuotesInTheInstructions() {
        ProviderRequestFactory factory = new ProviderRequestFactory();
        String name = "하\"늘\\";
        ChildContext hostile = new ChildContext(name, 8, List.of("공\"룡", "우\n주\u0007"));

        ProviderRequestFactory.SessionStart start = factory.sessionStart(hostile);

        String instructions = start.session().instructions();
        // 따옴표 · 역슬래시는 이스케이프되고 제어 문자는 공백이 된다 — 값이 따옴표를 닫고 지시 문장이 될 수 없다
        assertThat(instructions).contains("이름: \"하\\\"늘\\\\\"")
                .contains("관심사: [\"공\\\"룡\", \"우 주 \"]");
        assertThat(instructions.chars().noneMatch(Character::isISOControl)).as("지시문의 제어 문자").isTrue();
        assertThat(unescapedQuotes(instructions)).as("이스케이프되지 않은 따옴표 = 값 4개(이름 · 나이 · 관심사 둘)의 양 끝")
                .isEqualTo(8);
        // 선 형식 JSON이 이스케이프하고, 해석하면 같은 지시문이 돌아온다
        JsonNode wire = JsonCodec.shared().read(JsonCodec.shared().write(start), JsonNode.class);
        assertThat(wire.get("session").get("instructions").asString()).isEqualTo(instructions);
    }

    @Test
    void onlyExact960ByteFramesLeaveAndTheyLeaveUntransformed() {
        ProviderRequestFactory factory = new ProviderRequestFactory();
        byte[] pcm = new byte[AudioFrameCodec.INPUT_PAYLOAD_BYTES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i * 7);
        }

        ProviderRequestFactory.InputAudioAppend append = factory.inputAudio(pcm);

        assertThat(append.type()).isEqualTo(ProviderRequestFactory.TYPE_INPUT_AUDIO_APPEND);
        assertThat(Base64.getDecoder().decode(append.audio())).isEqualTo(pcm);
        for (int length : new int[] {0, AudioFrameCodec.INPUT_PAYLOAD_BYTES - 1, AudioFrameCodec.INPUT_PAYLOAD_BYTES + 1}) {
            assertThatThrownBy(() -> factory.inputAudio(new byte[length])).as(length + "바이트")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> factory.inputAudio(null)).isInstanceOf(NullPointerException.class);
        assertThat(JsonCodec.shared().write(factory.sessionClose())).isEqualTo("{\"type\":\"session.close\"}");
    }

    /** 역슬래시로 이스케이프되지 않은 따옴표 수. */
    private static int unescapedQuotes(String text) {
        int count = 0;
        boolean escaped = false;
        for (char c : text.toCharArray()) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                count++;
            }
        }
        return count;
    }

    private static List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** 객체 키를 점 경로로 모은다(배열 원소는 값만 본다). */
    private static Set<String> keys(JsonNode node, String prefix) {
        Set<String> keys = new HashSet<>();
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                keys.add(path);
                if (field.getValue().isObject()) {
                    keys.addAll(keys(field.getValue(), path));
                }
            }
        }
        return keys;
    }
}
