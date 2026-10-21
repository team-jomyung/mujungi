package kr.mujoong.realtime.dialog;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 참여 판단 추적기 (U3 L4 · components DialogPolicy · U3-R13 · B17 · AC6.3.3 ~ 6.3.8 · AC3.5.1).
 *
 * <p>U4b(L3 · BR2.2 ~ BR2.4 · U4b-J4 · U4b-J5): 커밋 5354a32에서 되살렸고 두 창 시간(처음 · 안내 뒤), 창 끝 조회, 기술 일시정지
 * 이동을 더했다. 안내를 재생하는 중(PROMPTING)에 온 대답도 {@link #reply}가 곧바로 분류해 처리한다(창 시작을 기다리지 않는다, R-11).
 *
 * <p>참여 질문을 재생한 뒤 아이 응답 · 무응답을 받아 다음 할 일({@link Action})만 돌려준다 — 세션 상태 · 시계 · 승인 문구 재생은 부르는 쪽(U4
 * 세션 생명주기)이 맡는다(U3-J1). 시각은 인자로 받고 대기 시간도 생성자 인자다(키 소유 U4).
 *
 * <ul>
 *   <li>무응답 창: 질문 · 안내의 재생 완료를 서버가 받은 시각부터 {@code noReply}(20초). 창 안의 응답은 판단하고, 창이 차면(같은 시각 포함)
 *       재안내 1회, 재안내 뒤에도 차면 DECLINED(AC6.3.3: 19초 응답 → 시작, 21초 → 재안내 / 2차 19초 → 시작, 21초 → DECLINED).
 *   <li>명시적 거부 · 주제 거부: 대안 1회, 대안 뒤 다시 거부하면 DECLINED(AC6.3.4 · 6.3.5 · 6.3.8). 주제 거부는 종료가 아니라 다른 선택지를
 *       제안하는 것이다.
 *   <li>불명확 발화: 무응답이 아니며 참여 질문을 되묻는다 — 재안내 1회에 포함한다(AC6.3.8). 재안내를 이미 썼으면 안내 없이 창을 이어 간다.
 *   <li>안내는 처음 질문을 포함해 최대 3번(처음 · 재안내 · 대안, B17 · AC6.3.7).
 * </ul>
 *
 * <p>대답 분류({@link #classify})는 거부를 먼저 보고, 짧은 긍정은 낱말 단위로 본다: 문장 전체가 짧은 긍정이거나 첫 낱말이 짧은 긍정이면 뒤에 다른 말이
 * 붙어도("응, 들려" · "네네 좋아요") 참여다. 첫 낱말에 물음표가 있으면("응?" · "네? 뭐라고요?") 되묻는 말이라 짧은 긍정 규칙을 쓰지 않는다
 * (docs/join-gate-stabilization.md (1)). 종료 확인 질문의 대답은 첫 낱말 규칙 없이 {@link #classifyWhole}로 본다.
 */
public final class JoinTracker {

    /** 아이 응답 분류. */
    public enum Reply {
        /** 참여한다. */
        ACCEPT,
        /** 명시적으로 거부한다. */
        REFUSE,
        /** 특정 주제만 거부한다(종료 아님). */
        TOPIC_REFUSAL,
        /** 전사는 있으나 의미가 불명확하다. */
        UNCLEAR
    }

    /** 부르는 쪽이 할 일. */
    public enum Action {
        /** 아무것도 하지 않는다(기다린다). */
        NONE,
        /** 참여 확인 → ACTIVE(AC6.3.6). */
        START,
        /** 무응답 재안내(승인 문구). */
        REPROMPT,
        /** 불명확 발화에 참여 질문 되묻기(재안내 1회로 센다). */
        CLARIFY,
        /** 다른 선택지 제안(승인 문구, 1회). */
        OFFER_ALTERNATIVE,
        /** 시작하지 않고 끝낸다(DECLINED, 원문 기록 없음). */
        DECLINE
    }

    /** 단계. */
    public enum Phase {
        /** 안내를 재생하는 중(창이 아직 없다). */
        PROMPTING,
        /** 재생이 끝나 응답을 기다린다(창이 열려 있다). */
        WAITING,
        /** 참여했다. */
        JOINED,
        /** 거절로 끝났다. */
        DECLINED
    }

    /** 안내 최대 횟수(처음 · 재안내 · 대안, B17). */
    public static final int MAX_PROMPTS = 3;
    /**
     * 에코로 의심하는 최소 글자 수(정규화 뒤, 시간 값 아님). 참여 대답은 짧고("응" 1자 · "어 할래" 3자 · "나도 이야기할래" 7자) 안내 글은 길어서, 이보다 짧은
     * 전사는 안내 글의 일부여도 아이 대답으로 본다 — 재생 중 대답을 바로 처리하는 결정(U4b-J33)을 지킨다.
     */
    public static final int ECHO_MIN_CHARS = 8;

    private static final List<String> REFUSE_WORDS = List.of("싫어", "싫은데", "안할래", "안해", "안하고싶", "하기싫",
            "아니", "아뇨", "노노", "안돼", "그만", "못하겠", "안할거", "안좋", "별로");
    private static final List<String> TOPIC_WORDS = List.of("이거말고", "그거말고", "얘기말고", "이야기말고", "말고다른",
            "다른거", "다른얘기", "다른이야기", "딴거", "딴얘기", "다른주제");
    /** 짧은 대답 하나로 된 참여(뒤에 '요'가 붙어도 된다). 전사와 같은 정규화를 거친 값으로 비교한다('ㅇㅇ'은 NFKC에서 바뀐다). */
    private static final List<String> ACCEPT_EXACT = normalized("응", "응응", "웅", "어", "엉", "네", "넹", "예", "ㅇㅇ",
            "그래", "그럼", "오케이", "ok", "okay", "yes", "당연하지", "물론이지");
    /**
     * 문장의 첫 낱말이면 뒤에 다른 말이 붙어도 참여로 보는 짧은 긍정('요'가 붙어도 된다). '어' · '엉'은 말머리 군말로 자주 쓰여 넣지 않는다 — 문장 전체가
     * 그 한 낱말일 때만 {@link #ACCEPT_EXACT}로 참여다.
     */
    private static final List<String> ACCEPT_FIRST_WORD = normalized("응", "응응", "웅", "웅웅", "으응", "네", "네네", "넹", "넵",
            "예", "ㅇㅇ", "그래", "좋아", "오케이", "ok", "yes", "알았어", "알겠어");
    /** 응 · 웅 · 네 · 넹 · 예가 되풀이된 첫 낱말("응응응" · "네네네요"). */
    private static final Pattern REPEATED_YES = Pattern.compile("^(응|웅|네|넹|예)+요?$");
    /** 들어 있으면 참여로 보는 말(거부 · 주제 거부를 먼저 본 뒤). */
    private static final List<String> ACCEPT_CUES = List.of("할래", "하자", "좋아", "좋지", "해볼래", "해보자", "할게",
            "재밌겠", "이야기해", "얘기해");

    private final Duration firstWindow;
    private final Duration afterPromptWindow;
    private Phase phase = Phase.PROMPTING;
    private Instant windowStart;
    private int prompts = 1;
    private boolean reprompted;
    private boolean alternativeOffered;

    /**
     * @param noReply 무응답 창(처음 · 안내 뒤 같은 값)
     */
    public JoinTracker(Duration noReply) {
        this(noReply, noReply);
    }

    /**
     * U4b(U4b-J4 · BR2.2): 두 창 시간을 받는다.
     *
     * @param firstWindow 처음 시작 안내 뒤 창({@code mujoong.timing.session.join-wait})
     * @param afterPromptWindow 재안내 · 되묻기 · 대안 뒤 창({@code mujoong.timing.session.join-wait-after-reprompt})
     */
    public JoinTracker(Duration firstWindow, Duration afterPromptWindow) {
        this.firstWindow = positive(firstWindow, "firstWindow");
        this.afterPromptWindow = positive(afterPromptWindow, "afterPromptWindow");
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("무응답 창은 0보다 커야 한다");
        }
        return value;
    }

    /**
     * 질문 · 재안내 · 대안 · 되묻기의 재생 완료(playbackMark)를 서버가 받았다 — 무응답 창을 연다.
     *
     * @param at 서버가 재생 완료 보고를 받은 시각
     */
    public void promptPlayed(Instant at) {
        Objects.requireNonNull(at, "at");
        if (phase == Phase.PROMPTING || phase == Phase.WAITING) {
            phase = Phase.WAITING;
            windowStart = at;
        }
    }

    /**
     * 아이 응답(확정 전사의 분류). 안내를 재생하는 중에 온 응답도 판단한다.
     *
     * @param reply 응답 분류
     * @param at 응답 확정 시각(창 안인지 본다)
     */
    public Action reply(Reply reply, Instant at) {
        Objects.requireNonNull(reply, "reply");
        Objects.requireNonNull(at, "at");
        if (isFinished()) {
            return Action.NONE;
        }
        if (expired(at)) {
            // 창이 찬 뒤에 온 응답 — 먼저 만기를 처리한다(경계 21초 → 재안내 · DECLINED)
            Action expiry = tick(at);
            if (expiry != Action.NONE) {
                return expiry;
            }
        }
        return switch (reply) {
            case ACCEPT -> finish(Phase.JOINED, Action.START);
            case REFUSE, TOPIC_REFUSAL -> {
                if (alternativeOffered || prompts >= MAX_PROMPTS) {
                    yield finish(Phase.DECLINED, Action.DECLINE);
                }
                alternativeOffered = true;
                yield prompt(Action.OFFER_ALTERNATIVE);
            }
            case UNCLEAR -> {
                if (reprompted || prompts >= MAX_PROMPTS) {
                    // 되묻기를 이미 썼다 — 안내 없이 지금 창을 이어 간다
                    yield Action.NONE;
                }
                reprompted = true;
                yield prompt(Action.CLARIFY);
            }
        };
    }

    /**
     * 시간 경과 확인. 창이 찼으면(시작 시각 + 창 이상) 재안내를 쓰지 않았고 안내가 남았으면 재안내, 아니면 DECLINED.
     *
     * @param now 지금 시각
     */
    public Action tick(Instant now) {
        Objects.requireNonNull(now, "now");
        if (phase != Phase.WAITING || !expired(now)) {
            return Action.NONE;
        }
        if (!reprompted && prompts < MAX_PROMPTS) {
            reprompted = true;
            return prompt(Action.REPROMPT);
        }
        return finish(Phase.DECLINED, Action.DECLINE);
    }

    /**
     * 확정 전사를 응답으로 분류한다. 순서는 주제 거부 → 거부 → 짧은 긍정(문장 전체 또는 첫 낱말) → 참여 단서 → 불명확이고, 정규화는 종료 판정기와
     * 같다. 거부 표현이 함께 있으면 짧은 긍정보다 거부가 앞선다("응 싫어"는 거부). 첫 낱말에 물음표가 있으면 짧은 긍정 규칙은 건너뛰고 단서만 본다. 빈
     * 전사는 불명확이다.
     */
    public static Reply classify(String transcript) {
        return classify(transcript, true);
    }

    /**
     * 종료 확인 질문("그만할까?")의 대답 분류: {@link #classify}와 같되 첫 낱말 규칙을 쓰지 않는다 — 짧은 긍정은 문장 전체가 그 한 낱말일 때만
     * 참여(= 끝내자)다. 참여 관문에서 넓힌 첫 낱말 규칙이 "응, 근데 더 하고 싶어"처럼 반대 뜻이 붙은 대답으로 ACTIVE 세션을 끝내지 않게 한다. 물음표
     * 규칙은 같아 되묻는 "응?"도 끝이 아니다(docs/join-gate-stabilization.md (1) · JOIN-GATE-A9).
     */
    public static Reply classifyWhole(String transcript) {
        return classify(transcript, false);
    }

    private static Reply classify(String transcript, boolean firstWordRule) {
        String text = StopIntentJudge.normalize(transcript);
        if (text.isEmpty()) {
            return Reply.UNCLEAR;
        }
        for (String word : TOPIC_WORDS) {
            if (text.contains(word)) {
                return Reply.TOPIC_REFUSAL;
            }
        }
        for (String word : REFUSE_WORDS) {
            if (text.contains(word)) {
                return Reply.REFUSE;
            }
        }
        if (!questioned(transcript)) {
            if (isOneOf(text, ACCEPT_EXACT)) {
                return Reply.ACCEPT;
            }
            List<String> words = firstWordRule ? StopIntentJudge.tokens(transcript) : List.of();
            if (!words.isEmpty() && (isOneOf(words.getFirst(), ACCEPT_FIRST_WORD) || REPEATED_YES.matcher(words.getFirst()).matches())) {
                return Reply.ACCEPT;
            }
        }
        for (String cue : ACCEPT_CUES) {
            if (text.contains(cue)) {
                return Reply.ACCEPT;
            }
        }
        return Reply.UNCLEAR;
    }

    /** 그 낱말이 목록의 하나이거나 그 뒤에 '요'가 붙은 꼴인가. */
    private static boolean isOneOf(String word, List<String> list) {
        for (String candidate : list) {
            if (word.equals(candidate) || word.equals(candidate + "요")) {
                return true;
            }
        }
        return false;
    }

    /** 원문 첫 낱말(앞뒤 공백 제외)에 물음표('?' · 전각 '？')가 있는가. 되묻는 "응?"을 짧은 긍정으로 보지 않으려고 쓴다. */
    private static boolean questioned(String transcript) {
        String stripped = transcript.strip();
        int end = 0;
        while (end < stripped.length() && !Character.isWhitespace(stripped.charAt(end))) {
            end++;
        }
        return Normalizer.normalize(stripped.substring(0, end), Normalizer.Form.NFKC).indexOf('?') >= 0;
    }

    private static List<String> normalized(String... words) {
        return Arrays.stream(words).map(StopIntentJudge::normalize).toList();
    }

    /**
     * 확정 전사가 안내 글의 에코로 보이는가: 정규화(종료 판정기와 같다) 뒤 {@link #ECHO_MIN_CHARS}자 이상이고, 같은 방식으로 정규화한 안내 글의
     * 부분 문자열이다. 기기 스피커 소리가 마이크로 다시 들어와 전사되면 안내 글의 "아니라" · "그만" · "하기 싫으면 안 해도"가 거부로 분류되는 것을 막는다.
     * 언제의 전사를 볼지(재생 중 · 끝난 직후)는 부르는 쪽이 정한다.
     *
     * @param promptText 대조할 안내 글 하나(없으면 null)
     */
    public static boolean echoOf(String transcript, String promptText) {
        if (promptText == null) {
            return false;
        }
        String text = StopIntentJudge.normalize(transcript);
        if (text.codePointCount(0, text.length()) < ECHO_MIN_CHARS) {
            return false;
        }
        return StopIntentJudge.normalize(promptText).contains(text);
    }

    /** 정규화(종료 판정기와 같다) 뒤 글자 수. 진단 로그에 쓴다(원문 대신 모양만 남긴다). */
    public static int normalizedLength(String transcript) {
        String text = StopIntentJudge.normalize(transcript);
        return text.codePointCount(0, text.length());
    }

    /** 공백으로 나눈 낱말 수(정규화해 빈 낱말은 뺀다). 진단 로그에 쓴다. */
    public static int wordCount(String transcript) {
        return transcript == null ? 0 : StopIntentJudge.tokens(transcript).size();
    }

    public Phase phase() {
        return phase;
    }

    /** 지금까지 한 안내 수(처음 질문 포함). */
    public int prompts() {
        return prompts;
    }

    public boolean isFinished() {
        return phase == Phase.JOINED || phase == Phase.DECLINED;
    }

    /** U4b: 지금 열린 창의 끝 시각(창이 없으면 null). 그 시각의 대답은 만료로 본다(BR2.2). */
    public Instant windowEnd() {
        return phase == Phase.WAITING && windowStart != null ? windowStart.plus(window()) : null;
    }

    /**
     * U4b(BR2.2 · U4b-J5): 기술 일시정지 동안 창을 멈춘다 — 열린 창의 시작을 멈춘 시간만큼 뒤로 민다(남은 시간부터 이어진다). 창이 없으면 아무것도
     * 하지 않는다.
     */
    public void shift(Duration paused) {
        Objects.requireNonNull(paused, "paused");
        if (phase == Phase.WAITING && windowStart != null && paused.isPositive()) {
            windowStart = windowStart.plus(paused);
        }
    }

    /** 지금 창 길이: 처음 안내면 처음 창, 그 뒤 안내면 안내 뒤 창. */
    private Duration window() {
        return prompts <= 1 ? firstWindow : afterPromptWindow;
    }

    private boolean expired(Instant at) {
        return phase == Phase.WAITING && windowStart != null && !at.isBefore(windowStart.plus(window()));
    }

    private Action prompt(Action action) {
        prompts++;
        phase = Phase.PROMPTING;
        windowStart = null;
        return action;
    }

    private Action finish(Phase end, Action action) {
        phase = end;
        windowStart = null;
        return action;
    }
}
