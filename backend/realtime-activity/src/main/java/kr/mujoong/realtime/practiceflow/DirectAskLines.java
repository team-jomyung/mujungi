package kr.mujoong.realtime.practiceflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;

/**
 * C안 '무중이 직접 묻기'의 장면 안 말 (docs/practice-direct-ask.md 3.2 · 5장 · 7.4). 장면 안에서 무중이 하는 말은 모두 여기서 고른 승인 문장이고
 * 승인 원문 읽기로 낸다 — 그 자리에서 지어내는 말이 없다.
 *
 * <ul>
 *   <li>고정 문장은 시작 패키지 {@code approvedSpeech}(code → text)에서 읽고, 패키지에 그 코드가 없으면 같은 문장의 상수({@link #FIXED})로 대신한다.
 *   <li>대본 칸이 없으면(옛 대본 v1) 7.4의 대체 문장을 쓴다. 대본 조각을 이어 만든 대체 문장은 문장부호 규칙(마침표 · 쉼표 · 물음표만, 4.3)을
 *       지키게 따옴표 · 괄호 · 느낌표 · 물결 · 말줄임표를 덜어 낸다 — 원문 대조가 문장부호까지 엄격해서 읽은 전사가 그 기호를 빼면 버려지기 때문이다.
 *   <li>두 문장을 한 슬롯으로 이어 읽을 때(친구 말 전하기 + 묻기, 다시 설명 + 묻기, 기다리기 + 묻기)는 앞 문장 끝에 마침표를 채우고 공백 하나로 잇는다.
 * </ul>
 *
 * <p>문장은 승인 원문이라 로그 · 기록 · 예외 · 문자열 표현에 내지 않는다(R-F14).
 */
final class DirectAskLines {

    /** 도움 제안(시드 004에 이미 있음). */
    static final String HELP_OFFER = "HELP_OFFER";
    /** 변형 제안. */
    static final String VARIANT_OFFER = "VARIANT_OFFER";
    /** 다시 해 보기 무응답. */
    static final String RETRY_PROMPT = "RETRY_PROMPT";
    /** 판독 불가 · 판정 보류 확인. */
    static final String INPUT_CONFIRM = "INPUT_CONFIRM";
    /** 장면 실패 · 거절 · 한도 마무리. */
    static final String SCENE_CLOSE = "SCENE_CLOSE";
    /** 무응답 다시 묻기의 앞머리. */
    static final String WAIT_LEAD = "WAIT_LEAD";
    /** 옛 대본(askPrompt 없음)의 묻는 말. */
    static final String ASK_DEFAULT = "ASK_DEFAULT";

    /** 패키지에 코드가 없을 때 쓰는 같은 문장(설계 5장 표 그대로). */
    static final Map<String, String> FIXED = Map.of(
            HELP_OFFER, "무중이가 조금 도와줄까?",
            VARIANT_OFFER, "비슷한 이야기로 한 번 더 해 볼까?",
            RETRY_PROMPT, "네 말로 한 번 말해 볼래?",
            INPUT_CONFIRM, "잘 못 들었어. 한 번 더 말해 줄래?",
            SCENE_CLOSE, "알겠어. 이 이야기는 여기까지 할게.",
            WAIT_LEAD, "천천히 생각해도 돼.",
            ASK_DEFAULT, "너라면 친구한테 뭐라고 말할래?");

    /** 옛 대본 대체: 상황 안내(spokenIntroduction 없음). */
    static final String INTRODUCTION_FALLBACK = "상상 속 이야기를 떠올려 봐. 무중이가 이야기를 들려줄게.";
    /** 옛 대본 대체: 친구 말 전하기의 앞머리(뒤에 opportunitySentence를 잇는다). */
    static final String RELAY_LEAD = "친구가 이렇게 말했어.";
    /** 옛 대본 대체: 되묻기. */
    static final String REASK_FALLBACK = "조금 더 자세히 말해 줄래?";
    /** 옛 대본 대체: 친구 반응. */
    static final String FRIEND_REACTION_FALLBACK = "그렇게 말해 주면 친구도 네 마음을 알 거야.";
    /** 옛 대본 대체: 도움의 표현 예시 앞머리 · 뒷머리. */
    static final String EXAMPLE_LEAD = "예를 들면 ";
    static final String EXAMPLE_TAIL = "처럼 말할 수 있어.";
    /** 도움 끝의 고정 꼬리(대본 helpSpeech도 이 말로 끝난다). */
    static final String HELP_TAIL = "네 말로 해 볼래?";

    /**
     * 도움 말 하나.
     *
     * @param exampleGiven 표현 예시를 담았는가(기록의 EXPRESSION_EXAMPLE 사실 · 도움 행 exampleGiven)
     */
    record Help(String text, boolean exampleGiven) {
        Help {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String toString() {
            return "Help[exampleGiven=" + exampleGiven + "]";
        }
    }

    private final Map<String, String> approved;

