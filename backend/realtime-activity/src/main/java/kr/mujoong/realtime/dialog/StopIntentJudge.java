package kr.mujoong.realtime.dialog;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 아이 종료 의도 판정기 (U3 L4 · components DialogPolicy · U3-R12 · U3-J1 · U3-J24 · AC6.4.4 ~ 6.4.6 · AC6.4.8 · FR5.4).
 *
 * <p>확정된 아이 전사 한 문장을 받아 {@link Verdict}만 돌려준다 — 상태를 바꾸지 않는다(components DialogPolicy). 판정 순서는 오인식(종료가 아닌데
 * 종료로 보기)을 0으로 두는 쪽이다(AC6.4.8):
 *
 * <ol>
 *   <li>기기가 재생 중에 확정된 전사는 기기 재생 음성일 수 있어 {@link Verdict#NONE}(AC6.4.6 '기기 재생 음성').
 *   <li>인용 · 남의 말 · 역할 놀이 표지("~라고 했어" · "~하래" · "~자구 했어" · "흉내")가 있으면 NONE(AC6.4.6 '역할 속 대사 · 상대 발화').
 *   <li>종료를 부정하는 말("그만 안 할래" · "그만하기 싫어" · "그만하고 싶지 않아" · "안 끝났어")이나 종료 표현을 되받아 부정하는 말("그만할래
 *       아니야")은 NONE.
 *   <li>세션 스냅숏의 종료 표현(기본 "무중아 그만할래" + 보호자 추가 표현)이 낱말 경계에 맞게 들어 있으면 {@link Verdict#STOP}(물음꼴이면
 *       CONFIRM).
 *   <li>주제 거부("이거 말고" · "공룡 얘기 그만")는 NONE(AC6.3.5 · AC6.4.6 '주제 거부').
 *   <li>명확한 종료 표현("그만할래" · "끝낼래" · "이제 끝내자" …)이 물음이 아니면 STOP. 같은 문장에 계속 표현이 섞여도 종료가 이긴다("더 하고
 *       싶은데 졸려서 그만할래", 3차 리뷰 후속).
 *   <li>계속 표현에 부정이 붙은 말("더 얘기하기 싫어" · "더 얘기 안 할래")은 종료 쪽이다 — 앞말이 대화 자체를 가리키면 STOP, 주제 낱말이
 *       앞서면 CONFIRM(3차 리뷰 후속).
 *   <li>계속 표현("더 할래" · "계속하자")은 NONE.
 *   <li>불명확한 표현("그만" 단독 · "쉬고 싶어" · "안 할래" · 물음꼴 종료 표현)은 {@link Verdict#CONFIRM} — 단정하지 않고 확인한다(AC6.4.5).
 * </ol>
 *
 * <p>비교는 정규화한 글자열로 한다: 유니코드 NFKC, 영문 소문자, 한글 · 영문 · 숫자 밖의 글자(공백 · 문장 부호)는 지운다. 판정 기준(표현 목록)은 이
 * 판정기에만 있고 대화 역할 지시문에는 들어가지 않는다(U3-R14 · NFR3).
 */
public final class StopIntentJudge {

    /** 판정 결과. */
    public enum Verdict {
        /** 명확한 종료 의도 — 대기 응답을 취소한다(마무리 인사 · CLOSING(CHILD_STOP)은 U4). */
        STOP,
        /** 종료일 수 있으나 불명확 — 단정하지 않고 짧게 확인한다(확인 질문 재생은 U4). */
        CONFIRM,
        /** 종료 의도 아님. */
        NONE
    }

    /** STOP의 근거(세션 이벤트 payload, 원문은 남기지 않는다). */
    public enum Basis {
        /** 세션 스냅숏의 종료 표현. */
        SNAPSHOT,
        /** 판정기의 명확한 종료 표현. */
        CLEAR_PATTERN,
        /** U11 W2: Luna 종료 판정(J6)의 END_CLEAR(세션 사건 payload의 근거 값). */
        LUNA_J6
    }

    /** 판정과 근거(STOP이 아니면 근거 null). */
    public record Judgment(Verdict verdict, Basis basis) {

        static final Judgment NONE = new Judgment(Verdict.NONE, null);
        static final Judgment CONFIRM = new Judgment(Verdict.CONFIRM, null);

        public Judgment {
            Objects.requireNonNull(verdict, "verdict");
            if ((verdict == Verdict.STOP) != (basis != null)) {
                throw new IllegalArgumentException("STOP만 근거가 있다");
            }
        }
    }

    /**
     * 남의 말 · 인용 · 역할 놀이 표지(정규화 뒤, 부분 일치). 입말 '-구'("라구 했어" · "하라구"), '-다고 했어', '-대'("싶대"), "이랬어"도
     * 남의 말이다(3차 리뷰 후속). 청유형 인용은 낱말 경계를 보는 아래 규칙들이 맡는다.
     */
    private static final List<String> REPORTED = List.of(
            "라고했", "라고말했", "라고하면", "라고하더", "라고그랬", "라고해서", "라고하는", "라고하잖", "라고해",
            "라구했", "라구해", "라구말했", "라구그랬", "라구그래", "라구하더",
            "이라고했", "이라고말했", "하래", "하라고", "하라고했", "하라더", "하라구", "그랬어", "그러더라", "그러던데",
            "이랬어", "이랬다", "이러더", "이러던", "다고했", "다고해", "다고말했", "다고그랬", "다고하더", "싶대", "겠대",
            "흉내", "인척", "척할", "척하", "역할놀이", "놀이에서", "연기", "대사");

    /**
     * 한 낱말 안의 청유형 인용(리뷰 반영 3 · 2차 리뷰 1 · 3차 리뷰 1 · 3차 리뷰 후속): 동사 줄기 끝 글자(하 · 내 · 두 · 가 · 놀) + 청유 '자' 바로 뒤에
     * 인용 어미(더라 · 던데 · 대 · 더래 · 며 · 면서 · 는데 · 길래 · 네 · 셨 · 시)가 오거나, 준말 '~재'(= ~자고 해). 낱말 안에서만 보므로 "혼자
     * 대화" · "피자 대신"처럼 낱말 경계를 넘어 걸리지 않는다.
     */
    private static final Pattern HORTATIVE_QUOTE_IN_WORD = Pattern.compile(
            "(?:하|내|두|가|놀)자(?:더라|던데|대|더래|며|면서|는데|길래|네|셨|시)|(?:하|내|두)재");

    /** 청유 '자' 바로 앞에 오는 동사 줄기 끝 글자(낱말 끝 '-자고 · -자구'와 조사 없는 인용을 가를 때 쓴다). */
    private static final String HORTATIVE_STEMS = "하내두가놀";

    /** 청유형 인용 표지 '-자고'와 입말 '-자구'. */
    private static final List<String> HORTATIVE_QUOTE_MARKERS = List.of("자고", "자구");

    /**
     * 동사 줄기에 붙지 않은 "~자고" 뒤에 오는 말하기 동사의 첫머리(2차 리뷰 1 · 3차 리뷰 1 '그래'). "~자고"가 낱말 안에 있으면 바로 뒤가, 낱말
     * 끝이면 다음 낱말의 첫머리가 이것이어야 인용이다.
     */
    private static final List<String> QUOTE_VERB_HEADS = List.of("했", "해", "하", "말", "그랬", "그러", "그래");

    /** 조사 없는 인용("그만하자 해서 왔어" · "그만하자 하더라")에서 '<줄기>자' 다음 낱말의 첫머리(3차 리뷰 후속). */
    private static final List<String> PLAIN_QUOTE_HEADS = List.of(
            "했", "해서", "하더", "하셨", "하시", "하길래", "하면서", "그래서", "그랬", "그러");

    /**
     * 종료를 부정하는 말(정규화 뒤): "그만 안 할래" · "안 그만둘래" · "그만하기 싫어" · "그만하고 싶지 않아" · "끝내지 마" · "그만하면 안 돼" ·
     * "안 끝났어" · "그만할 생각 없어". "오늘은 그만, 안녕"의 '그만안'처럼 '안' 뒤가 동사가 아니면 보지 않고, "왜 안 끝나"는 끝내고 싶은 말이라
     * 보지 않는다(3차 리뷰 후속).
     */
    private static final Pattern NEGATED_STOP = Pattern.compile(
            "그만안(?:하|할|해|둘)|안그만(?:하|할|해|둘)"
                    + "|(?:그만|끝)(?:하|두|내)(?:기(?:는)?싫|고싶(?:지|진|지는)않|지(?:는)?(?:마|말|않)|면안(?:돼|되))"
                    + "|(?<!왜)안끝(?:나|났|날|낼|내)|아직안끝|(?:그만|끝)(?:할|낼|둘)(?:생각|맘|마음)없");

    /** 종료 표현 바로 뒤에 오면 그 표현을 되받아 부정하는 말("그만할래 아니야" · "그만하자는 거 아니야" · "그만할래 말고"). */
    private static final List<String> DENIALS = List.of(
            "아니야", "아니에", "아니고", "아닌", "아냐", "말고", "는아니", "는거아니", "는게아니", "는건아니");

    /**
     * 종료 의도를 푸는 표현(정규화 뒤, 2차 리뷰 6 · 3차 리뷰 2 · 3차 리뷰 후속): 종료를 부정하는 말과 적극적 계속 표현. 판정용 목록과 따로 둔다 —
     * 판정은 종료 쪽을 먼저 보고, 해제는 이 말이 계속 의사일 때만 푼다. 화면 설계 · 스토리에 목록이 없어 가정이다(U3-J26).
     */
    private static final List<String> RELEASE = List.of(
            "그만안하", "그만안할", "그만안해", "그만안둘", "안그만하", "안그만할", "안그만둘", "그만하기싫", "그만하지말",
            "그만하지마", "그만하지않", "그만하고싶지않", "끝내기싫", "끝내지말", "끝내지마", "끝내고싶지않", "아직안끝",
            "안끝났", "안끝낼", "안끝내",
            "더할래", "더하자", "더하고싶", "더해", "더할거", "더얘기", "더이야기", "더놀", "계속할래", "계속하자",
            "계속하고싶", "계속해", "계속얘기", "계속이야기", "계속놀", "또하자", "또할래", "또해", "다시하자", "다시할래",
            "다시해");

    /** 다음으로 미루는 말(이것이 있으면 "담에 또 하자"처럼 지금 계속하자는 뜻이 아니다). "그다음에"의 '다음에'는 아니다. */
    private static final List<String> DEFERRALS = List.of(
            "다음에", "다음번", "담에", "나중에", "내일", "이따", "있다가", "자고일어나");

    /** 계속 표현(정규화 뒤). 종료 표현이 없을 때만 NONE의 근거가 된다. */
    private static final List<String> CONTINUE = List.of(
            "더할래", "더하자", "더하고싶", "더해", "더할거", "더얘기", "더이야기", "더놀", "계속할래", "계속하자",
            "계속하고싶", "계속해", "계속얘기", "계속이야기", "계속놀", "또하자", "또할래", "또해", "다시하자",
            "다시할래", "다시해");

    /** '더 · 계속 · 또 · 다시'에 부정이 바로 붙은 말("더 안 할래" · "더는 싫어" · "더 이상 못 하겠어" · "계속 안 할래"). */
    private static final Pattern NEGATED_MORE = Pattern.compile(
            "(?:더는|더이상|더|계속|또|다시)(?:안(?:할|해|하)|싫|못(?:할|해|하))");

    /** 뒤에 부정이 따라붙는지 보는 계속 표현의 줄기("더 얘기하기 싫어"의 '더얘기'). */
    private static final List<String> CONTINUATION_STEMS = List.of(
            "더얘기", "더이야기", "더놀", "더하", "더할", "더해", "계속얘기", "계속이야기", "계속놀", "계속하", "계속할",
            "계속해", "또하", "또할", "또해", "다시하", "다시할", "다시해");

    /** 계속 표현 뒤 같은 절에 오면 부정이 되는 말("재미없어"의 '없'처럼 홀로 쓰인 '없'은 아니다). */
    private static final List<String> NEGATIONS = List.of(
            "안할", "안해", "안하", "싫", "않", "지마", "지말", "못하", "못할", "못해", "거없", "것없", "수없", "생각없",
            "맘없", "마음없");

    /** 계속 표현의 절을 끝내는 글자(청유 · 의지 · 부탁 · 물음): "더 하자, 엄마는 싫대"의 '싫'은 다른 절이다. */
    private static final String CLAUSE_ENDS = "자래줘까";

    /** 부정을 다시 뒤집는 꼴("더 얘기 안 하면 안 돼" · "싫은 건 아니야") — 계속하겠다는 뜻이다. */
    private static final List<String> DOUBLE_NEGATIONS = List.of("면안", "건아니", "게아니", "거아니");

    /** 부정을 찾는 절의 최대 길이(글자). */
    private static final int CLAUSE_WINDOW = 10;

    /** 주제 거부(정규화 뒤). "얘기그만"처럼 주제 뒤에 그만이 오는 꼴은 아래에서 따로 본다. */
    private static final List<String> TOPIC_REFUSAL = List.of(
            "이거말고", "그거말고", "저거말고", "얘기말고", "이야기말고", "말고다른", "다른거", "다른얘기", "다른이야기",
            "다른주제", "딴거", "딴얘기", "주제바꾸", "주제바꿔", "그얘기는", "그이야기는", "그건싫", "그거싫", "이거싫");

    /**
     * 주제 낱말 뒤의 그만(예: "공룡 얘기 그만할래" · "공룡 그만 얘기할래") — 앞에 주제 낱말이 있을 때만 주제 거부로 본다.
     */
    private static final List<String> TOPIC_STOP = List.of(
            "얘기그만", "이야기그만", "놀이그만", "질문그만", "거그만", "그만얘기", "그만이야기");

    /** 주제 낱말이 아닌 앞말(이것만 앞에 있으면 대화 자체에 대한 말이다). */
    private static final List<String> NON_TOPIC_HEADS = List.of("", "나", "나는", "나이제", "이제", "이제나", "우리",
            "우리이제", "이제우리", "무중아", "무중아나", "무중아이제", "무중아나이제", "그럼", "그럼이제", "오늘은", "오늘",
            "이제오늘은", "아", "음", "어", "아니", "아니나", "아니이제", "진짜", "정말", "이제진짜", "나진짜", "나이제진짜");

    /** 명확한 종료 표현 — 어미가 분명한 동사꼴이라 들어 있기만 하면 된다(정규화 뒤, 물음꼴이 아닐 때 STOP). */
    private static final List<String> CLEAR_STOP = List.of(
            "그만할래", "그만할게", "그만하고싶", "그만하자", "그만할거야", "그만할거예", "그만할거에", "그만할라고", "그만둘래",
            "그만둘게", "그만하겠", "그만얘기할래", "그만이야기할래", "그만말할래", "그만얘기하자", "그만이야기하자", "그만말하자",
            "끝낼래", "끝낼게", "끝내자", "끝내고싶", "끝낼거야", "끝내겠", "여기까지할래", "여기까지하자", "이제안할래",
            "그만나갈래", "이제갈래", "이제갈게", "이제잘래", "이제잘게");

    /** 명확한 종료 표현 — 명사로 끝나 낱말 경계에 맞을 때만 본다("이제 끝말잇기"는 아니다). */
    private static final List<String> CLEAR_STOP_PHRASES = List.of(
            "이제끝", "대화끝", "얘기끝", "이야기끝", "오늘은여기까지", "오늘은여기서끝", "대화그만", "이제그만대화");

    /** 앞말 뒤 문장 전체가 이것일 때만 보는 불명확한 신호("그만" · "무중아 그만해" · "이제 끝내"). */
    private static final List<String> WHOLE_SENTENCE_SIGNALS = List.of("그만", "끝", "그만해", "끝내");

    /** 불명확한 종료 신호(정규화 뒤, 들어 있으면 확인한다). 바로 앞에 '안'이 붙으면("안 졸려") 신호가 아니다. */
    private static final List<String> UNCLEAR = List.of(
            "쉬고싶", "쉴래", "쉬자", "쉬어도돼", "힘들어", "피곤해", "졸려", "지겨워", "재미없", "하기싫",
            "안할래", "안해", "됐어", "이제됐", "바이바이", "잘있어", "다음에하자", "나중에하자", "다음에또", "나중에또",
            "가야돼", "가야해", "끝낼까", "그만할까", "끝내도돼", "그만해도돼", "그만해도될까", "끝내도될까",
            "그만놀래", "그만놀자", "그만안녕", "그만바이", "이제안녕", "오늘은그만");

    /** 스냅숏 표현 끝에 붙어도 같은 말로 보는 어미(문장 끝). */
    private static final List<String> FINAL_SUFFIXES = List.of("", "요", "야", "이야", "이에요", "예요", "다", "이다");

    /** 한글 음절의 받침 ㄹ 번호('-ㄹ까' 물음을 가릴 때 쓴다). */
    private static final int FINAL_RIEUL = 8;

    private final List<String> snapshot;

    /**
     * @param stopExpressions 세션 스냅숏의 종료 표현(기본 + 추가, 순서 무관). 정규화 뒤 두 글자 미만인 값은 버린다(오인식 방지)
     */
    public StopIntentJudge(List<String> stopExpressions) {
        List<String> normalized = new ArrayList<>();
        for (String expression : stopExpressions == null ? List.<String>of() : stopExpressions) {
            String value = normalize(expression);
            if (value.length() >= 2 && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        this.snapshot = List.copyOf(normalized);
    }

    /**
     * 확정된 아이 전사 하나를 판정한다.
     *
     * @param transcript 확정 전사(null · 빈 값이면 NONE)
     * @param devicePlaying 전사가 확정될 때 기기가 음성을 재생하고 있었는가
     */
    public Judgment judge(String transcript, boolean devicePlaying) {
        if (transcript == null || devicePlaying) {
            return Judgment.NONE;
        }
        String text = normalize(transcript);
        List<String> words = tokens(transcript);
        if (text.isEmpty() || reported(text, words) || NEGATED_STOP.matcher(text).find() || denied(text)) {
            return Judgment.NONE;
        }
        boolean question = isQuestion(transcript, text);
        if (matchesPhrase(words, snapshot)) {
            // 물음꼴("이제 끝이야?")은 단정하지 않고 확인한다(AC6.4.5)
            return question ? Judgment.CONFIRM : new Judgment(Verdict.STOP, Basis.SNAPSHOT);
        }
        if (containsAny(text, TOPIC_REFUSAL) || topicStop(text)) {
            return Judgment.NONE;
        }
        if (containsAny(text, CLEAR_STOP) || matchesPhrase(words, CLEAR_STOP_PHRASES)) {
            return question ? Judgment.CONFIRM : new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN);
        }
        int negated = negatedContinuation(text);
        if (negated >= 0) {
            // "공룡 얘기 더 하기 싫어"처럼 주제 낱말이 앞서면 그 주제를 싫다는 말일 수 있어 확인만 한다
            boolean aboutTheTalk = NON_TOPIC_HEADS.contains(text.substring(0, negated));
            return question || !aboutTheTalk ? Judgment.CONFIRM : new Judgment(Verdict.STOP, Basis.CLEAR_PATTERN);
        }
        if (continuing(text)) {
            return Judgment.NONE;
        }
        return unclear(text) ? Judgment.CONFIRM : Judgment.NONE;
    }

    /** 계속 표현이 들어 있는가(해야 할 일 · 이어 주는 말로 쓰인 것은 빼고). */
    private static boolean continuing(String text) {
        for (String phrase : CONTINUE) {
            for (int at = text.indexOf(phrase); at >= 0; at = text.indexOf(phrase, at + 1)) {
                if (!choreOrConnective(text.substring(at + phrase.length()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 표현 뒤가 해야 할 일("숙제 더 해야 돼")이나 이어 주는 말("계속해서 불러")인가 — 계속하자는 뜻이 아니다. */
    private static boolean choreOrConnective(String rest) {
        return rest.startsWith("야돼") || rest.startsWith("야되") || rest.startsWith("야해") || rest.startsWith("서");
    }

    /**
     * 적극적으로 대화를 이어 가겠다는 말인가("더 얘기하자" · "계속해" · "더 해 줘" · "그만 안 할래" · "안 끝났어"). 종료 의도를 푸는 근거는
     * 이것뿐이다(2차 리뷰 6). 남의 말이거나, 다음으로 미루는 말이 있거나, 그 말 자체를 판정하면 종료 · 불명확이면 계속 의사가 아니다(3차 리뷰 2).
     * 해제 표현도 같은 절에 부정이 따라붙거나("더 얘기하기 싫어"), 해야 할 일("숙제 더 해야 돼") · 이어 주는 말("계속해서 불러")이면 아니다
     * (3차 리뷰 후속).
     */
    public boolean continues(String transcript) {
        String text = normalize(transcript);
        if (text.isEmpty() || reported(text, tokens(transcript)) || deferred(text)
                || judge(transcript, false).verdict() != Verdict.NONE) {
            return false;
        }
        for (String phrase : RELEASE) {
            for (int at = text.indexOf(phrase); at >= 0; at = text.indexOf(phrase, at + 1)) {
                if (releases(text, at, phrase)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean releases(String text, int at, String phrase) {
        String rest = text.substring(at + phrase.length());
        if (choreOrConnective(rest)) {
            return false;
        }
        if (phrase.startsWith("안끝") && at > 0 && text.charAt(at - 1) == '왜') {
            return false;
        }
        return !negatedAhead(rest);
    }

    /**
     * 표현이 낱말 경계에 맞게 들어 있는가: 이어진 낱말들을 붙인 값이 표현과 같거나 표현 + 문장 끝 어미다(띄어쓰기가 달라도 되고, "이제 끝난
     * 거"처럼 낱말 안에서 걸리는 것은 아니다).
     */
    private static boolean matchesPhrase(List<String> words, List<String> phrases) {
        for (String expression : phrases) {
            for (int start = 0; start < words.size(); start++) {
                StringBuilder joined = new StringBuilder();
                for (int end = start; end < words.size(); end++) {
                    joined.append(words.get(end));
                    if (joined.length() > expression.length() + 3) {
                        break;
                    }
                    if (joined.length() >= expression.length() && joined.indexOf(expression) == 0
                            && FINAL_SUFFIXES.contains(joined.substring(expression.length()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 공백으로 나눈 낱말마다 정규화한 값(빈 낱말은 뺀다). 참여 판단({@link JoinTracker})도 같은 낱말 단위를 쓴다. */
    static List<String> tokens(String transcript) {
        List<String> words = new ArrayList<>();
        for (String word : transcript.strip().split("\\s+")) {
            String normalized = normalize(word);
            if (!normalized.isEmpty()) {
                words.add(normalized);
            }
        }
        return words;
    }

    /** 남의 말 · 인용 · 역할 놀이(부분 일치 표지, 낱말 안 청유형 인용, "~자고 · ~자구" 인용, 조사 없는 인용). */
    private static boolean reported(String text, List<String> words) {
        if (containsAny(text, REPORTED) || hortativeQuote(words)) {
            return true;
        }
        for (String word : words) {
            if (HORTATIVE_QUOTE_IN_WORD.matcher(word).find()) {
                return true;
            }
        }
        return false;
    }

    /** 종료 표현(스냅숏 · 명확한 표현) 바로 뒤에 그 표현을 되받아 부정하는 말이 오는가("그만할래 아니야" · "그만하자는 거 아니야"). */
    private boolean denied(String text) {
        for (List<String> expressions : List.of(snapshot, CLEAR_STOP)) {
            for (String expression : expressions) {
                for (int at = text.indexOf(expression); at >= 0; at = text.indexOf(expression, at + 1)) {
                    String rest = text.substring(at + expression.length());
                    if (startsWithAny(rest.startsWith("요") ? rest.substring(1) : rest, DENIALS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 계속 표현에 부정이 붙은 자리(없으면 -1): "더 안 할래" · "더 이상 못 하겠어"처럼 바로 붙은 부정, 또는 "더 얘기하기 싫어" · "더 하고 싶지
     * 않아"처럼 같은 절에 따라붙은 부정. 이중 부정("더 얘기 안 하면 안 돼")은 아니다.
     */
    private static int negatedContinuation(String text) {
        Matcher more = NEGATED_MORE.matcher(text);
        while (more.find()) {
            String rest = text.substring(more.end());
            if (!containsAny(rest.substring(0, Math.min(rest.length(), 5)), DOUBLE_NEGATIONS)) {
                return more.start();
            }
        }
        for (String stem : CONTINUATION_STEMS) {
            for (int at = text.indexOf(stem); at >= 0; at = text.indexOf(stem, at + 1)) {
                if (negatedAhead(text.substring(at + stem.length()))) {
                    return at;
                }
            }
        }
        return -1;
    }

    /** 계속 표현 뒤 같은 절(최대 {@value #CLAUSE_WINDOW}글자, 절을 끝내는 글자 앞까지)에 부정이 있고, 이중 부정이 아닌가. */
    private static boolean negatedAhead(String rest) {
        if (rest.startsWith("서")) {
            // "계속해서 싫어" · "더해서"의 '-서'는 이어 주는 말이라 뒤는 다른 절이다
            return false;
        }
        int end = Math.min(rest.length(), CLAUSE_WINDOW);
        for (int i = 0; i < end; i++) {
            if (CLAUSE_ENDS.indexOf(rest.charAt(i)) >= 0) {
                end = i;
                break;
            }
        }
        String clause = rest.substring(0, end);
        String tail = rest.substring(0, Math.min(rest.length(), end + 4));
        return containsAny(clause, NEGATIONS) && !containsAny(tail, DOUBLE_NEGATIONS);
    }

    /** 다음으로 미루는 말이 있는가("그다음에"의 '다음에'는 아니다). */
    private static boolean deferred(String text) {
        for (String word : DEFERRALS) {
            for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + 1)) {
                if (!(word.equals("다음에") && at > 0 && text.charAt(at - 1) == '그')) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 정규화: NFKC · 영문 소문자 · 한글 · 영문 · 숫자만 남긴다. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder kept = new StringBuilder(folded.length());
        folded.codePoints().filter(StopIntentJudge::kept).forEach(kept::appendCodePoint);
        return kept.toString();
    }

    private static boolean kept(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
                && (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL
                        || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
                        || Character.isDigit(codePoint));
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * "~자고 · ~자구" 인용과 조사 없는 인용. 동사 줄기에 붙은 '-자고'는 뒤에 말이 더 있으면 인용이고("그만하자고 화냈어"), 문장 끝이면 아이
     * 자신의 강조다("이제 그만하자고!"). 줄기에 붙지 않은 "자고"(자다)는 말하기 동사가 따라올 때만 본다. '<줄기>자' 다음 낱말이 "해서 ·
     * 하더 · 했"으로 시작하면 조사 없는 인용이다("그만하자 해서 왔어").
     */
    private static boolean hortativeQuote(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            String next = i + 1 < words.size() ? words.get(i + 1) : "";
            for (String marker : HORTATIVE_QUOTE_MARKERS) {
                for (int at = word.indexOf(marker); at >= 0; at = word.indexOf(marker, at + 1)) {
                    String rest = word.substring(at + marker.length());
                    boolean afterStem = at > 0 && HORTATIVE_STEMS.indexOf(word.charAt(at - 1)) >= 0;
                    boolean sentenceEnd = (rest.isEmpty() || rest.equals("요")) && next.isEmpty();
                    String following = rest.isEmpty() ? next : rest;
                    if (afterStem ? !sentenceEnd : startsWithAny(following, QUOTE_VERB_HEADS)) {
                        return true;
                    }
                }
            }
            if (word.length() >= 2 && word.endsWith("자")
                    && HORTATIVE_STEMS.indexOf(word.charAt(word.length() - 2)) >= 0
                    && startsWithAny(next, PLAIN_QUOTE_HEADS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String text, List<String> heads) {
        for (String head : heads) {
            if (text.startsWith(head)) {
                return true;
            }
        }
        return false;
    }

    /** "공룡 얘기 그만할래"처럼 주제 낱말 뒤의 그만. 앞말이 '나 · 이제 · 우리'뿐이면 대화를 그만두는 말이라 주제 거부가 아니다. */
    private static boolean topicStop(String text) {
        for (String marker : TOPIC_STOP) {
            int at = text.indexOf(marker);
            if (at < 0) {
                continue;
            }
            String head = text.substring(0, at);
            if (marker.equals("거그만")) {
                // "이거 그만" · "그거 그만"처럼 지시어 + 거만 주제다("그만할거야"의 '거그만'은 앞에서 걸리지 않는다)
                if (head.endsWith("이") || head.endsWith("그") || head.endsWith("저")) {
                    return true;
                }
                continue;
            }
            if (!NON_TOPIC_HEADS.contains(head)) {
                return true;
            }
        }
        return false;
    }

    /** 앞말 뒤 문장 전체가 "그만 · 끝 · 그만해 · 끝내"인 경우와, 바로 앞에 '안'이 붙지 않은 불명확한 신호. */
    private static boolean unclear(String text) {
        for (String head : NON_TOPIC_HEADS) {
            if (!text.startsWith(head)) {
                continue;
            }
            String core = text.substring(head.length());
            for (String signal : WHOLE_SENTENCE_SIGNALS) {
                if (core.equals(signal) || core.equals(signal + signal) || core.equals(signal + "이야")
                        || core.equals(signal + "요")) {
                    return true;
                }
            }
        }
        for (String signal : UNCLEAR) {
            for (int at = text.indexOf(signal); at >= 0; at = text.indexOf(signal, at + 1)) {
                if (signal.startsWith("안") || at == 0 || text.charAt(at - 1) != '안') {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 물음꼴: 물음표로 끝나거나, 고르는 물음("~할래 아니면 ~할래")이거나, 의문 어미("니" · "냐" · "나요" · '-ㄹ까(요)')로 끝난다. 이유 ·
     * 다그침의 '-니까'("졸리니까" · "그만하자니까")는 물음이 아니다(3차 리뷰 후속).
     */
    private static boolean isQuestion(String original, String normalized) {
        String trimmed = original.strip();
        if (trimmed.endsWith("?") || trimmed.endsWith("？") || normalized.contains("아니면")) {
            return true;
        }
        return normalized.endsWith("니") || normalized.endsWith("냐") || normalized.endsWith("나요")
                || endsWithRieulKka(normalized);
    }

    private static boolean endsWithRieulKka(String normalized) {
        String body;
        if (normalized.endsWith("까요")) {
            body = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("까")) {
            body = normalized.substring(0, normalized.length() - 1);
        } else {
            return false;
        }
        if (body.isEmpty()) {
            return false;
        }
        char last = body.charAt(body.length() - 1);
        return last >= '가' && last <= '힣' && (last - '가') % 28 == FINAL_RIEUL;
    }
}
