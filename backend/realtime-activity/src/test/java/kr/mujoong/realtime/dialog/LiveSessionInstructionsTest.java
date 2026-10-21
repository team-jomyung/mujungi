package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import kr.mujoong.contract.status.ActivityType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 활동별 세션 지시문 묶음: 자원 읽기 · 판 · 해시 · 기동 거부. 공급자를 부르지 않는다. */
@Tag("voice-session")
class LiveSessionInstructionsTest {

    private static final String FREE_TALK_BODY = "참여가 확인되면 관심사나 일상 질문 하나로 시작한다.";

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = LiveSessionInstructionsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** 실행 자원에 바꿔치기 하나를 얹은 읽기 함수. */
    private static java.util.function.Function<String, byte[]> withOverride(String path, byte[] bytes) {
        Map<String, byte[]> overrides = new HashMap<>();
        overrides.put(path, bytes);
        return name -> {
            if (overrides.containsKey(name)) {
                return overrides.get(name);
            }
            try {
                return resource(name);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    @Test
    void bothActivityBodiesLoadWithTheirVersionAndTheSha256OfTheSentBytes() throws Exception {
        LiveSessionInstructions live = LiveSessionInstructions.loadDefault();

        LiveSessionInstructions.Body free = live.of(ActivityType.FREE_TALK);
        LiveSessionInstructions.Body practice = live.of(ActivityType.PRACTICE);
        assertThat(free.version()).isEqualTo("live-free-talk.v1");
        assertThat(practice.version()).isEqualTo("live-practice.v1");
        for (LiveSessionInstructions.Body body : java.util.List.of(free, practice)) {
            assertThat(body.sha256()).matches("[0-9a-f]{64}");
            String file = new String(resource(LiveSessionInstructions.resource(body.version())), StandardCharsets.UTF_8);
            assertThat(body.text()).isEqualTo(file.strip());
            assertThat(body.sha256()).isEqualTo(sha256(file.strip()));
        }
        assertThat(free.sha256()).isNotEqualTo(practice.sha256());
    }

    @Test
    void theFreeTalkBodyIsTheEarlierJoinSentenceAndThePracticeBodyDropsIt() {
        LiveSessionInstructions live = LiveSessionInstructions.loadDefault();

        assertThat(live.of(ActivityType.FREE_TALK).text()).isEqualTo(FREE_TALK_BODY);
        assertThat(live.of(ActivityType.PRACTICE).text()).doesNotContain("관심사나 일상 질문", "질문 하나로 시작")
                .contains("역할놀이", "친구 역할", "코치 역할은 지시가 있을 때만", "대신 알려 주거나 힌트를 주지 않는다");
    }

    @Test
    void theResourceNameComesFromTheVersionAndOtherShapesAreRefused() {
        assertThat(LiveSessionInstructions.resource("live-practice.v1")).isEqualTo("live-prompts/practice.v1.txt");
        assertThat(LiveSessionInstructions.resource("live-free-talk.v2")).isEqualTo("live-prompts/free-talk.v2.txt");
        for (String bad : new String[] {null, "practice.v1", "live-../x.v1", "live-practice", "LIVE-practice.v1"}) {
            assertThatThrownBy(() -> LiveSessionInstructions.resource(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aMissingOrBlankResourceRefusesStartupWithoutQuotingABody() {
        String path = LiveSessionInstructions.resource(LiveSessionInstructions.PRACTICE_VERSION);

        assertThatThrownBy(() -> LiveSessionInstructions.load(withOverride(path, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("없다").hasMessageContaining(path);
        assertThatThrownBy(() -> LiveSessionInstructions.load(withOverride(path, " \n\t\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("비었다");
    }

    @Test
    void anOversizedOrTemplatedResourceRefusesStartupWithoutQuotingABody() {
        String path = LiveSessionInstructions.resource(LiveSessionInstructions.FREE_TALK_VERSION);
        String oversized = "가".repeat(LiveSessionInstructions.MAX_LENGTH + 1);
        String templated = "이름은 {이름}이다. 합성 지시 문장이다.";

        assertThatThrownBy(() -> LiveSessionInstructions.load(withOverride(path, oversized.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("상한").hasMessageNotContaining("가가가");
        assertThatThrownBy(() -> LiveSessionInstructions.load(withOverride(path, templated.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("자리 표시자").hasMessageNotContaining("합성 지시");
        String atLimit = "가".repeat(LiveSessionInstructions.MAX_LENGTH);
        assertThat(LiveSessionInstructions.load(withOverride(path, (atLimit + "\n").getBytes(StandardCharsets.UTF_8)))
                .of(ActivityType.FREE_TALK).text()).hasSize(LiveSessionInstructions.MAX_LENGTH);
    }

    @Test
    void theBodyNeverPrintsItsTextAndAnUnknownActivityIsAProgrammingError() {
        LiveSessionInstructions live = LiveSessionInstructions.loadDefault();
        LiveSessionInstructions.Body practice = live.of(ActivityType.PRACTICE);

        assertThat(practice.toString()).contains("live-practice.v1", practice.sha256()).doesNotContain(practice.text(), "역할놀이");
        assertThatThrownBy(() -> live.of(ActivityType.UNRECOGNIZED)).isInstanceOf(IllegalArgumentException.class);
    }
}
