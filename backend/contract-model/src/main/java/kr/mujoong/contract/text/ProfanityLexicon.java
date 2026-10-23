package kr.mujoong.contract.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 두 앱이 함께 쓰는 욕설 어휘 사전 (JIRA-266 · contracts/deviations.md FT-1023-b).
 *
 * <p>core-api(아이 이름 입력 거절)와 realtime-activity(세션 지시문의 이름 대체 · 1차 출력 사전 {@code PROFANITY} 범주)가 같은 어휘로
 * 판정하도록 판정기와 자원 파일 {@value #RESOURCE}을 공용 계약 모듈에 둔다. 두 앱은 서로의 코드를 참조하지 않는다(R-F5). 프레임워크 의존 · 로그 ·
 * 시각 읽기가 없는 순수 함수다(판정 결과에 원문을 담지 않는다, R-F14).
 *
 * <p>정규화({@link #normalize}): NFKC → 라틴 소문자 → 글자(문자 · 한글 자모)가 아닌 것(공백 제외)은 모두 지운다. 그래서 문장부호 · 숫자 ·
 * 특수문자를 끼운 변형({@code 씨.발} · {@code 씨1발} · {@code 씨~발})도 붙여서 본다. 한글 호환 자모({@code ㅅㅂ})는 NFKC가 조합용 자모로 바꾸므로
 * 사전 항목도 같은 정규화를 거쳐 비교한다.
 *
 * <p>두 가지 판정을 둔다.
 *
 * <ul>
 *   <li>{@link #containsInName}(이름 · 짧은 입력): 공백까지 지워 한 덩어리로 본다({@code 씨 발} · {@code 개 새끼}도 걸린다).
 *   <li>{@link #containsInText}(AI 말 · 문장): 공백을 낱말 경계로 남긴다. 낱말마다 붙여 보고, 한 글자 낱말이 이어진 곳({@code 씨 발 아})만 이어
 *       본다. 띄어 쓴 일반 문장({@code 아저씨 발이 커요} · {@code 다시 팔을 들어})이 경계를 넘어 걸리지 않게 하려는 것이다.
 * </ul>
 *
 * <p>예외(허용 낱말): 금지 항목을 품은 일반 낱말({@code 시발점} · {@code 수박씨} · {@code 아저씨} · {@code 등신대} 등)은 판정 전에 지운 자리로
 * 바꾼다. 지운 자리는 앞뒤 글자를 잇지 않으므로 {@code 수박씨발라}에서 {@code 씨발}을 찾지 않는다. 이 때문에 허용 낱말 바로 뒤에 붙인 욕설
 * ({@code 아저씨발})은 놓친다 — 알려진 틈이다.
 */
public final class ProfanityLexicon {

    /** 자원 파일 위치(classpath). */
    public static final String RESOURCE = "kr/mujoong/contract/text/profanity-lexicon.txt";

    /** 허용 낱말을 바꿔 넣는 자리(정규화 뒤에는 나올 수 없는 글자라 금지 항목과 이어지지 않는다). */
    private static final char MASK = '\u0000';

    private final String version;
    private final List<String> blocked;
    private final List<String> allowed;

    private ProfanityLexicon(String version, List<String> blocked, List<String> allowed) {
        this.version = version;
        this.blocked = List.copyOf(blocked);
        // 긴 허용 낱말부터 지운다(짧은 낱말이 긴 낱말 일부를 먼저 지워 긴 낱말이 남는 일이 없게)
        this.allowed = allowed.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    /** 앱 안에서 한 번 읽어 쓰는 사전. 자원이 없거나 형식이 틀리면 {@link LoadException}. */
    public static ProfanityLexicon shared() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        private static final ProfanityLexicon INSTANCE = loadDefault();
    }

    /** classpath의 {@value #RESOURCE}를 읽는다. */
    public static ProfanityLexicon loadDefault() {
        ClassLoader loader = Objects.requireNonNullElse(ProfanityLexicon.class.getClassLoader(),
                ClassLoader.getSystemClassLoader());
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new LoadException("자원 없음");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LoadException("자원 읽기 실패");
        }
    }

    /**
     * 사전 본문을 읽는다. 형식: {@code #}로 시작하는 줄과 빈 줄은 주석, {@code @version <값>} 한 줄, {@code [block]} · {@code [allow]} 구역
     * 머리 아래에 한 줄에 낱말 하나. 정규화 뒤 비는 낱말 · 구역 밖 낱말 · 판 없음 · 금지 항목 없음은 거부한다(메시지에 낱말을 싣지 않는다).
     */
    public static ProfanityLexicon parse(String text) {
        Objects.requireNonNull(text, "text");
        String version = null;
        Set<String> blocked = new LinkedHashSet<>();
        Set<String> allowed = new LinkedHashSet<>();
        Set<String> section = null;
        int lineNo = 0;
        for (String raw : text.split("\n", -1)) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("@version ")) {
                version = line.substring("@version ".length()).strip();
                continue;
            }
            if (line.equals("[block]")) {
                section = blocked;
                continue;
            }
            if (line.equals("[allow]")) {
                section = allowed;
                continue;
            }
            String word = compact(normalize(line));
            if (section == null || word.isEmpty()) {
                throw new LoadException("형식 오류 " + lineNo + "번째 줄");
            }
            section.add(word);
        }
        if (version == null || version.isEmpty() || blocked.isEmpty()) {
            throw new LoadException("판 또는 금지 항목 없음");
        }
        return new ProfanityLexicon(version, new ArrayList<>(blocked), new ArrayList<>(allowed));
    }

    /** 사전 판(자원 파일 {@code @version}). */
    public String version() {
        return version;
    }

    /** 금지 항목 수(정규화 뒤 중복 제외). */
    public int blockedCount() {
        return blocked.size();
    }

    /** 이름 · 짧은 입력 판정: 공백까지 지운 한 덩어리에서 금지 항목을 찾는다. */
    public boolean containsInName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return containsIn(compact(normalize(name)));
    }

    /** 문장 판정: 낱말마다, 그리고 한 글자 낱말이 이어진 곳을 이어서 금지 항목을 찾는다. */
    public boolean containsInText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String[] words = normalize(text).split(" ");
        StringBuilder singles = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (containsIn(word)) {
                return true;
            }
            if (word.codePointCount(0, word.length()) == 1) {
                singles.append(word);
                continue;
            }
            if (singles.length() > 1 && containsIn(singles.toString())) {
                return true;
            }
            singles.setLength(0);
        }
        return singles.length() > 1 && containsIn(singles.toString());
    }

    /** 허용 낱말을 지운 자리로 바꾼 뒤 금지 항목을 부분 문자열로 찾는다. */
    private boolean containsIn(String compacted) {
        String masked = compacted;
        for (String allow : allowed) {
            if (masked.contains(allow)) {
                masked = masked.replace(allow, String.valueOf(MASK));
            }
        }
        for (String block : blocked) {
            if (masked.contains(block)) {
                return true;
            }
        }
        return false;
    }

    /**
     * NFKC → 라틴 소문자 → 글자(문자 범주 · 한글 자모)는 남기고, 공백류는 공백 하나로, 그 밖(문장부호 · 숫자 · 기호 · 제로폭)은 지운다.
     */
    public static String normalize(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(nfkc.length());
        boolean lastSpace = false;
        for (int i = 0; i < nfkc.length(); ) {
            int cp = nfkc.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (!lastSpace && !out.isEmpty()) {
                    out.append(' ');
                    lastSpace = true;
                }
                continue;
            }
            if (Character.isLetter(cp)) {
                out.appendCodePoint(Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
                        ? Character.toLowerCase(cp) : cp);
                lastSpace = false;
            }
        }
        int end = out.length();
        if (end > 0 && out.charAt(end - 1) == ' ') {
            out.setLength(end - 1);
        }
        return out.toString();
    }

    private static String compact(String normalized) {
        return normalized.replace(" ", "");
    }

    /** 사전 로딩 거부. 메시지에 낱말을 싣지 않는다. */
    public static final class LoadException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        LoadException(String detail) {
            super("욕설 사전 로딩 거부: " + detail);
        }
    }
}
