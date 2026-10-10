package kr.mujoong.realtime.voice;

import static kr.mujoong.realtime.testing.RealtimeItEnvironment.WAIT;
import static kr.mujoong.realtime.testing.RealtimeItEnvironment.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import kr.mujoong.contract.status.SessionStatus;
import kr.mujoong.contract.ws.WsEnvelope;
import kr.mujoong.contract.ws.WsMessageType;
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
 */
@Tag("u1")
@Tag("db")
class ProviderRequestWhitelistIT {

    private static final Set<String> SESSION_START_KEYS = Set.of("type", "model", "instructions", "childContext",
            "childContext.displayName", "childContext.age", "childContext.interests", "audio", "audio.input",
            "audio.output");
    private static final Set<String> INPUT_AUDIO_KEYS = Set.of("type", "audio");
    private static final Set<String> SESSION_CLOSE_KEYS = Set.of("type");
    private static final Set<String> FORBIDDEN_FIELDS = Set.of("judgmentCriteria", "knownContent", "question",
            "guardianId", "childId", "deviceId", "sessionId", "email", "consentSnapshot", "practice", "goals");

    @Test
    void everyProviderMessageStaysInsideTheWhitelistAndCarriesNoOtherPackageValue() {
        try (RealtimeItEnvironment env = RealtimeItEnvironment.start()) {
            DeviceSimulatorClient device = env.connectDevice();
            UUID sessionId = UUID.randomUUID();
            StartSessionHandler.StartCommand command = env.startCommand(sessionId,
                    new ChildContext("하늘", 8, List.of("공룡", "우주")));
            env.app.bean(StartSessionHandler.class).start(command, null);
            WsEnvelope start = device.await(WsMessageType.START_SESSION, WAIT);
            device.ack(start.opId(), sessionId);
            device.deviceReady(sessionId, true, true);
            awaitTrue(() -> env.app.bean(ActivitySessionRepository.class).find(sessionId)
                    .map(row -> row.status() == SessionStatus.ACTIVE).orElse(false), "ACTIVE");
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
            assertThat(everything).contains("하늘").contains("공룡");
            // 입력 음성은 무변환으로 간다(J131)
            assertThat(env.provider.audioHashes()).hasSize(3).doesNotHaveDuplicates();
        }
    }

    @Test
    void theRequestRecordsDeclareOnlyWhitelistedComponentsAndNoFreeFormMaps() {
        assertThat(components(ProviderRequestFactory.SessionStart.class))
                .containsExactly("type", "model", "instructions", "childContext", "audio");
        assertThat(components(ProviderRequestFactory.ChildContextData.class))
                .containsExactly("displayName", "age", "interests");
        assertThat(components(ProviderRequestFactory.InputAudioAppend.class)).containsExactly("type", "audio");
        assertThat(components(ProviderRequestFactory.SessionClose.class)).containsExactly("type");
        for (Class<?> type : List.of(ProviderRequestFactory.SessionStart.class,
                ProviderRequestFactory.ChildContextData.class, ProviderRequestFactory.AudioSpec.class,
                ProviderRequestFactory.InputAudioAppend.class, ProviderRequestFactory.SessionClose.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertThat(Map.class.isAssignableFrom(component.getType())).as(component.getName()).isFalse();
                assertThat(FORBIDDEN_FIELDS).doesNotContain(component.getName());
                if (Collection.class.isAssignableFrom(component.getType())) {
                    assertThat(component.getGenericType().getTypeName()).isEqualTo("java.util.List<java.lang.String>");
                }
            }
        }
        // 공장 공개 메서드는 아이 맥락 · 음성 프레임 · 정리만 받는다(세션 패키지 전체를 받는 입구가 없다)
        assertThat(Arrays.stream(ProviderRequestFactory.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(method.getParameterTypes())).map(Class::getSimpleName))
                .allMatch(name -> Set.of("ChildContext", "byte[]").contains(name));
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
