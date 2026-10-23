package kr.mujoong.realtime.log;

import java.util.regex.Pattern;

/**
 * 공급자 전사의 비언어 표지 · 앞머리 잡음 정리 (JIRA-262).
 *
 * <p>GPT-Live 전사에는 말이 아닌 표지가 섞인다: AI 출력 전사 끝의 생각 표지({@code [hum} 등), 아이 입력 전사의 울음 같은 소리 표지
 * ({@code [cry}), 짝 없는 {@code ]}, 아이 전사 앞머리의 문장부호 잡음({@code .} · {@code ..}). 저장 · 보호자 표시 · 하류(결과 작업 인용 대조 ·
 * 리포트) · 판정 입력이 같은 글을 보도록 저장 전에 한 번 정리한다. 순수 함수이고 같은 글을 다시 넣어도 결과가 같다(멱등).
 *
 * <p>대괄호 표지는 <b>영문자로 시작하는 짧은 낱말</b>만 지운다(닫는 괄호가 없어도 지운다). 한글이 든 대괄호는 사람 말일 수 있어 두고, 짝 없는 닫는
 * 괄호만 지운다. 아이 말의 울음 표지도 지운다: 표지 글자가 아이가 한 말처럼 인용 · 판정되지 않게 하고, 표지만 있던 행은 빈 전사(불확실 표시)로
 * 남는다.
 */
public final class TranscriptMarkers {

    /** 영문 표지: {@code [hum]} · {@code [hum} · {@code [ cry ]} · {@code [laughs softly]}. */
    private static final Pattern BRACKET_MARKER = Pattern.compile("\\[\\s*[A-Za-z][A-Za-z _'-]{0,40}\\s*]?");
    /** 남은 짝 없는 대괄호 조각: 끝에 홀로 남은 여는 괄호, 여는 괄호 없는 닫는 괄호. */
    private static final Pattern DANGLING_OPEN = Pattern.compile("\\[\\s*$");
    /** 아이 전사 앞머리의 문장부호 잡음. */
    private static final Pattern LEADING_NOISE = Pattern.compile("^\\s*[.,·…~!?\\-][\\s.,·…~!?\\-]*");
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile(" +([.,!?…])");

    private TranscriptMarkers() {
    }

    /** AI 출력 전사: 대괄호 비언어 표지를 지운다. 대괄호가 없는 글은 한 글자도 바꾸지 않는다. */
    public static String cleanAi(String text) {
        if (!hasBracket(text)) {
            return text;
        }
        return tidy(stripMarkers(text));
    }

    /** 아이 입력 전사: 대괄호 비언어 표지와 앞머리 문장부호 잡음을 지운다. 정리 결과가 비면 빈 글이다. */
    public static String cleanChild(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = hasBracket(text) ? tidy(stripMarkers(text)) : text;
        return LEADING_NOISE.matcher(out).replaceFirst("");
    }

    private static boolean hasBracket(String text) {
        return text != null && (text.indexOf('[') >= 0 || text.indexOf(']') >= 0);
    }

    private static String stripMarkers(String text) {
        String out = BRACKET_MARKER.matcher(text).replaceAll(" ");
        out = DANGLING_OPEN.matcher(out).replaceAll("");
        return removeUnmatchedClosers(out);
    }

    /** 여는 괄호와 짝이 맞지 않는 닫는 괄호만 지운다(한글이 든 대괄호 쌍은 둔다). */
    private static String removeUnmatchedClosers(String text) {
        if (text.indexOf(']') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int open = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                open++;
            } else if (c == ']') {
                if (open == 0) {
                    out.append(' ');
                    continue;
                }
                open--;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String tidy(String text) {
        String out = SPACES.matcher(text).replaceAll(" ");
        out = SPACE_BEFORE_PUNCT.matcher(out).replaceAll("$1");
        return out.strip();
    }
}
