package kr.mujoong.realtime.gate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.text.ProfanityLexicon;

/**
 * 출력 게이트 시드 금지 표현 사전 (U1 3.5 Step 25 · SD-18 2.14 · A5 · NFR1.2 · J101 · contracts/ai-io.md 4절).
 *
 * <p>앱 리소스 {@value #RESOURCE} 하나를 기동 때 읽고 이후 읽기 전용 상수로 쓴다(핫 리로드 없음). 로딩 거부: 파일 없음 · 파싱 실패 · 중복
 * id · category 8개 중 항목이 없는 것 · 정규화 뒤 형태가 아닌 패턴(공백 · 구두점 · 라틴 대문자 · 정규식 메타 문자 · 제로폭 문자) — S1b가
 * 기동을 끝낸다. 패턴 문법은 리터럴과 숫자 자리표시 {@code #}(숫자 한 자 이상)뿐이라 정규식이 없고 판정은 선형에 가깝다(ReDoS 없음).
 *
 * <p>판정은 전사를 {@link #normalize}(NFKC → 공백 · 제로폭 · 구두점(% 제외) 제거 → 라틴 소문자)한 문자열에서 패턴을 부분 문자열로
 * 찾는다. 띄어쓰기 변형은 정규화가, 조사 결합은 어간 형태의 패턴이 흡수한다.
 *
 * <p>{@link Category#PROFANITY}(JIRA-266 · FT-1023-b)는 이 파일의 항목에 더해 두 앱 공통 욕설 사전({@link ProfanityLexicon}, contract-model)을
 * 함께 본다. 욕설 어휘(변형 · 예외 낱말 포함)는 core의 이름 입력 검사와 같은 한 곳에서 관리하고, 이 파일에는 범주 표지인 대표 항목 하나만 둔다
 * (다른 범주와 같이 '범주마다 항목 하나 이상' 로딩 규칙을 지킨다). 이 파일의 판정은 공백을 지우고 찾으므로 낱말 경계를 넘어 걸릴 짧은 욕설
 * ('아저씨 발' → '씨발')은 파일에 두지 않고, 낱말 경계를 지키는 공통 사전에 맡긴다. 공통 사전 로딩이 실패하면 시드 사전 로딩도 실패한다(기동 거부). 파일 항목을
 * 먼저 보고, 걸리지 않으면 공통 사전의 문장 판정({@link ProfanityLexicon#containsInText})을 본다.
 */
public final class SeedDictionary {

    /** 사전 파일 위치(classpath). */
    public static final String RESOURCE = "gate/seed-dictionary.json";
    /** 시드 수준 표지. */
    public static final String LEVEL_SEED = "SEED";
    /** 게이트 적용 범위 값. */
    public static final String APPLIES_TO_OUTPUT_GATE = "OUTPUT_GATE";

    /** 계약 9절 범주 7개 + 욕설(JIRA-266, 제브 v3 라벨과 같은 이름). */
    public enum Category {
        DIAGNOSIS,
        TREATMENT,
        SCORE,
        CAUSE,
        GENERALIZATION,
        EMOTION_CLAIM,
        PRESSURE,
        PROFANITY
    }

    /** 공통 욕설 사전으로 걸렸을 때의 항목 id(로그 · 지표에는 쓰지 않는다). */
    public static final String LEXICON_PROFANITY_ID = "LEXICON-PROFANITY";

    /** 사전 항목. */
    public record Entry(String id, String pattern, Category category, List<String> appliesTo) {

        public Entry {
            appliesTo = appliesTo == null ? List.of() : List.copyOf(appliesTo);
        }
    }

    /** 걸린 항목(로그 · 지표에는 쓰지 않는다 — 사유 열거값만). */
    public record Match(String id, Category category) {
    }

    /** 로딩 거부 사유. */
    public enum Problem {
        MISSING,
        PARSE_FAILED,
        DUPLICATE_ID,
        EMPTY_CATEGORY,
        BAD_PATTERN,
        BAD_ENTRY
    }

    /** 로딩 거부. 메시지는 사유와 항목 id · 범주 이름뿐이다(전사 · 패턴 값 없음). */
    public static final class LoadException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final Problem problem;

