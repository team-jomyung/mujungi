package kr.mujoong.realtime.dialog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 연습 세션 전체에 유지하는 기본 지침(조원 프롬프트 묶음 v1의 {@code practice-system.v1.txt}, 원문 그대로). 연습 세션의 {@code session.start}
 * 지시문에 한 번만 싣고, 턴마다 보내는 500자 지시({@code PracticeInstruction})에는 넣지 않는다. 기동 때 읽고 없거나 비면 기동을 막는다.
 */
public final class PracticeSystemPrompt {

    /** 리소스 경로(판 이름이 파일 이름에 들어 있다). */
    public static final String RESOURCE = "practice/practice-system.v1.txt";
    /** 판 이름(로그 · 기록용, 원문 아님). */
    public static final String VERSION = "practice-system.v1";

    private static final String BODY = load();

    private PracticeSystemPrompt() {
    }

    /** 기본 지침 원문. */
    public static String instructions() {
        return BODY;
    }

    private static String load() {
        try (InputStream in = PracticeSystemPrompt.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("연습 기본 지침 리소스가 없다");
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (body.isEmpty()) throw new IllegalStateException("연습 기본 지침이 비었다");
            return body;
        } catch (IOException e) {
            throw new UncheckedIOException("연습 기본 지침을 읽지 못했다", e);
        }
    }
}
