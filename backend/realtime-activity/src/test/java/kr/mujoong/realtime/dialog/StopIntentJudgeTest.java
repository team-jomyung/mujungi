package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kr.mujoong.realtime.dialog.StopIntentJudge.Basis;
import kr.mujoong.realtime.dialog.StopIntentJudge.Judgment;
import kr.mujoong.realtime.dialog.StopIntentJudge.Verdict;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 종료 의도 판정기 시험(U3 L4 · U3-R12 · AC6.4.4 ~ 6.4.6 · AC6.4.8).
 *
 * <p>AC6.4.8 합성 세트에서 오인식(종료 아님 → STOP) 0, 미인식(종료 → STOP 아님) 1 이하를 확인하고, 규칙별 경계를 따로 본다. 세션 스냅숏은
 * 기본 표현 "무중아 그만할래"와 보호자 추가 표현 "이제 끝"이다. 2026-10-23부터 세션은 등록 종료 표현으로만 끝나므로(FT-1023-a) 판정기가
 * 따로 갖던 종료 말("그만할래" · "끝낼래" · "더 얘기하기 싫어" …)은 종료 아님 쪽으로 옮겼다.
 */
@Tag("voice-session")
@org.junit.jupiter.api.Tag("service-guardrail")
class StopIntentJudgeTest {

    private static final List<String> SNAPSHOT = List.of("무중아 그만할래", "이제 끝");

    private final StopIntentJudge judge = new StopIntentJudge(SNAPSHOT);

    /** 합성 세트: 아이의 종료 의도 10건. */
    private static final List<String> STOPS = List.of(
            "무중아 그만할래",
            "무중아, 그만할래!",
            "음 무중아 그만할래요",
            "이제 끝",
            "무중아 그만할래, 더 얘기 안 할래",
            "무중아 그만할래?");

    /** 등록되지 않은 종료 말: 2026-10-23까지 판정기가 종료로 보던 말이다(FT-1023-a). */
    private static final List<String> UNREGISTERED_STOP_WORDS = List.of(
            "나 이제 그만할래",
            "오늘은 여기까지 할래",
            "이제 끝낼래",
            "그만하고 싶어요",
            "나 이제 얘기 그만할래",
            "무중아 우리 이제 끝내자",
            "더 얘기하기 싫어",
            "더 얘기 안 할래",
            "나 이제 더 하고 싶지 않아");

    /** 합성 세트: 종료가 아닌 말 10건(인용 · 남의 말 · 주제 거부 · 역할 놀이 · 계속 · 불명확). */
    private static final List<String> NOT_STOPS = List.of(
            "친구가 무중아 그만할래 라고 했어",
            "엄마가 그만하래",
            "공룡 얘기 그만할래",
            "이거 말고 다른 이야기 하자",
            "곰돌이 흉내 낼게 나 그만할래",
            "그만 안 할래 더 할래",
            "그만하기 싫어",
            "쉬고 싶어",
            "그만",
            "이제 끝난 거 같아 그 만화",
            "그만하고 싶지 않아",
            "더 얘기 안 하면 안 돼",
            "엄마가 그만하자구 했어",
            "그만하자는 거 아니야 더 놀자");

    @Test
    void theSyntheticTwentyHasNoFalseStopAndAtMostOneMissedStop() {
        assertThat(STOPS).hasSize(6);
        assertThat(NOT_STOPS).hasSize(14);
        assertThat(UNREGISTERED_STOP_WORDS).hasSize(9);

        long falseStops = java.util.stream.Stream.concat(NOT_STOPS.stream(), UNREGISTERED_STOP_WORDS.stream())
                .filter(text -> judge.judge(text).verdict() == Verdict.STOP).count();
        long missedStops = STOPS.stream().filter(text -> judge.judge(text).verdict() != Verdict.STOP).count();

        assertThat(falseStops).as("오인식(AC6.4.8: 0)").isZero();
        assertThat(missedStops).as("미인식(AC6.4.8: 1 이하)").isLessThanOrEqualTo(1);
        // 지금 규칙은 세트 전부를 맞힌다(미인식 0)
        assertThat(missedStops).isZero();
    }

