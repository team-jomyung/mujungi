package kr.mujoong.testsupport.dialogue;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 실호출 하니스의 참고 점검이 쓰는 한국어 글자 비교 도우미(시험 전용, 운영 검사 아님). core 상황 대본 하니스와 realtime 대화 하니스가 같은 규칙으로
 * 센다(docs/practice-dialogue-standard.md 14절).
 *
 * <p>형태소 분석이 아니다: 띄어쓰기 낱말에서 흔한 끝 조사 하나를 떼고, 두 글자 미만 · 흔한 대명사 · 감탄사 · 꾸밈말을 뺀 '핵심 낱말'을 정규화한 글에서
 * 찾는다. 결과는 사람이 대사를 읽기 전에 볼 참고 값이다(놓침 · 잘못 잡음이 있다).
 */
public final class HeardWords {

    /** 빈칸 예문에서 아이가 채울 자리(core 생성 판 v7과 같은 낱말). */
    public static final String BLANK = "뭐뭐";

    /** 떼어 내는 끝 조사 · 서술 꼬리(긴 것부터 맞춘다). */
    private static final List<String> ENDINGS = List.of("이라고", "에서는", "한테는", "에게는", "으로는", "이에요", "이랑", "에서", "에게", "한테",
            "으로", "까지", "부터", "처럼", "보다", "이야", "예요", "이다", "라고", "을", "를", "이", "가", "은", "는", "도", "만", "로", "에", "의",
            "와", "과", "랑", "야", "인");

    /** 핵심 낱말에서 빼는 흔한 말(정규화한 모양). */
    private static final Set<String> COMMON = Set.of("친구", "무중", "무중이", "너", "나", "내", "네", "우리", "저", "제", "너도", "나도", "내가",
            "네가", "여기", "거기", "저기", "이거", "그거", "저거", "이것", "그것", "저것", "이걸", "그걸", "저걸", "자", "응", "어", "아", "음", "그래",
            "좋아", "진짜", "정말", "지금", "이제", "같이", "함께", "그냥", "조금", "좀", "잠깐", "잠깐만", "아니", "예", "왜", "뭐", "어떤", "무슨",
            "그럼", "그리고", "근데", "그런데", "하나", "한번", "다시", "먼저", "혹시", "있어", "없어", "했어", "해", "할래", "볼래", "줄래", "줄까",
            "있지", "이렇게", "그렇게");

    /** '뭐뭐'(모음으로 끝남) 뒤 조사와 예시의 받침 뒤 조사 짝. 빈칸 뒤 말을 예시와 맞춰 볼 때 쓴다. */
    private static final Map<String, String> PARTICLE_AFTER_CONSONANT = Map.of("를", "을", "가", "이", "는", "은", "와", "과", "로", "으로",
            "랑", "이랑", "라고", "이라고", "야", "이야", "라서", "이라서");

    /** 아이에게 들리면 안 되는 지시문 낱말(정규화한 모양). */
    private static final List<String> INSTRUCTION_WORDS = List.of("말틀", "빈칸예문");

    /** 소리로 읽지 않는 문장 밖 기호(유니코드 분류로 잡히지 않는 것만). */
    private static final String EXTRA_SYMBOLS = "#*@&%_|\\~^`";

    private HeardWords() {
    }

    /** 글자 · 숫자만 남기고 NFKC · 소문자로 맞춘다(core {@code ScenarioContentPolicy.normalize} · realtime {@code SceneIntro.normalize}와 같다). */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    /** 문장의 핵심 낱말(띄어쓰기 낱말마다 끝 조사 하나를 떼고, 두 글자 미만 · 흔한 말을 뺀다, 처음 나온 순서). */
    public static List<String> keyWords(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        for (String raw : text.split("\\s+")) {
            String token = normalize(raw);
            String stem = stem(token);
            if (stem.length() >= 2 && !COMMON.contains(stem) && !COMMON.contains(token) && !words.contains(stem)) {
                words.add(stem);
            }
        }
        return words;
    }

