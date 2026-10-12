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
 * <p>AC6.4.8 합성 세트 20건(종료 10 · 종료 아님 10)에서 오인식(종료 아님 → STOP) 0, 미인식(종료 → STOP 아님) 1 이하를 확인하고, 규칙별 경계를
 * 따로 본다. 세션 스냅숏은 기본 표현 "무중아 그만할래"와 보호자 추가 표현 "이제 끝"이다.
 */
@Tag("u3")
class StopIntentJudgeTest {

    private static final List<String> SNAPSHOT = List.of("무중아 그만할래", "이제 끝");

    private final StopIntentJudge judge = new StopIntentJudge(SNAPSHOT);

    /** 합성 세트: 아이의 종료 의도 10건. */
    private static final List<String> STOPS = List.of(
            "무중아 그만할래",
            "무중아, 그만할래!",
            "음 무중아 그만할래요",
            "이제 끝",
            "나 이제 그만할래",
            "오늘은 여기까지 할래",
            "이제 끝낼래",
            "그만하고 싶어요",
            "나 이제 얘기 그만할래",
            "무중아 우리 이제 끝내자");

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
            "이제 끝난 거 같아 그 만화");

    @Test
    void theSyntheticTwentyHasNoFalseStopAndAtMostOneMissedStop() {
        assertThat(STOPS).hasSize(10);
        assertThat(NOT_STOPS).hasSize(10);

        long falseStops = NOT_STOPS.stream().filter(text -> judge.judge(text, false).verdict() == Verdict.STOP).count();
        long missedStops = STOPS.stream().filter(text -> judge.judge(text, false).verdict() != Verdict.STOP).count();

        assertThat(falseStops).as("오인식(AC6.4.8: 0)").isZero();
        assertThat(missedStops).as("미인식(AC6.4.8: 1 이하)").isLessThanOrEqualTo(1);
        // 지금 규칙은 세트 전부를 맞힌다(미인식 0)
        assertThat(missedStops).isZero();
    }

    @Test
    void theSnapshotMatchesOnWordBoundariesWithFlexibleSpacingAndAFinalEnding() {
        assertThat(judge.judge("무중아그만할래", false)).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("무중아 그만 할래", false)).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(judge.judge("이제 끝이야", false)).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        // 낱말 안에서 걸리는 것은 표현이 아니다
        assertThat(judge.judge("이제 끝난 것 같아", false).verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("오늘 이제 끝말잇기 하자", false).verdict()).isEqualTo(Verdict.NONE);
    }

    @Test
    void aClearPatternIsAStopWithItsBasisAndAQuestionIsOnlyConfirmed() {
        assertThat(judge.judge("나 그만할래", false)).isEqualTo(new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN));
        assertThat(judge.judge("이제 끝내자.", false)).isEqualTo(new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN));
        assertThat(judge.judge("그만할래?", false).verdict()).isEqualTo(Verdict.CONFIRM);
        assertThat(judge.judge("우리 이제 끝낼까", false).verdict()).as("끝내자는 물음은 확인").isEqualTo(Verdict.CONFIRM);
        assertThat(judge.judge("대화 끝", false)).isEqualTo(new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN));
        assertThat(new StopIntentJudge(List.of()).judge("이제 끝이야", false))
                .isEqualTo(new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN));
        assertThat(judge.judge("이제 끝이야?", false).verdict()).as("스냅숏도 물음꼴은 확인").isEqualTo(Verdict.CONFIRM);
    }

    @ParameterizedTest
    @CsvSource({
        "그만, CONFIRM",
        "그만그만, CONFIRM",
        "이제 그만, CONFIRM",
        "끝, CONFIRM",
        "쉬고 싶어, CONFIRM",
        "나 졸려, CONFIRM",
        "재미없어, CONFIRM",
        "안 할래, CONFIRM",
        "바이바이, CONFIRM",
        "그만 좀 웃겨, NONE",
        "공룡이 좋아, NONE",
        "티라노는 끝까지 싸웠어, NONE"
    })
    void unclearSignalsAreOnlyConfirmedAndOrdinaryTalkIsNothing(String text, Verdict expected) {
        assertThat(judge.judge(text, false).verdict()).isEqualTo(expected);
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
        assertThat(judge.judge(text, false).verdict()).isEqualTo(Verdict.NONE);
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
        assertThat(judge.judge(text, false).verdict()).isEqualTo(Verdict.NONE);
    }

    /** 2차 리뷰 1: 청유형 인용 표지는 낱말 경계를 넘어 걸리지 않는다 — "혼자 대화" · "피자 대신"의 '자대'는 인용이 아니다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "나 혼자 대화하는 거 그만할래",
        "피자 대신 이제 그만할래",
        "혼자 더라도 그만할래",
        "의자 던데 그만할래",
        "나 혼자 고민했어 그만할래",
        "동생이랑 같이 자고 싶어 그만할래"
    })
    void hortativeQuoteMarkersDoNotCrossWordBoundaries(String text) {
        assertThat(judge.judge(text, false)).isEqualTo(new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN));
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
        assertThat(judge.judge(text, false).verdict()).isEqualTo(Verdict.NONE);
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
        assertThat(judge.judge(text, false).verdict()).isEqualTo(Verdict.NONE);
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
     * 3차 리뷰 2(a) · (b): 부정형 계속 표현 · 남의 말 속 계속 표현 · 판정이 STOP이나 CONFIRM인 말은 종료 의도를 풀지 않는다.
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
        "더 놀자는 말은 그만할래"
    })
    void negatedReportedOrStoppingContinuationsDoNotLift(String text) {
        assertThat(judge.continues(text)).isFalse();
    }

    @Test
    void theHortativeQuoteMarkersDoNotSwallowOrdinaryStopsOrWordsWithJae() {
        assertThat(judge.judge("그만하자", false)).isEqualTo(new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN));
        assertThat(judge.judge("재미없어 그만하자", false)).isEqualTo(new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN));
        assertThat(judge.judge("그만하자 재미없어", false)).isEqualTo(new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN));
        assertThat(judge.judge("이제 끝내 재미없어", false).verdict()).isEqualTo(Verdict.CONFIRM);
        assertThat(judge.judge("나는 천재", false).verdict()).isEqualTo(Verdict.NONE);
    }

    @Test
    void anythingConfirmedWhileTheDevicePlaysIsIgnoredAndBlankInputIsNothing() {
        assertThat(judge.judge("무중아 그만할래", true).verdict()).as("기기 재생 음성일 수 있다").isEqualTo(Verdict.NONE);
        assertThat(judge.judge(null, false).verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("   ", false).verdict()).isEqualTo(Verdict.NONE);
        assertThat(judge.judge("?!", false).verdict()).isEqualTo(Verdict.NONE);
    }

    @Test
    void theSnapshotIgnoresBlankDuplicateAndOneLetterExpressions() {
        StopIntentJudge custom = new StopIntentJudge(java.util.Arrays.asList("그만 놀래", "그만 놀래", " ", "끝", null));

        assertThat(custom.judge("나 그만 놀래", false)).isEqualTo(new Judgment(Verdict.STOP, Basis.SNAPSHOT));
        assertThat(custom.judge("끝", false).verdict()).as("한 글자 표현은 스냅숏에서 빠진다(불명확으로 확인)")
                .isEqualTo(Verdict.CONFIRM);
        assertThat(new StopIntentJudge(null).judge("무중아 그만할래", false)).as("스냅숏이 없어도 명확한 표현은 잡는다")
                .isEqualTo(new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN));
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
        assertThatThrownBy(() -> new Judgment(Verdict.CONFIRM, Basis.SNAPSHOT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
