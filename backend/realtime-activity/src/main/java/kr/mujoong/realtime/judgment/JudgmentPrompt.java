package kr.mujoong.realtime.judgment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;
import kr.mujoong.realtime.content.LiveContentResources;

/**
 * 판정 지시문과 그 판 번호 (U11 BR1.1). 지시문은 요청 {@code instructions} 자리로만 가고 아이 발화 같은 데이터는 섞지 않는다(U9 BR1.8). 판
 * 번호는 {@code LunaRequest.promptVersion} 형식을 따르고 판정 기록의 {@code promptVersion}으로 남는다. 기본 지시문은 자원 파일
 * {@value #RESOURCE}에 지시 본문을 두고, 지점별 질문 · 선택지 뜻은 세션에 고정되는 판정 선택지 판에서 이어 붙인다(선택지 정의를 두 곳에 두지 않는다).
 */
public record JudgmentPrompt(String version, String instructions) {

    /**
     * 지시 본문 자원. 판 번호는 파일 이름의 {@code prompt-v3}이다. v3은 v1 본문에 J4 규칙만 더했다: J4는 무중의 도움 제안과 '이어서 해 볼까?'
     * 제안에 쓰고, '그만하고 싶어' · '이제 그만할래' · '여기까지만 할래' · '오늘은 안 할래'는 REFUSE다(실측 F6, docs/practice-dialogue-standard.md
     * 14절). {@code prompt-v1.txt}는 이력으로 남기고 코드가 읽지 않는다. 판 이름 v2는 다른 작업 브랜치(J1 규칙)가 쓰고 있어 건너뛰었다.
     */
    public static final String RESOURCE = "judgment/prompt-v3.txt";
    /** 지금 판 번호. */
    public static final String DEFAULT_VERSION = "prompt-v3";

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public JudgmentPrompt {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("promptVersion 형식이 올바르지 않다");
        }
        Objects.requireNonNull(instructions, "instructions");
        if (instructions.isBlank()) {
            throw new IllegalArgumentException("판정 지시문이 비었다");
        }
    }

    /** 자원 본문에 선택지 판의 지점별 질문 · 선택지 뜻을 이어 붙인 기본 지시문. */
    public static JudgmentPrompt load(LiveContentResources.JudgmentOptionSet optionSet) {
        Objects.requireNonNull(optionSet, "optionSet");
        StringBuilder text = new StringBuilder(readResource().strip());
        for (LiveContentResources.PointOptions point : optionSet.points().values()) {
            text.append("\n\n[").append(point.point().name()).append("] ").append(point.question());
            for (LiveContentResources.Option option : point.options()) {
                text.append("\n- ").append(option.code()).append(": ").append(option.definition());
            }
        }
        return new JudgmentPrompt(DEFAULT_VERSION, text.toString());
    }

    private static String readResource() {
        try (InputStream in = JudgmentPrompt.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("판정 지시문 자원이 없다: " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "JudgmentPrompt[version=" + version + "]";
    }
}