    @Test
    void theSnapshotMatchesOnWordBoundariesWithFlexibleSpacingAndAFinalEnding() {
        assertThat(judge.judge("무중아그만할래")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("무중아 그만 할래")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("이제 끝이야")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        // 낱말 안에서 걸리는 것은 표현이 아니다
        assertThat(judge.judge("이제 끝난 것 같아").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("오늘 이제 끝말잇기 하자").verdict()).isEqualTo(Verdict.NONE);
    }

    @Test
    void onlyRegisteredExpressionsStopEvenAsAQuestion() {
        // 판정기가 따로 갖던 종료 말은 더 종료가 아니다(2026-10-23 결정, FT-1023-a)
        assertThat(judge.judge("나 그만할래")).isEqualTo(new Judgment(Verdict.NONE, null));
        assertThat(judge.judge("이제 끝내자.")).isEqualTo(new Judgment(Verdict.NONE, null));
        assertThat(judge.judge("그만할래?")).isEqualTo(new Judgment(Verdict.NONE, null));
        assertThat(judge.judge("우리 이제 끝낼까")).isEqualTo(new Judgment(Verdict.NONE, null));
        assertThat(judge.judge("이제 그만할까")).isEqualTo(new Judgment(Verdict.NONE, null));
        assertThat(judge.judge("대화 끝")).isEqualTo(new Judgment(Verdict.NONE, null));
        assertThat(new StopIntentJudge(List.of()).judge("이제 끝이야")).as("스냅숏이 비면 무엇도 종료가 아니다")
                .isEqualTo(new Judgment(Verdict.NONE, null));
        // 전사의 물음표는 믿을 수 없어 등록 종료 표현은 물음꼴도 종료다(2026-10-22 결정, 확인 질문 폐기)
        assertThat(judge.judge("이제 끝이야?")).as("스냅숏 표현도 물음꼴이면 종료")
                .isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("무중아 그만할래?")).as("등록 종료 표현의 물음꼴")
                .isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
    }

    /** 불명확한 종료 신호와 문장 전체 신호는 확인 질문 없이 NONE이다(2026-10-22 결정, 애매한 신호 목록 삭제). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "그만",
        "그만그만",
        "이제 그만",
        "끝",
        "그만해",
        "끝내",
        "쉬고 싶어",
        "나 졸려",
        "졸려",
        "힘들어",
        "됐어",
        "재미없어",
        "안 할래",
        "안해",
        "바이바이",
        "그만 좀 웃겨",
        "공룡이 좋아",
        "티라노는 끝까지 싸웠어"
    })
    void unclearSignalsAndOrdinaryTalkAreNothing(String text) {
        assertThat(judge.judge(text)).isEqualTo(new Judgment(Verdict.NONE, null));
    }

    /** 일상 말이 애매한 신호에 걸려 응답이 끊기던 오탐 회귀(2026-10-22 현장 시험): 모두 NONE이다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "숙제를 안해가서 선생님한테 맞았어",
        "숙제를 안 해가서 혼났어",
        "왜 대답 안 해줘",
        "어떻게 됐어",
        "수학이 힘들어",
        "오늘 너무 힘들어서 졸려",
        "동생이 장난감 안 줘서 속상했어"
    })
    void everydayTalkThatOnceTrippedUnclearSignalsIsNothing(String text) {
        assertThat(judge.judge(text).verdict()).isEqualTo(Verdict.NONE);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "동생이 무중아 그만할래 라고 말했어",
        "선생님이 그만하라고 했어",
        "아빠가 그러던데 그만할래",
        "역할놀이에서 공주가 그만할래 했어",
        "그 얘기는 그만할래",
        "공룡 이야기 그만하자",
        "이거 그만하고 다른 거 하자",
        "아직 그만할래 아니야 더 얘기하자",
        "계속할래",
        "끝내기 싫어"
    })
    void reportedSpeechRolePlayTopicRefusalAndContinuingAreNeverStops(String text) {
        assertThat(judge.judge(text).verdict()).isEqualTo(Verdict.NONE);
    }

    /** 리뷰 반영 3: 청유형 인용("~자고 했어" · "~재")은 남의 말이다 — 명확한 종료 표현이 들어 있어도 STOP이 아니다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "엄마가 그만하자고 했어",
        "아빠가 이제 끝내자고 했어",
        "동생이 그만하자고 말했어",
        "형이 여기까지 하자고 그랬어",
        "선생님이 그만하자고 해서 멈췄어",
        "할머니가 그만하자고 하셨어",
        "누나가 그만하재",
        "친구가 이제 끝내재",
        "엄마가 그만하자더라",
        "아빠가 그만하자던데"
    })
    void hortativeReportedSpeechIsNeverAStop(String text) {
        assertThat(judge.judge(text).verdict()).isEqualTo(Verdict.NONE);
    }

    /** 2차 리뷰 1: 청유형 인용 표지는 낱말 경계를 넘어 걸리지 않는다 — "혼자 대화" · "피자 대신"의 '자대'는 인용이 아니다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "나 혼자 대화하는 거 무중아 그만할래",
        "피자 대신 이제 무중아 그만할래",
        "혼자 더라도 무중아 그만할래",
        "의자 던데 무중아 그만할래",
        "나 혼자 고민했어 무중아 그만할래",
        "동생이랑 같이 자고 싶어 무중아 그만할래"
    })
    void hortativeQuoteMarkersDoNotCrossWordBoundaries(String text) {
        assertThat(judge.judge(text)).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "엄마가 그만하자고 해",
        "엄마가 그만하자고해서 멈췄어",
        "아빠가 끝내자고 말했어",
        "형이 그만하자고 그러더라",
        "누나가 그만하자더라고",
        "동생이 그만하자대"
    })
    void hortativeQuotesAreStillFoundWithOrWithoutASpaceBeforeTheVerb(String text) {
        assertThat(judge.judge(text).verdict()).isEqualTo(Verdict.NONE);
    }

    /** 3차 리뷰 1: 청유형 인용 뒤에 어미(요 · 고요 · 서 · 잖아 · 구)가 더 붙어도 남의 말이다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "누나가 그만하자더라고요",
        "엄마가 그만하자던데요",
        "엄마가 그만하자대서 왔어",
        "형이 그만하자더라구",
        "동생이 그만하자더라잖아",
        "아빠가 끝내자더래",
        "엄마가 그만두자대요",
        "누나가 그만하재서 왔어",
        "엄마가 그만하자고 그래",
        "아빠가 그만하자고 그래서 멈췄어"
    })
    void hortativeQuotesWithFurtherEndingsAreStillReportedSpeech(String text) {
        assertThat(judge.judge(text).verdict()).isEqualTo(Verdict.NONE);
    }

    /** 2차 리뷰 6: 종료 의도를 푸는 것은 적극적인 계속 표현뿐이다(남의 말 · 주제 거부 · 평범한 말은 아니다). */
    @Test
    void onlyAnActiveContinuationCountsAsContinuing() {
        assertThat(judge.continues("아니 더 얘기하자")).isTrue();
        assertThat(judge.continues("그만 안 할래 계속할래")).isTrue();
        assertThat(judge.continues("엄마가 그만하라고 했어")).isFalse();
        assertThat(judge.continues("공룡 얘기 그만")).isFalse();
        assertThat(judge.continues("공룡이 좋아")).isFalse();
        assertThat(judge.continues(null)).isFalse();
    }