    /** @param approvedSpeech 시작 패키지 approvedSpeech의 code → text(없으면 빈 지도) */
    DirectAskLines(Map<String, String> approvedSpeech) {
        this.approved = approvedSpeech == null ? Map.of() : Map.copyOf(approvedSpeech);
    }

    /** 고정 문장: 패키지의 승인 문장, 없으면 같은 문장의 상수. */
    String fixed(String code) {
        String text = approved.get(code);
        return present(text) ? text : Objects.requireNonNull(FIXED.get(code), "모르는 고정 문장 코드");
    }

    /** SCENE_INTRO: 상황 안내. */
    String introduction(Scene scene) {
        return present(scene.spokenIntroduction()) ? scene.spokenIntroduction() : INTRODUCTION_FALLBACK;
    }

    /** OPPORTUNITY 진입: 친구 말 전하기 + 묻기(한 슬롯). */
    String opportunity(Scene scene) {
        return join(relay(scene), ask(scene));
    }

    /** 상대 말을 무중이 전하는 문장. 옛 대본은 "친구가 이렇게 말했어." + 기회 문장이다. */
    String relay(Scene scene) {
        return present(scene.opportunityRelay()) ? scene.opportunityRelay() : join(RELAY_LEAD, plain(scene.opportunitySentence()));
    }

    /** 묻는 말. 옛 대본은 고정 ASK_DEFAULT다. */
    String ask(Scene scene) {
        return present(scene.askPrompt()) ? scene.askPrompt() : fixed(ASK_DEFAULT);
    }

    /** OPPORTUNITY 무응답 1회: 기다리기 + 묻기. */
    String waitAndAsk(Scene scene) {
        return join(fixed(WAIT_LEAD), ask(scene));
    }

    /** REEXPLAIN: 다시 설명 + 묻기(main은 다시 설명만 했다). */
    String reexplanation(Scene scene) {
        return join(scene.reexplanation(), ask(scene));
    }

    /** REASK: 되묻기. */
    String reask(Scene scene) {
        return present(scene.reaskPrompt()) ? scene.reaskPrompt() : REASK_FALLBACK;
    }

    /** REASK 무응답 1회: 기다리기 + 되묻기(설계에 행이 없어 OPPORTUNITY와 같은 모양으로 둔다). */
    String waitAndReask(Scene scene) {
        return join(fixed(WAIT_LEAD), reask(scene));
    }

    /**
     * FOCUS: 도움. 옛 대본은 요점(없으면 다시 설명) + 표현 예시(있을 때만) + "네 말로 해 볼래?"다. 대본 helpSpeech는 표현 예시를 담는다(4.2).
     */
    Help help(Scene scene) {
        if (present(scene.helpSpeech())) {
            return new Help(scene.helpSpeech(), true);
        }
        String focus = scene.focusPoints().stream().filter(DirectAskLines::present).findFirst().map(DirectAskLines::plain)
                .filter(DirectAskLines::present).orElse(plain(scene.reexplanation()));
        String example = scene.expressionExamples().stream().filter(DirectAskLines::present).findFirst()
                .map(DirectAskLines::plain).map(DirectAskLines::withoutEnding).filter(DirectAskLines::present).orElse(null);
        List<String> parts = new ArrayList<>(3);
        parts.add(focus);
        if (example != null) {
            parts.add(EXAMPLE_LEAD + example + EXAMPLE_TAIL);
        }
        parts.add(HELP_TAIL);
        return new Help(join(parts.toArray(String[]::new)), example != null);
    }

    /** 장면 성공 마무리: 친구 반응. */
    String friendReaction(Scene scene) {
        return present(scene.friendReaction()) ? scene.friendReaction() : FRIEND_REACTION_FALLBACK;
    }

    /** 문장들을 한 슬롯으로 잇는다: 앞 문장이 문장 끝 부호 · 쉼표로 끝나지 않으면 마침표를 채우고 공백 하나로 잇는다. */
    static String join(String... parts) {
        StringBuilder text = new StringBuilder();
        for (String raw : parts) {
            String part = raw == null ? "" : raw.strip();
            if (part.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                char last = text.charAt(text.length() - 1);
                if (last != '.' && last != '?' && last != '!' && last != ',') {
                    text.append('.');
                }
                text.append(' ');
            }
            text.append(part);
        }
        return text.toString();
    }

    /** 대본 조각을 문장부호 규칙(마침표 · 쉼표 · 물음표만)에 맞춘다: 따옴표 · 괄호 · 물결은 빼고, 느낌표 · 말줄임표는 마침표로 바꾼다. */
    static String plain(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[\"'“”‘’()\\[\\]{}「」『』<>~～]", "")
                .replaceAll("[!！]", ".")
                .replace("…", ".")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("\\s+", " ");
        return cleaned.strip();
    }

    /** 표현 예시를 "…처럼" 앞에 넣을 수 있게 끝의 문장부호를 뗀다. */
    private static String withoutEnding(String text) {
        return text.replaceAll("[.?,\\s]+$", "");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "DirectAskLines[approvedCodes=" + approved.size() + "]";
    }
}