        LoadException(Problem problem, String detail) {
            super("시드 사전 로딩 거부: " + problem + (detail == null ? "" : " (" + detail + ")"));
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /** 파일 모양. */
    record FileShape(String version, String level, List<RawEntry> entries) {
    }

    record RawEntry(String id, String pattern, String category, List<String> appliesTo) {
    }

    private final String version;
    private final String level;
    private final List<Entry> entries;
    private final List<CompiledPattern> compiled;
    private final ProfanityLexicon profanity;

    private SeedDictionary(String version, String level, List<Entry> entries, ProfanityLexicon profanity) {
        this.version = version;
        this.level = level;
        this.entries = List.copyOf(entries);
        this.profanity = Objects.requireNonNull(profanity, "profanity");
        List<CompiledPattern> patterns = new ArrayList<>();
        for (Entry entry : this.entries) {
            if (entry.appliesTo().contains(APPLIES_TO_OUTPUT_GATE)) {
                patterns.add(CompiledPattern.of(entry));
            }
        }
        this.compiled = List.copyOf(patterns);
    }

    /** classpath의 {@value #RESOURCE}를 읽는다. */
    public static SeedDictionary loadDefault() {
        ClassLoader loader = Objects.requireNonNullElse(Thread.currentThread().getContextClassLoader(),
                SeedDictionary.class.getClassLoader());
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new LoadException(Problem.MISSING, RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LoadException(Problem.MISSING, RESOURCE);
        }
    }

    /** JSON 본문을 읽고 검증한다. 욕설 범주는 공통 욕설 사전({@link ProfanityLexicon#shared})을 함께 쓴다. */
    public static SeedDictionary parse(String json) {
        ProfanityLexicon profanity;
        try {
            profanity = ProfanityLexicon.shared();
        } catch (RuntimeException | ExceptionInInitializerError e) {
            throw new LoadException(Problem.MISSING, ProfanityLexicon.RESOURCE);
        }
        return parse(json, profanity);
    }

    /** JSON 본문을 읽고 검증한다(공통 욕설 사전을 지정, 시험용). */
    static SeedDictionary parse(String json, ProfanityLexicon profanity) {
        Objects.requireNonNull(profanity, "profanity");
        FileShape file;
        try {
            file = JsonCodec.shared().read(json, FileShape.class);
        } catch (RuntimeException e) {
            throw new LoadException(Problem.PARSE_FAILED, null);
        }
        if (file == null || file.entries() == null || file.version() == null || file.version().isBlank()
                || file.level() == null || file.level().isBlank()) {
            throw new LoadException(Problem.PARSE_FAILED, "version · level · entries");
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        for (RawEntry raw : file.entries()) {
            if (raw == null || raw.id() == null || raw.id().isBlank() || raw.pattern() == null
                    || raw.category() == null) {
                throw new LoadException(Problem.BAD_ENTRY, null);
            }
            if (!ids.add(raw.id())) {
                throw new LoadException(Problem.DUPLICATE_ID, raw.id());
            }
            Category category;
            try {
                category = Category.valueOf(raw.category());
            } catch (IllegalArgumentException e) {
                throw new LoadException(Problem.BAD_ENTRY, raw.id());
            }
            if (!isValidPattern(raw.pattern())) {
                throw new LoadException(Problem.BAD_PATTERN, raw.id());
            }
            Entry entry = new Entry(raw.id(), raw.pattern(), category, raw.appliesTo());
            entries.add(entry);
            if (entry.appliesTo().contains(APPLIES_TO_OUTPUT_GATE)) {
                counts.merge(category, 1, Integer::sum);
            }
        }
        Set<Category> empty = EnumSet.allOf(Category.class);
        empty.removeAll(counts.keySet());
        if (!empty.isEmpty()) {
            throw new LoadException(Problem.EMPTY_CATEGORY, empty.toString());
        }
        return new SeedDictionary(file.version(), file.level(), entries, profanity);
    }

    public String version() {
        return version;
    }

    public String level() {
        return level;
    }

    /** 시드 수준인가(S2b 대조, SD-24). */
    public boolean isSeedLevel() {
        return LEVEL_SEED.equals(level);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** 전사에서 처음 걸린 항목. */
    public Optional<Match> findMatch(String transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return Optional.empty();
        }
        String normalized = normalize(transcript);
        for (CompiledPattern pattern : compiled) {
            if (pattern.occursIn(normalized)) {
                return Optional.of(new Match(pattern.id, pattern.category));
            }
        }
        if (profanity.containsInText(transcript)) {
            return Optional.of(new Match(LEXICON_PROFANITY_ID, Category.PROFANITY));
        }
        return Optional.empty();
    }

    /** NFKC → 공백 · 제로폭 · 구두점(% 제외) 제거 → 라틴 소문자. */
    public static String normalize(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(nfkc.length());
        nfkc.codePoints().forEach(cp -> {
            if (cp != '%' && isRemovable(cp)) {
                return;
            }
            out.appendCodePoint(isLatin(cp) ? Character.toLowerCase(cp) : cp);
        });
        return out.toString();
    }

    private static boolean isLatin(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
    }

    /** 공백 · 제로폭 · 구두점(ASCII 구두점 · 유니코드 구두점 범주)인가. */
    static boolean isRemovable(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            return true;
        }
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF) {
            return true;
        }
        if (cp < 0x80 && isAsciiPunct(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION || type == Character.FORMAT;
    }

    private static boolean isAsciiPunct(int cp) {
        return (cp >= 0x21 && cp <= 0x2F) || (cp >= 0x3A && cp <= 0x40) || (cp >= 0x5B && cp <= 0x60)
                || (cp >= 0x7B && cp <= 0x7E);
    }

    /**
     * 패턴이 정규화 뒤 형태인가: 비지 않았고, {@code #}를 뺀 나머지가 정규화해도 그대로이며(공백 · 구두점 · 라틴 대문자 · 정규식 메타 ·
     * 제로폭 없음), {@code #} 바로 뒤가 숫자가 아니고, 리터럴 글자가 하나 이상 있다.
     */
    static boolean isValidPattern(String pattern) {
        if (pattern.isEmpty()) {
            return false;
        }
        boolean literal = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '#') {
                if (i + 1 < pattern.length() && Character.isDigit(pattern.charAt(i + 1))) {
                    return false;
                }
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '#') {
                    return false;
                }
            } else {
                literal = true;
            }
        }
        if (!literal) {
            return false;
        }
        String withoutPlaceholders = pattern.replace("#", "");
        return Normalizer.isNormalized(pattern, Normalizer.Form.NFKC)
                && normalize(withoutPlaceholders).equals(withoutPlaceholders);
    }

    /** 리터럴 · 숫자 자리표시 조각으로 나눈 패턴. */
    private static final class CompiledPattern {

        private final String id;
        private final Category category;
        private final List<String> parts;

        private CompiledPattern(String id, Category category, List<String> parts) {
            this.id = id;
            this.category = category;
            this.parts = parts;
        }

        static CompiledPattern of(Entry entry) {
            List<String> parts = new ArrayList<>();
            StringBuilder literal = new StringBuilder();
            for (char c : entry.pattern().toCharArray()) {
                if (c == '#') {
                    if (!literal.isEmpty()) {
                        parts.add(literal.toString());
                        literal.setLength(0);
                    }
                    parts.add(null);
                } else {
                    literal.append(c);
                }
            }
            if (!literal.isEmpty()) {
                parts.add(literal.toString());
            }
            // null 조각 = 숫자 자리표시(List.copyOf는 null을 받지 않는다)
            return new CompiledPattern(entry.id(), entry.category(), Collections.unmodifiableList(parts));
        }

        boolean occursIn(String text) {
            for (int start = 0; start < text.length(); start++) {
                if (matchesAt(text, start)) {
                    return true;
                }
            }
            return false;
        }

        /** {@code #}는 숫자 한 자 이상을 욕심껏 먹는다(뒤 조각이 숫자로 시작하지 않으므로 되돌릴 일이 없다). */
        private boolean matchesAt(String text, int start) {
            int position = start;
            for (String part : parts) {
                if (part == null) {
                    int digits = position;
                    while (digits < text.length() && Character.isDigit(text.charAt(digits))) {
                        digits++;
                    }
                    if (digits == position) {
                        return false;
                    }
                    position = digits;
                } else {
                    if (!text.startsWith(part, position)) {
                        return false;
                    }
                    position += part.length();
                }
            }
            return true;
        }
    }
}