    /** 3차 리뷰 2(c): 흔한 계속 표현은 종료 의도를 푼다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "계속해",
        "더 해 줘",
        "또 하자",
        "다시 하자",
        "더 놀자",
        "아니 계속 얘기하자",
        "끝내지 마"
    })
    void commonContinuationsLiftTheStopIntent(String text) {
        assertThat(judge.continues(text)).isTrue();
    }

    /**
     * 3차 리뷰 2(a) · (b): 부정형 계속 표현 · 남의 말 속 계속 표현 · 판정이 STOP인 말은 종료 의도를 풀지 않는다.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "더 얘기 안 할래",
        "더 하고 싶지 않아",
        "계속하고 싶지 않아",
        "계속하기 싫어",
        "더 하자고 하지 마",
        "형이 더 하자고 했는데 나는 그만할래",
        "누나가 계속하재",
        "엄마가 계속하라고 했어",
        "다음에 또 하자",
        "나중에 다시 하자",
        "더 놀자는 말은 무중아 그만할래",
        "더 얘기하기 싫어",
        "계속 얘기하기 싫어",
        "더 이야기하고 싶지 않아",
        "더 얘기하지 마",
        "더 얘기할 거 없어",
        "더 얘기는 안 할래",
        "더 놀고 싶지 않아",
        "더 하고 싶진 않아",
        "더 하고 싶지는 않아",
        "계속하고 싶지만 이제 끝",
        "더 하고 싶은데 졸려서 무중아 그만할래",
        "더 얘기하자는 말은 무중아 그만할래",
        "형이 더 하자구 했는데 나는 그만할래"
    })
    void negatedReportedOrStoppingContinuationsDoNotLift(String text) {
        assertThat(judge.continues(text)).isFalse();
    }

    /** 계속 표현에 부정이 붙은 말은 2026-10-23부터 종료가 아니다(등록 종료 표현만 끝낸다, FT-1023-a). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "더 얘기하기 싫어",
        "더 얘기 안 할래",
        "계속 얘기하기 싫어",
        "더 이야기하고 싶지 않아",
        "더 얘기하지 마",
        "더 얘기할 거 없어",
        "더 얘기는 안 할래",
        "더 놀고 싶지 않아",
        "더 하고 싶진 않아",
        "더 하고 싶지는 않아",
        "더 안 할래",
        "더는 싫어",
        "더 이상 못 하겠어",
        "무중아 이제 더 얘기 안 할래"
    })
    void aContinuationFollowedByANegationIsNoLongerAStop(String text) {
        assertThat(judge.judge(text)).isEqualTo(new Judgment(Verdict.NONE, null));
    }

    /** 부정이 붙은 계속 표현은 물음꼴 · 주제 낱말 · 이중 부정 · 다른 절의 부정 어느 쪽이든 종료가 아니다(FT-1023-a). */
    @Test
    void aNegatedContinuationIsNothingInAnyShape() {
        assertThat(judge.judge("더 얘기 안 할래?").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("공룡 얘기 더 하기 싫어").verdict()).as("주제 낱말이 앞선다").isEqualTo(Verdict.NONE);
        assertThat(judge.judge("더 얘기 안 하면 안 돼").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("더 얘기하기 싫은 건 아니야").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("더 하자 엄마는 싫대").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("계속해서 재미없어").verdict()).as("'계속해서'는 계속 표현이 아니다").isEqualTo(Verdict.NONE);
    }

