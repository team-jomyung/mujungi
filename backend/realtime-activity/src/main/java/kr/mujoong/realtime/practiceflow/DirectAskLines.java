package kr.mujoong.realtime.practiceflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;

/**
 * C안 '무중이 직접 묻기'의 장면 안 말 (docs/practice-direct-ask.md 3.2 · 5장 · 7.4). 장면 안에서 무중이 하는 말은 모두 여기서 고른 승인 문장이고
 * 승인 원문 읽기로 낸다 — 그 자리에서 지어내는 말이 없다. 장면 밖 말 가운데 목표 안내도 서버가 정한 승인 문장({@link #goalBrief})으로 읽는다(편차
 * FT-1024-g, 같은 문서 7.3). 목표 회고 · 낭독 실패 뒤 '뜻 전하기'는 생성형 그대로다.
 *
 * <ul>
 *   <li>고정 문장은 시작 패키지 {@code approvedSpeech}(code → text)에서 읽고, 패키지에 그 코드가 없으면 같은 문장의 상수({@link #FIXED})로 대신한다.
 *   <li>대본 칸이 없으면(옛 대본 v1, 또는 core가 서버 검사에 걸려 그 칸만 뺀 v2 대본) 칸마다 7.4의 대체 문장을 쓴다. 상대 말 전하기의 앞머리 · 묻기 ·
 *       반응은 장면 relation의 상대 호칭({@link Partner}: 선생님(높임말) · 선배 · 동생 · 호칭 없음(가족) · 친구)으로 고른다. 대본 조각을 이어 만든
 *       대체 문장은 문장부호 규칙(마침표 · 쉼표 · 물음표만, 4.3)을 지키게 따옴표 · 괄호 · 느낌표 · 물결 · 말줄임표를 덜어 낸다 — 원문 대조가
 *       문장부호까지 엄격해서 읽은 전사가 그 기호를 빼면 버려지기 때문이다.
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

    /** 목표 안내 승인 문장의 앞머리(뒤에 목표 이름 + 받침에 맞춘 '이야.' · '야.'를 잇는다, 편차 FT-1024-g). */
    static final String GOAL_BRIEF_LEAD = "오늘 연습할 목표는 ";
    /** 목표 안내: 오늘 목표가 하나일 때의 둘째 문장. */
    static final String GOAL_BRIEF_ONLY = "이 목표로 짧게 이야기 연습을 하고 오늘 연습을 마무리할 거야.";
    /** 목표 안내: 목표가 여럿일 때 몇 번째인지 말한 뒤의 문장. */
    static final String GOAL_BRIEF_ONE_OF = "이 목표로 짧게 이야기 연습을 해 볼 거야.";
    /** 목표 안내의 마지막 문장. */
    static final String GOAL_BRIEF_TAIL = "필요하면 도움을 받아도 되고, 언제든 쉬거나 끝내도 돼.";
    /**
     * 목표 안내 승인 문장에 넣을 수 있는 목표 이름의 최대 글자 수(공백 포함, 다듬은 뒤). AI 목표 후보 표시 이름 상한(12자,
     * {@code ai-goal-candidate-output})보다 넉넉하게 둔다. 넘으면 생성형 목표 안내로 대신한다.
     */
    static final int GOAL_NAME_MAX = 20;
    /** 오늘 고른 목표 수(1 ~ 3, 연습 시작 요청의 목표 상한 3)와 지금 몇 번째인지를 읽는 말. 숫자는 낭독 · 받아쓰기에서 꼴이 바뀌어 원문 대조가 어긋난다. */
    private static final List<String> COUNT_WORDS = List.of("한", "두", "세");
    private static final List<String> ORDINAL_WORDS = List.of("첫", "두", "세");

    /**
     * 대체 문장의 상대 호칭(계약 ai-io.md 10.18 '상대 호칭별 대체' 표). 장면 relation으로 고르고({@link #of}), 대본 칸이 없을 때(옛 대본 v1이거나 core가
     * 서버 검사에 걸려 뺀 칸) 상대 말 전하기의 앞머리 · 묻기 · 반응에만 쓴다. 친구(그 밖의 모든 값)는 지금 문장 그대로이고 묻기는 시드 고정 문장
     * {@code ASK_DEFAULT}다. 다른 호칭의 묻기는 시드 문장이 아니라 이 상수다 — 장면 안 승인 낭독은 대본 ID로만 기록하고 고정 문장 코드 · 판으로 기록하지
     * 않아서(승인 원문 대조도 문장 자체로 한다) 상수로 두어도 기록이 틀어지지 않는다.
     */
    enum Partner {
        FRIEND(RELAY_LEAD, null, FRIEND_REACTION_FALLBACK),
        TEACHER("선생님이 이렇게 말씀하셨어.", "너라면 선생님께 뭐라고 말씀드릴래?", "그렇게 말씀드리면 선생님도 네 마음을 아실 거야."),
        SENIOR("선배가 이렇게 말했어.", "너라면 선배한테 뭐라고 말할래?", "그렇게 말해 주면 선배도 네 마음을 알 거야."),
        JUNIOR("동생이 이렇게 말했어.", "너라면 동생한테 뭐라고 말할래?", "그렇게 말해 주면 동생도 네 마음을 알 거야."),
        /** 호칭 없는 중립 문장: 가족, 그리고 동아리 밖의 형 · 누나 · 언니 · 오빠(가족인지 손윗사람인지 갈리지 않는다). */
        NEUTRAL("그때 이렇게 말했어.", "너라면 뭐라고 말할래?", "그렇게 말해 주면 네 마음이 잘 전해질 거야.");

        /** 손윗사람을 부르는 친족 호칭. 동아리 이름과 함께면 선배이고, 아니면 가족일 수도 있어 호칭 없는 문장이다. */
        private static final List<String> ELDER_WORDS = List.of("형", "누나", "언니", "오빠");
        /** 가족 낱말. */
        private static final List<String> FAMILY_WORDS = List.of("가족", "엄마", "아빠", "부모", "할머니", "할아버지");

        private final String relayLead;
        private final String ask;
        private final String reaction;

        Partner(String relayLead, String ask, String reaction) {
            this.relayLead = relayLead;
            this.ask = ask;
            this.reaction = reaction;
        }

        /** 상대 말 전하기의 앞머리(뒤에 opportunitySentence를 잇는다). */
        String relayLead() {
            return relayLead;
        }

        /** 묻는 말. 친구는 null(시드 고정 문장 ASK_DEFAULT를 쓴다). */
        String ask() {
            return ask;
        }

        /** 상대 반응. */
        String reaction() {
            return reaction;
        }

        /**
         * relation 코드 {@code TEACHER} · {@code CLUB_SENIOR} · {@code CLUB_JUNIOR} · {@code FAMILY}는 표의 호칭이다. 코드가 아닌 관계 이름(core가
         * 그래프 코드로 바꾸지 않은 목표의 관계 이름, 예: '동아리 형')은 낱말로 고른다({@link #byName}). 그 밖의 값(친구 · 또래 코드 · 모르는 값)은 친구다.
         */
        static Partner of(String relation) {
            String value = relation == null ? "" : relation.replaceAll("\\s", "");
            return switch (value) {
                case "TEACHER" -> TEACHER;
                case "CLUB_SENIOR" -> SENIOR;
                case "CLUB_JUNIOR" -> JUNIOR;
                case "FAMILY" -> NEUTRAL;
                default -> byName(value);
            };
        }

        /**
         * 관계 이름의 낱말로 고른다(공백은 뺀 값): '선생님' · '교사'는 선생님, '선배'나 '동아리' + 손윗사람 호칭은 선배, '후배' · '동생'은 동생,
         * 가족 낱말이나 동아리 밖의 손윗사람 호칭은 호칭 없는 문장이고, 그 밖은 친구다.
         */
        private static Partner byName(String name) {
            if (name.contains("선생님") || name.contains("교사")) {
                return TEACHER;
            }
            if (name.contains("선배")) {
                return SENIOR;
            }
            if (name.contains("후배") || name.contains("동생")) {
                return JUNIOR;
            }
            boolean elder = ELDER_WORDS.stream().anyMatch(name::contains);
            if (elder && name.contains("동아리")) {
                return SENIOR;
            }
            if (elder || FAMILY_WORDS.stream().anyMatch(name::contains)) {
                return NEUTRAL;
            }
            return FRIEND;
        }
    }

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

    /** 상대 말을 무중이 전하는 문장. 칸이 없으면 상대 호칭별 앞머리(친구는 "친구가 이렇게 말했어.") + 기회 문장이다. */
    String relay(Scene scene) {
        if (present(scene.opportunityRelay())) {
            return scene.opportunityRelay();
        }
        return join(Partner.of(scene.relation()).relayLead(), plain(scene.opportunitySentence()));
    }

    /** 묻는 말. 칸이 없으면 친구는 고정 ASK_DEFAULT, 다른 호칭은 호칭별 묻기 상수다. */
    String ask(Scene scene) {
        if (present(scene.askPrompt())) {
            return scene.askPrompt();
        }
        Partner partner = Partner.of(scene.relation());
        return partner == Partner.FRIEND ? fixed(ASK_DEFAULT) : partner.ask();
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

    /** 장면 성공 마무리: 상대 반응. 칸이 없으면 상대 호칭별 반응 문장(친구는 "그렇게 말해 주면 친구도 네 마음을 알 거야.")이다. */
    String friendReaction(Scene scene) {
        if (present(scene.friendReaction())) {
            return scene.friendReaction();
        }
        return Partner.of(scene.relation()).reaction();
    }

    /**
     * 장면 밖 목표 안내의 승인 문장(편차 FT-1024-g, 계약 ai-io.md 10.18 '목표 안내 승인 낭독'). 생성형 목표 안내({@link PracticeInstructions#goalBrief})와
     * 같은 뜻을 서버가 정한 문장으로 쓰되 문장부호 규칙(마침표 · 쉼표 · 물음표만, 4.3)을 지킨다: 목표 이름을 감싸던 작은따옴표를 쓰지 않고, 이름 뒤는 끝
     * 글자의 받침으로 '이야.'(받침 있음) · '야.'(받침 없음)를 고르며, 목표 수 · 몇 번째는 숫자 대신 말("두 개" · "첫 번째")로 읽는다.
     *
     * <p>목표 이름({@link #goalName})을 다듬을 수 없거나(빈 이름 · 너무 김 · 한글 음절과 띄어쓰기 밖의 글자) 목표 수 · 순서가 1 ~ 3 밖이면 승인 문장을 만들지
     * 않는다 — 호출자는 지금의 생성형 목표 안내로 대신한다.
     *
     * @param goalName 목표 표시 이름(시작 패키지)
     * @param index 지금 목표의 순서(1부터)
     * @param count 오늘 고른 목표 수
     * @return 승인 문장, 만들 수 없으면 null
     */
    static String goalBrief(String goalName, int index, int count) {
        String name = goalName(goalName);
        if (name == null || count < 1 || count > COUNT_WORDS.size() || index < 1 || index > count) {
            return null;
        }
        String opening = GOAL_BRIEF_LEAD + name + (hasFinalConsonant(name) ? "이야." : "야.");
        String scope = count == 1 ? GOAL_BRIEF_ONLY
                : "오늘 고른 목표는 " + COUNT_WORDS.get(count - 1) + " 개이고, 지금은 " + ORDINAL_WORDS.get(index - 1) + " 번째야. "
                        + GOAL_BRIEF_ONE_OF;
        return opening + " " + scope + " " + GOAL_BRIEF_TAIL;
    }

    /**
     * 목표 이름을 목표 안내 승인 문장에 넣을 꼴로 다듬는다: 한글을 조합형(NFC)으로 맞추고, 제어 문자 · 겹친 공백을 공백 하나로 바꾸고, 따옴표는 걷는다.
     * 남은 글자가 한글 음절과 띄어쓰기뿐이고 1 ~ {@link #GOAL_NAME_MAX}자일 때만 쓴다. 그 밖의 글자(숫자 · 로마자 · 그 밖의 문장부호 · 기호 · 낱자)는
     * 문장부호 규칙을 어기거나 낭독 · 받아쓰기에서 꼴이 바뀌어 원문 대조가 어긋나므로 고쳐 쓰지 않고 null을 돌려준다.
     *
     * @return 다듬은 이름, 쓸 수 없으면 null
     */
    static String goalName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC)
                .replaceAll("[\"'“”‘’「」『』]", "")
                .replaceAll("[\\p{Cntrl}\\s\\p{Z}]+", " ")
                .strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > GOAL_NAME_MAX) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != ' ' && (c < '가' || c > '힣')) {
                return null;
            }
        }
        return name;
    }

    /** 끝 글자(한글 음절)에 받침이 있는가. */
    private static boolean hasFinalConsonant(String name) {
        return (name.charAt(name.length() - 1) - '가') % 28 != 0;
    }

    /** 문장 끝 부호(마침표 · 물음표 · 느낌표 · 말줄임표, 전각 포함 — {@code PracticeSpeech}가 문장 끝으로 보는 부호와 같다). */
    private static final String SENTENCE_ENDS = ".?!。？！…";
    /** 문장 끝 부호 뒤에 붙어 그 문장에 드는 닫는 따옴표(옛 대본 조각). */
    private static final String CLOSING_QUOTES = "\"”’'";

    /**
     * 조각 단위 승인 낭독(B1)이 원문 중간에서 끊겨 앞부분만 들렸을 때 다시 읽을 나머지(docs/practice-direct-ask.md 7.7). 원문을 문장 끝 부호로 나눠,
     * 들린 곳이 끝나는 위치가 든 문장부터 원문 끝까지다 — 문장 중간에서 끊겼으면 그 문장 처음부터, 문장 끝 부호까지 들렸으면 다음 문장부터다. 나머지는 승인
     * 원문의 일부라 그대로 새 승인 원문으로 읽는다(원문 대조 · B1도 이 나머지가 기준이다). 들리지 않은 글에 말(글자 · 숫자)이 없으면(문장부호 · 공백뿐,
     * 예: 전사가 말끝 물음표만 빠뜨림) 다 들린 것으로 보고 null이다.
     *
     * @param text 승인 원문(재생한 그대로)
     * @param heardEnd 원문에서 들린 곳이 끝나는 색인(0 ~ 원문 길이)
     * @return 다시 읽을 나머지, 다시 읽을 말이 없으면 null
     */
    static String unheardRest(String text, int heardEnd) {
        if (text == null) {
            return null;
        }
        int cut = Math.clamp(heardEnd, 0, text.length());
        if (text.substring(cut).codePoints().noneMatch(Character::isLetterOrDigit)) {
            return null;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (SENTENCE_ENDS.indexOf(text.charAt(i)) < 0) {
                continue;
            }
            int next = i + 1;
            while (next < text.length() && (SENTENCE_ENDS.indexOf(text.charAt(next)) >= 0 || CLOSING_QUOTES.indexOf(text.charAt(next)) >= 0)) {
                next++;
            }
            if (next > cut) {
                break;
            }
            start = next;
            i = next - 1;
        }
        return text.substring(start).strip();
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