    /** 낱말 끝의 조사 하나를 뗀다. 떼고 두 글자 미만이 되면 떼지 않는다. */
    static String stem(String token) {
        for (String ending : ENDINGS) {
            if (token.endsWith(ending) && token.length() - ending.length() >= 2) {
                return token.substring(0, token.length() - ending.length());
            }
        }
        return token;
    }

    /** {@code words} 가운데 {@code text}(정규화)에 들어 있는 낱말. */
    public static List<String> mentioned(List<String> words, String text) {
        String normalized = normalize(text);
        return words.stream().filter(word -> !word.isEmpty() && normalized.contains(word)).distinct().toList();
    }

    /** 두 문장이 정규화해서 같은가. */
    public static boolean same(String a, String b) {
        return a != null && b != null && !normalize(a).isEmpty() && normalize(a).equals(normalize(b));
    }

    /** 두 문장이 함께 가진 핵심 낱말(한쪽의 핵심 낱말이 다른 쪽 글에 들어 있음, 양쪽 방향). */
    public static List<String> sharedKeyWords(String a, String b) {
        List<String> shared = new ArrayList<>(mentioned(keyWords(a), b));
        mentioned(keyWords(b), a).stream().filter(word -> !shared.contains(word)).forEach(shared::add);
        return shared;
    }

    /**
     * 빈칸 예문이 표현 예시에서 비운 말(정규화한 모양, 빈칸 순서대로). 빈칸 밖의 말이 예시에 순서대로 있어야 맞춰 볼 수 있고(빈칸 바로 뒤 조사는
     * 받침 뒤 모양도 받는다), 맞춰 볼 수 없으면 빈 값이다.
     */
    public static Optional<List<String>> blankedWords(String example, String frame) {
        String e = normalize(example);
        String f = normalize(frame);
        if (!f.contains(BLANK)) {
            return Optional.empty();
        }
        String[] segments = f.split(BLANK, -1);
        if (!e.startsWith(segments[0])) {
            return Optional.empty();
        }
        int position = segments[0].length();
        List<String> spans = new ArrayList<>();
        for (int i = 1; i < segments.length; i++) {
            int[] match = find(e, position, segments[i], i == segments.length - 1);
            if (match == null) {
                return Optional.empty();
            }
            spans.add(e.substring(position, match[0]));
            position = match[0] + match[1];
        }
        return position == e.length() ? Optional.of(spans) : Optional.empty();
    }

    private static int[] find(String example, int from, String segment, boolean last) {
        List<String> forms = new ArrayList<>(List.of(segment));
        PARTICLE_AFTER_CONSONANT.forEach((vowel, consonant) -> {
            if (segment.startsWith(vowel)) {
                forms.add(consonant + segment.substring(vowel.length()));
            }
        });
        for (String form : forms) {
            int at = last ? (example.endsWith(form) ? example.length() - form.length() : -1) : example.indexOf(form, from);
            if (at >= from) {
                return new int[] {at, form.length()};
            }
        }
        return null;
    }

    /** 문장에 든 이모지 · 기호 · 괄호(소리로 읽지 않는 글자, 나온 순서 · 한 번씩). 따옴표 · 마침표 같은 문장부호는 세지 않는다. */
    public static List<String> symbols(String text) {
        List<String> found = new ArrayList<>();
        if (text == null) {
            return found;
        }
        text.codePoints().filter(HeardWords::symbol).mapToObj(Character::toString).distinct().forEach(found::add);
        return found;
    }

    private static boolean symbol(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.OTHER_SYMBOL, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL,
                    Character.START_PUNCTUATION, Character.END_PUNCTUATION -> true;
            default -> EXTRA_SYMBOLS.indexOf(codePoint) >= 0;
        };
    }

    /** 문장에 든 지시문 낱말('말틀' · '빈칸 예문', 정규화 비교). */
    public static List<String> instructionWords(String text) {
        String normalized = normalize(text);
        return INSTRUCTION_WORDS.stream().filter(normalized::contains).toList();
    }
}