    /** 3차 리뷰 후속 ②: 한 문장에 등록 종료 표현이 있으면 계속 표현보다 종료가 이긴다. 인용 · 종료를 되받는 부정은 그대로 NONE이다. */
    @Test
    void aStopExpressionWinsOverAContinuationInTheSameSentence() {
        assertThat(judge.judge("무중아 그만할래, 더 얘기 안 할래")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("무중아 그만할래 더 하고 싶지 않아")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("더 하고 싶은데 졸려서 무중아 그만할래")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("계속하고 싶지만 이제 끝")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("무중아 그만할래? 아니면 더 할래")).as("고르는 물음도 등록 종료 표현이 이긴다")
                .isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));

        assertThat(judge.judge("아직 그만할래 아니야 더 얘기하자").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("그만하자는 거 아니야 더 놀자").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("무중아 그만할래 아니야").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("형이 더 하자고 했는데 나는 그만할래").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("그만하고 싶지 않아").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("그만할 생각 없어").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("그만하면 안 돼").verdict()).isEqualTo(Verdict.NONE);
    }

    /** 3차 리뷰 후속: 입말 '-구' 인용과 다른 흔한 인용꼴(-자면서 · -자네 · -자셨어 · '-자고 화냈어' · 조사 없는 인용 · '-다고 했어' · '-대')은 남의 말이다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "엄마가 그만하자구 했어",
        "게임 했는데 엄마가 그만하자구 해서 그만했어",
        "친구가 무중아 그만할래 라구 했어",
        "아빠가 끝내자구 그랬어",
        "아까 엄마가 게임 그만하자면서 화냈어",
        "엄마가 이제 그만하자네",
        "할머니가 그만하자셨어",
        "엄마가 게임 그만하자고 화냈어",
        "엄마가 그만하자 해서 왔어",
        "동생이 그만할래 이랬어",
        "어제 친구가 축구 그만하고 싶다고 해서 집에 왔어",
        "동생이 그만하고 싶대"
    })
    void colloquialAndOtherQuoteFormsAreReportedSpeech(String text) {
        assertThat(judge.judge(text).verdict()).isEqualTo(Verdict.NONE);
    }

    /** 문장 끝의 '-자고(요)'는 아이 자신의 강조라 인용이 아니다 — 같은 문장의 등록 종료 표현을 막지 않는다. 그 말만으로는 종료가 아니다(FT-1023-a). */
    @Test
    void aSentenceFinalHortativeJagoIsTheChildsOwnEmphasis() {
        assertThat(judge.judge("무중아 그만할래 이제 그만하자고!")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("무중아 그만할래 그만하자고요")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("이제 그만하자고!").verdict()).isEqualTo(Verdict.NONE);
    }

    /** 3차 리뷰 후속: 떠나는 말 · 해야 할 일 · 이어 주는 말은 종료 의도를 풀지 않는다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "담에 또 하자",
        "이따가 또 하자",
        "자고 일어나서 또 하자",
        "다음번에 또 하자",
        "숙제 더 해야 돼",
        "엄마가 계속해서 불러",
        "오늘은 그만, 안녕!",
        "왜 안 끝나"
    })
    void farewellsChoresAndConnectivesDoNotLift(String text) {
        assertThat(judge.continues(text)).isFalse();
    }

    /** 3차 리뷰 후속: "안 끝났어"와 흔한 계속 말은 종료 의도를 푼다("그다음에"는 미루는 말이 아니고, "안 졸려"는 불명확 신호가 아니다). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "안 끝났어",
        "나 아직 얘기 안 끝났어",
        "계속 놀자",
        "다시 해 줘",
        "또 해 줘",
        "더 할 거야",
        "그 다음에 어떻게 됐는지 더 얘기해 줘",
        "안 졸려, 계속해"
    })
    void notFinishedAndCommonContinuationsLift(String text) {
        assertThat(judge.continues(text)).isTrue();
    }

    /** 이유 · 다그침의 '-니까'가 뒤따라도 등록 종료 표현이면 STOP이다. 등록되지 않은 '-ㄹ까' 물음은 종료가 아니다(FT-1023-a). */
    @Test
    void aReasonNikkaAfterARegisteredStopIsAStopButUnregisteredKkaQuestionsAreNot() {
        assertThat(judge.judge("무중아 그만할래, 졸리니까")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("무중아 그만할래 엄마가 부르니까")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("그만하자니까!").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("오늘은 여기까지 할까").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("이제 끝낼까요").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("우리 그만할까요").verdict()).isEqualTo(Verdict.NONE);
    }

    /** 흔한 종료 말 · 짧은 신호 · 주제 낱말 뒤의 그만은 모두 종료가 아니다(2026-10-22 · 2026-10-23 결정, FT-1023-a). */
    @Test
    void commonStopWordsShortSignalsAndTopicStopsAreNothing() {
        assertThat(judge.judge("그만 얘기하자").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("이제 그만할 거예요").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("나 이제 그만할라고").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("이제 갈게").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("이제 그만 놀래").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("그만 놀자").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("무중아 그만해").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("이제 끝내").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("오늘은 그만, 안녕!").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("공룡 그만 얘기할래").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("그만해 간지러워").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("안 졸려").verdict()).isEqualTo(Verdict.NONE);
    }

    /** 보호자가 "그만하자"를 등록했을 때 청유형 인용 표지가 그 표현이나 '재'가 든 낱말을 삼키지 않는다. */
    @Test
    void theHortativeQuoteMarkersDoNotSwallowARegisteredHortativeOrWordsWithJae() {
        StopIntentJudge custom = new StopIntentJudge(List.of("무중아 그만할래", "그만하자"));
        assertThat(custom.judge("그만하자")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(custom.judge("재미없어 그만하자")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(custom.judge("그만하자 재미없어")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(custom.judge("이제 끝내 재미없어").verdict()).isEqualTo(Verdict.NONE);
        assertThat(custom.judge("나는 천재").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("그만하자").verdict()).as("등록하지 않으면 종료가 아니다").isEqualTo(Verdict.NONE);
    }

    @Test
    void blankInputIsNothing() {
        assertThat(judge.judge(null).verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("   ").verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("?!").verdict()).isEqualTo(Verdict.NONE);
    }

    @Test
    void theSnapshotIgnoresBlankDuplicateAndOneLetterExpressions() {
        StopIntentJudge custom = new StopIntentJudge(java.util.Arrays.asList("그만 놀래", "그만 놀래", " ", "끝", null));

        assertThat(custom.judge("나 그만 놀래")).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(custom.judge("끝").verdict()).as("한 글자 표현은 스냅숏에서 빠진다(종료로 보지 않는다)")
                .isEqualTo(Verdict.NONE);
        assertThat(new StopIntentJudge(null).judge("무중아 그만할래")).as("스냅숏이 없으면 무엇도 종료가 아니다(기본 표현은 core가 스냅숏에 넣는다)")
                .isEqualTo(new Judgment(Verdict.NONE, null));
    }

    @Test
    void normalizationFoldsWidthCaseAndDropsPunctuationAndOtherScripts() {
        assertThat(StopIntentJudge.normalize("  무중아,  그만할래!! ")).isEqualTo("무중아그만할래");
        assertThat(StopIntentJudge.normalize("ＳＴＯＰ  Ok")).isEqualTo("stopok");
        assertThat(StopIntentJudge.normalize("끝😀")).isEqualTo("끝");
        assertThat(StopIntentJudge.normalize(null)).isEmpty();
    }

    @Test
    void aStopJudgmentAlwaysCarriesItsBasisAndOthersNever() {
        assertThatThrownBy(() -> new Judgment(Verdict.STOP, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Judgment(Verdict.NONE, Basis.SNAPSHOT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
