package kr.mujoong.realtime.dialog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import kr.mujoong.contract.status.ActivityType;

/**
 * 활동 종류별 GPT-Live 세션 지시문 묶음. {@code session.start} 지시문은 공통 역할 문장({@link DialogInstructions#role}, 따옴표로 묶은 아이
 * 정보 포함) 뒤에 이 묶음의 활동 본문을 붙여 만든다. 활동 본문은 자원 파일에 두어 대화 품질 담당이 코드 없이 고칠 수 있게 하고, 판 이름과 본문
 * 바이트의 sha256을 세션 사건으로 남겨 어떤 지시문으로 대화했는지 원문 없이 되짚게 한다.
 *
 * <p>기동 때 한 번 읽는다. 파일이 없거나 비었거나 상한을 넘거나 자리 표시자({@code { }})가 있으면 기동을 거부한다. 예외 메시지에는 자원 경로와
 * 사유만 두고 본문은 넣지 않는다. 판을 올릴 때는 새 자원 파일을 만들고 아래 판 상수 한 줄만 바꾼다(자원 이름은 판 이름에서 정해진다).
 */
public final class LiveSessionInstructions {

    /** 자원 폴더. */
    public static final String RESOURCE_DIR = "live-prompts/";
    /** 자유대화 활동 본문 판. 자원은 {@code live-prompts/free-talk.v1.txt}. */
    public static final String FREE_TALK_VERSION = "live-free-talk.v1";
    /**
     * 연습 활동 본문 판. 자원은 {@code live-prompts/practice.v2.txt}(4회차 R3: 서버가 이끄는 연습 — 지시가 없을 때 아이 말에는 '응.' 한 마디, 무중 · 아이
     * 이름 구분). 앞 판 {@code practice.v1.txt}는 기록의 sha256을 되짚을 수 있게 남긴다.
     */
    public static final String PRACTICE_VERSION = "live-practice.v2";
    /**
     * 활동 본문 상한(코드 포인트). 공급자 {@code session.start} 지시문 상한은 기록이 없어 임시로 둔 값이다. 실제로 받아들여지는지는 실호출 도구로 사람이
     * 확인한다.
     */
    public static final int MAX_LENGTH = 4000;

    private static final String VERSION_PREFIX = "live-";
    private static final Pattern VERSION = Pattern.compile("^live-[a-z0-9-]+\\.v[0-9]+$");

    /** 활동 본문 하나. {@link #toString()}에는 본문을 넣지 않는다. */
    public record Body(ActivityType kind, String version, String sha256, String text) {

        public Body {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String toString() {
            return "LiveSessionInstructions.Body[kind=" + kind + ", version=" + version + ", sha256=" + sha256 + "]";
        }
    }

    private final Map<ActivityType, Body> bodies;

    private LiveSessionInstructions(Map<ActivityType, Body> bodies) {
        this.bodies = bodies;
    }

    /** 실행 자원에서 읽는다. */
    public static LiveSessionInstructions loadDefault() {
        return load(LiveSessionInstructions::readResource);
    }

    /**
     * 자원 읽기 함수로 읽는다(시험이 빠진 파일 · 빈 파일 · 긴 파일을 넣는다).
     *
     * @param reader 자원 경로 → 바이트(없으면 null)
     */
    public static LiveSessionInstructions load(Function<String, byte[]> reader) {
        Objects.requireNonNull(reader, "reader");
        Map<ActivityType, Body> bodies = new EnumMap<>(ActivityType.class);
        bodies.put(ActivityType.FREE_TALK, read(reader, ActivityType.FREE_TALK, FREE_TALK_VERSION));
        bodies.put(ActivityType.PRACTICE, read(reader, ActivityType.PRACTICE, PRACTICE_VERSION));
        return new LiveSessionInstructions(Map.copyOf(bodies));
    }

    /** 판 이름에서 정해지는 자원 경로({@code live-practice.v1} → {@code live-prompts/practice.v1.txt}). */
    public static String resource(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("세션 지시문 판 이름 형식이 올바르지 않다");
        }
        return RESOURCE_DIR + version.substring(VERSION_PREFIX.length()) + ".txt";
    }

    /** 활동 종류의 본문. 알 수 없는 활동 종류는 세션 계획이 받지 않으므로 여기 오면 프로그램 오류다. */
    public Body of(ActivityType kind) {
        Body body = bodies.get(Objects.requireNonNull(kind, "kind"));
        if (body == null) {
            throw new IllegalArgumentException("세션 지시문이 없는 활동 종류: " + kind);
        }
        return body;
    }

    private static Body read(Function<String, byte[]> reader, ActivityType kind, String version) {
        String path = resource(version);
        byte[] bytes = reader.apply(path);
        if (bytes == null) {
            throw new IllegalStateException("세션 지시문 자원이 없다: " + path);
        }
        // 파일 끝 줄바꿈(편집기 설정)은 본문이 아니다. 보내는 본문과 해시가 같은 바이트를 보게 한다
        String text = new String(bytes, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("세션 지시문 자원이 비었다: " + path);
        }
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            throw new IllegalStateException("세션 지시문 자원이 상한을 넘는다: " + path);
        }
        if (text.indexOf('{') >= 0 || text.indexOf('}') >= 0) {
            // 아이 정보는 코드가 따옴표로 묶어 붙인다. 파일 안 자리 표시자는 지시로 읽힐 수 있어 받지 않는다
            throw new IllegalStateException("세션 지시문 자원에 자리 표시자가 있다: " + path);
        }
        return new Body(kind, version, sha256(text.getBytes(StandardCharsets.UTF_8)), text);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }

    private static byte[] readResource(String path) {
        try (InputStream in = LiveSessionInstructions.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
