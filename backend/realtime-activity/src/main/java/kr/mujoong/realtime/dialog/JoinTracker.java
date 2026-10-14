package kr.mujoong.realtime.dialog;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

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

    private static final List<String> REFUSE_WORDS = List.of("싫어", "싫은데", "안할래", "안해", "안하고싶", "하기싫",
            "아니", "아뇨", "노노", "안돼", "그만", "못하겠", "안할거", "안좋", "별로");
    private static final List<String> TOPIC_WORDS = List.of("이거말고", "그거말고", "얘기말고", "이야기말고", "말고다른",
            "다른거", "다른얘기", "다른이야기", "딴거", "딴얘기", "다른주제");
    /** 짧은 대답 하나로 된 참여(뒤에 '요'가 붙어도 된다). */
    private static final List<String> ACCEPT_EXACT = List.of("응", "응응", "웅", "어", "엉", "네", "넹", "예", "ㅇㅇ",
            "그래", "그럼", "오케이", "ok", "okay", "yes", "당연하지", "물론이지");
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

    /** 확정 전사를 응답으로 분류한다(주제 거부 → 거부 → 참여 → 불명확 순, 정규화는 종료 판정기와 같다). 빈 전사는 불명확이다. */
    public static Reply classify(String transcript) {
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
        for (String word : ACCEPT_EXACT) {
            if (text.equals(word) || text.equals(word + "요")) {
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
