package kr.mujoong.realtime.dialog;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 아이 종료 의도 판정기 (U3 L4 · components DialogPolicy · U3-R12 · U3-J1 · U3-J24 · AC6.4.4 ~ 6.4.6 · AC6.4.8 · FR5.4).
 *
 * <p>확정된 아이 전사 한 문장을 받아 {@link Verdict}만 돌려준다 — 상태를 바꾸지 않는다(components DialogPolicy). 판정 순서는 오인식(종료가 아닌데
 * 종료로 보기)을 0으로 두는 쪽이다(AC6.4.8):
 *
 * <ol>
 *   <li>기기가 재생 중에 확정된 전사는 기기 재생 음성일 수 있어 {@link Verdict#NONE}(AC6.4.6 '기기 재생 음성').
 *   <li>인용 · 남의 말 · 역할 놀이 표지("~라고 했어" · "~하래" · "흉내")가 있으면 NONE(AC6.4.6 '역할 속 대사 · 상대 발화').
 *   <li>계속하겠다는 부정("그만 안 할래" · "그만하기 싫어" · "더 할래")은 NONE.
 *   <li>세션 스냅숏의 종료 표현(기본 "무중아 그만할래" + 보호자 추가 표현)이 낱말 경계에 맞게 들어 있으면 {@link Verdict#STOP}(물음꼴이면
 *       CONFIRM).
 *   <li>주제 거부("이거 말고" · "공룡 얘기 그만")는 NONE(AC6.3.5 · AC6.4.6 '주제 거부').
 *   <li>명확한 종료 표현("그만할래" · "끝낼래" · "이제 끝내자" …)이 물음이 아니면 STOP.
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
        CLEAR_PATTERN
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

    /** 남의 말 · 인용 · 역할 놀이 표지(정규화 뒤, 부분 일치). 청유형 인용은 낱말 경계를 보는 아래 두 목록이 맡는다. */
    private static final List<String> REPORTED = List.of(
            "라고했", "라고말했", "라고하면", "라고하더", "라고그랬", "라고해서", "라고하는", "라고하잖", "라고해",
            "이라고했", "이라고말했", "하래", "하라고", "하라고했", "하라더", "그랬어", "그러더라", "그러던데",
            "흉내", "인척", "척할", "척하", "역할놀이", "놀이에서", "연기", "대사");

    /**
     * 한 낱말 안의 청유형 인용(리뷰 반영 3 · 2차 리뷰 1 · 3차 리뷰 1): 동사 줄기 끝 글자(하 · 내 · 두 · 가 · 놀) + 청유 '자' 바로 뒤에 인용 어미
     * (더라 · 던데 · 대 · 더래)가 오거나, 준말 '~재'(= ~자고 해). 낱말 안에서만 보므로 "혼자 대화" · "피자 대신"처럼 낱말 경계를 넘어 걸리지 않고,
     * 뒤에 요 · 고요 · 서 · 잖아 · 구가 더 붙어도("그만하자더라고요" · "그만하자대서") 걸린다.
     */
    private static final Pattern HORTATIVE_QUOTE_IN_WORD =
            Pattern.compile("(?:하|내|두|가|놀)자(?:더라|던데|대|더래)|(?:하|내|두)재");

    /**
     * 청유형 인용 "~자고" 뒤에 오는 말하기 동사의 첫머리(2차 리뷰 1 · 3차 리뷰 1 '그래'). "~자고"가 낱말 안에 있으면 바로 뒤가, 낱말 끝이면 다음
     * 낱말의 첫머리가 이것이어야 인용이다("그만하자고 했어" · "그만하자고해서" · "그만하자고 그래"). "자고" 단독은 아이 자신의 강조("이제
     * 그만하자고!")일 수 있어 보지 않는다.
     */
    private static final List<String> QUOTE_VERB_HEADS = List.of("했", "해", "하", "말", "그랬", "그러", "그래");

    /**
     * 종료 의도를 푸는 적극적 계속 표현(정규화 뒤, 2차 리뷰 6 · 3차 리뷰 2). 판정용 {@link #CONTINUE}와 따로 둔다 — 판정은 이 말들이 섞여도
     * 종료로 보지 않는 쪽(오인식 0)이고, 해제는 이 말이 계속 의사일 때만 푸는 쪽이라 거르는 조건이 다르다. 화면 설계 · 스토리에 목록이 없어
     * 가정이다(U3-J26).
     */
    private static final List<String> RELEASE = List.of(
            "그만안", "안그만", "그만하기싫", "그만하지말", "그만하지마", "끝내기싫", "끝내지말", "끝내지마", "아직안끝", "아직그만",
            "더할래", "더하자", "더하고싶", "더해", "더얘기", "더이야기", "더놀", "계속할래", "계속하자", "계속하고싶", "계속해",
            "계속얘기", "계속이야기", "또하자", "또할래", "다시하자", "다시할래");

    /** 계속 표현 바로 뒤에 오면 부정이 되는 말(3차 리뷰 2 (a): "더 얘기 안 할래" · "더 하고 싶지 않아" · "더 얘기 말고"). */
    private static final List<String> RELEASE_NEGATIONS = List.of("안", "않", "싫", "말", "지않", "지마", "지말", "기싫");

    /** 다음으로 미루는 말(이것이 있으면 "다음에 또 하자"처럼 지금 계속하자는 뜻이 아니다). */
    private static final List<String> DEFERRALS = List.of("다음에", "나중에", "내일");

    /** 계속하겠다는 부정 · 연장(정규화 뒤). */
    private static final List<String> CONTINUE = List.of(
            "그만안", "안그만", "그만하기싫", "그만하지말", "그만하지마", "끝내기싫", "끝내지말", "끝내지마", "안끝",
            "더할래", "더하자", "더하고싶", "더얘기", "더이야기", "계속할래", "계속하자", "계속하고싶", "계속얘기",
            "계속이야기", "아직안끝", "아직그만");

    /** 주제 거부(정규화 뒤). "얘기그만"처럼 주제 뒤에 그만이 오는 꼴은 아래에서 따로 본다. */
    private static final List<String> TOPIC_REFUSAL = List.of(
            "이거말고", "그거말고", "저거말고", "얘기말고", "이야기말고", "말고다른", "다른거", "다른얘기", "다른이야기",
            "다른주제", "딴거", "딴얘기", "주제바꾸", "주제바꿔", "그얘기는", "그이야기는", "그건싫", "그거싫", "이거싫");

    /** 주제 뒤에 오는 그만(예: "공룡 얘기 그만할래") — 앞에 주제 낱말이 있을 때만 주제 거부로 본다. */
    private static final List<String> TOPIC_STOP = List.of("얘기그만", "이야기그만", "놀이그만", "질문그만", "거그만");

    /** 주제 낱말이 아닌 앞말(이것만 앞에 있으면 대화 자체를 그만두는 말이다). */
    private static final List<String> NON_TOPIC_HEADS = List.of("", "나", "나는", "나이제", "이제", "이제나", "우리",
            "우리이제", "이제우리", "무중아", "무중아나", "무중아이제", "무중아나이제", "그럼", "그럼이제", "오늘은", "오늘",
            "이제오늘은");

    /** 명확한 종료 표현 — 어미가 분명한 동사꼴이라 들어 있기만 하면 된다(정규화 뒤, 물음꼴이 아닐 때 STOP). */
    private static final List<String> CLEAR_STOP = List.of(
            "그만할래", "그만할게", "그만하고싶", "그만하자", "그만할거야", "그만둘래", "그만둘게", "그만하겠",
            "그만얘기할래", "그만이야기할래", "그만말할래", "끝낼래", "끝낼게", "끝내자", "끝내고싶", "끝낼거야", "끝내겠",
            "여기까지할래", "여기까지하자", "이제안할래", "그만나갈래", "이제갈래", "이제잘래");

    /** 명확한 종료 표현 — 명사로 끝나 낱말 경계에 맞을 때만 본다("이제 끝말잇기"는 아니다). */
    private static final List<String> CLEAR_STOP_PHRASES = List.of(
            "이제끝", "대화끝", "얘기끝", "이야기끝", "오늘은여기까지", "오늘은여기서끝", "대화그만", "이제그만대화");

    /** 불명확한 종료 신호(정규화 뒤) — 확인한다. */
    private static final List<String> UNCLEAR = List.of(
            "그만", "끝", "쉬고싶", "쉴래", "쉬자", "쉬어도돼", "힘들어", "피곤해", "졸려", "지겨워", "재미없", "하기싫",
            "안할래", "안해", "됐어", "이제됐", "바이바이", "잘있어", "다음에하자", "나중에하자", "다음에또", "나중에또",
            "가야돼", "가야해", "끝낼까", "그만할까", "끝내도돼", "그만해도돼", "그만해도될까", "끝내도될까");

    /** 스냅숏 표현 끝에 붙어도 같은 말로 보는 어미(문장 끝). */
    private static final List<String> FINAL_SUFFIXES = List.of("", "요", "야", "이야", "이에요", "예요", "다", "이다");

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
        if (text.isEmpty() || reported(text, words) || containsAny(text, CONTINUE)) {
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
        if (unclear(text)) {
            return Judgment.CONFIRM;
        }
        return Judgment.NONE;
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

    /** 공백으로 나눈 낱말마다 정규화한 값(빈 낱말은 뺀다). */
    private static List<String> tokens(String transcript) {
        List<String> words = new ArrayList<>();
        for (String word : transcript.strip().split("\\s+")) {
            String normalized = normalize(word);
            if (!normalized.isEmpty()) {
                words.add(normalized);
            }
        }
        return words;
    }

    /**
     * 적극적으로 대화를 이어 가겠다는 말인가("더 얘기하자" · "계속해" · "더 해 줘" · "그만 안 할래"). 종료 의도를 푸는 근거는 이것뿐이다(2차
     * 리뷰 6). 3차 리뷰 2: 남의 말(인용 표지 · 청유형 인용)이거나, 계속 표현 바로 뒤가 부정(안 · 않 · 싫 · 말)이거나, 다음으로 미루는 말이
     * 있거나, 그 말 자체를 판정하면 종료 · 불명확이면 계속 의사가 아니다.
     */
    public boolean continues(String transcript) {
        String text = normalize(transcript);
        if (text.isEmpty() || reported(text, tokens(transcript)) || containsAny(text, DEFERRALS)
                || judge(transcript, false).verdict() != Verdict.NONE) {
            return false;
        }
        for (String phrase : RELEASE) {
            for (int at = text.indexOf(phrase); at >= 0; at = text.indexOf(phrase, at + 1)) {
                if (!startsWithAny(text.substring(at + phrase.length()), RELEASE_NEGATIONS)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 남의 말 · 인용 · 역할 놀이(부분 일치 표지, 낱말 안 청유형 인용, "~자고" + 말하기 동사). */
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

    /** "~자고" + 말하기 동사(같은 낱말 안 또는 다음 낱말 첫머리). */
    private static boolean hortativeQuote(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            for (int at = word.indexOf("자고"); at >= 0; at = word.indexOf("자고", at + 1)) {
                String rest = word.substring(at + 2);
                String following = rest.isEmpty() ? (i + 1 < words.size() ? words.get(i + 1) : "") : rest;
                if (startsWithAny(following, QUOTE_VERB_HEADS)) {
                    return true;
                }
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

    /** 단독 "그만" · "끝"은 문장 전체가 그것일 때만, 나머지는 포함으로 본다. */
    private static boolean unclear(String text) {
        for (String signal : UNCLEAR) {
            if (signal.equals("그만") || signal.equals("끝")) {
                if (text.equals(signal) || text.equals(signal + signal) || text.equals("이제" + signal)
                        || text.equals(signal + "이야") || text.equals(signal + "요")) {
                    return true;
                }
                continue;
            }
            if (text.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    /** 물음꼴: 물음표로 끝나거나 의문 어미("까" · "니" · "냐" · "나요")로 끝난다. */
    private static boolean isQuestion(String original, String normalized) {
        String trimmed = original.strip();
        if (trimmed.endsWith("?") || trimmed.endsWith("？")) {
            return true;
        }
        return normalized.endsWith("까") || normalized.endsWith("니") || normalized.endsWith("냐")
                || normalized.endsWith("나요") || normalized.endsWith("까요");
    }
}
