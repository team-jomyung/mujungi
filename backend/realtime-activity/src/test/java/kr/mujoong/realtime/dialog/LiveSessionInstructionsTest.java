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
        assertThat(practice.version()).isEqualTo("live-practice.v2");
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
                .contains("친구 역할", "코치 역할은 지시가 있을 때만", "대신 알려 주거나 힌트를 주지 않는다");
    }

    /**
     * 4회차 R3(실측 B3 · B6): 연습 본문 v2는 서버가 이끄는 연습이라 지시가 없을 때 아이 말에는 '응.' 한 마디로만 답하고 스스로 장면 · 역할 · 질문을 만들지
     * 않게 하며, 무중과 아이 이름을 가른다. 자기 놀이를 부르던 낱말('역할놀이')은 쓰지 않는다. 앞 판(v1)은 기록을 되짚을 수 있게 자원에 남는다.
     */
    @Test
    void thePracticeBodyV2LeadsAServerRunPracticeAnswersOnlyOkayWithoutAnInstructionAndSeparatesTheNames() throws Exception {
        LiveSessionInstructions.Body practice = LiveSessionInstructions.loadDefault().of(ActivityType.PRACTICE);

        assertThat(practice.text()).contains("서버가 순서를 정해 이끄는 말하기 연습", "진행 지시가 없을 때 아이가 말하면", "'응.' 한 마디로만 답하고",
                "새 장면이나 역할이나 질문이나 놀이를 스스로 만들지 않는다", "지시한 말을 한 번만 하고 다른 말을 덧붙이지 않는다",
                "너의 이름은 무중이다", "아이의 이름은 위 아이 정보의 이름", "아이를 무중이라고 부르지 않는다");
        assertThat(practice.text()).as("자기 놀이 · 자유 응답을 부르는 낱말").doesNotContain("역할놀이", "평소처럼");
        String first = new String(resource(LiveSessionInstructions.resource("live-practice.v1")), StandardCharsets.UTF_8).strip();
        assertThat(first).as("앞 판은 남는다").contains("역할놀이 연습");
        assertThat(practice.sha256()).isNotEqualTo(sha256(first));
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

        assertThat(practice.toString()).contains("live-practice.v2", practice.sha256()).doesNotContain(practice.text(), "말하기 연습");
        assertThatThrownBy(() -> live.of(ActivityType.UNRECOGNIZED)).isInstanceOf(IllegalArgumentException.class);
    }
}
