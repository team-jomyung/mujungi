package kr.mujoong.realtime.practiceflow;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.json.JsonCodec;

/**
 * 팀장 기준 연습 대화(목표 '다시 말해 바로잡기', 크레용 · 우유 두 장면)를 옮긴 고정 시험 자료
 * {@code src/test/resources/practiceflow/standard-practice-dialogue.json}을 읽는다(시험 소스 전용). 대본은 생성 판 v6 계약 모양이고, 아이 차례는
 * 기준 대화의 아이 대사와 기대 판정이다. 대역 시험({@code StandardPracticeDialogueTest})과 실호출 하네스의 '대본 아이' 모드가 같이 쓴다.
 * 자료는 합성 인물(하늘이 8세)의 가짜 대화이며 실제 아동 데이터가 아니다.
 *
 * @param practiceJson 시작 패키지 practice 조각(JSON 문자열)
 * @param childTurns 기준 대화의 아이 차례(순서대로)
 */
record StandardPracticeDialogue(String practiceJson, List<ChildTurn> childTurns) {

    static final String RESOURCE = "/practiceflow/standard-practice-dialogue.json";

    /**
     * 기준 대화의 아이 차례 한 건.
     *
     * @param scene 장면 번호(1 = 크레용 BASE, 2 = 우유 VARIANT)
     * @param text 아이 대사(기준 대화 그대로)
     * @param point 기대 판정 지점(J2 ~ J5)
     * @param option 기대 첫 선택지 코드
     */
    record ChildTurn(int scene, String text, String point, String option) {
    }

    private record Raw(Map<String, Object> practice, List<ChildTurn> childTurns) {
    }

    static StandardPracticeDialogue load() {
        try (InputStream in = StandardPracticeDialogue.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("기준 연습 대화 자료가 없다: " + RESOURCE);
            }
            Raw raw = JsonCodec.shared().read(new String(in.readAllBytes(), StandardCharsets.UTF_8), Raw.class);
            return new StandardPracticeDialogue(JsonCodec.shared().write(raw.practice()), List.copyOf(raw.childTurns()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 대본(firstScenario) 값. */
    PracticeScript script() {
        return PracticeStartPackage.parse(practiceJson).firstScenario();
    }

    /** firstScenario JSON(계약 스키마 대조용). */
    @SuppressWarnings("unchecked")
    String firstScenarioJson() {
        Map<String, Object> practice = JsonCodec.shared().read(practiceJson, Map.class);
        List<Map<String, Object>> goals = (List<Map<String, Object>>) practice.get("goals");
        return JsonCodec.shared().write(goals.getFirst().get("firstScenario"));
    }
}
