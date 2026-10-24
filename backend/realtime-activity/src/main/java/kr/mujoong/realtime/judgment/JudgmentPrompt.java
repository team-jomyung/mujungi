package kr.mujoong.realtime.judgment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import kr.mujoong.realtime.content.LiveContentResources;

/**
 * 판정 질문 지시문과 그 판 번호 (U11 BR1.1, FT-1023-d). 진행 중 판정은 Decisions API {@code choice} 질문 한 개로 묻는다. 지시 본문은 자원
 * 파일 {@value #RESOURCE}에 두고(응답 방식 · "아이의 가장 마지막 말 하나만 판정" 포함), 지점 질문 · 선택지 뜻은 세션에 고정되는 판정 선택지
 * 판에서 꺼낸다(선택지 정의를 두 곳에 두지 않는다). 지시문은 질문 지시문 자리로만 가고 아이 발화 같은 데이터는 섞지 않는다(U9 BR2.5). 판
 * 번호는 {@code LunaRequest.promptVersion} 형식을 따르고 판정 기록의 {@code promptVersion}으로 남는다.
 *
 * @param body 지시 본문(지점 질문을 붙이기 전)
 * @param optionSet 지점 질문 · 선택지 뜻을 꺼낼 선택지 판(없으면 null — 지점 질문 · 선택지 뜻을 붙이지 않는다)
 */
public record JudgmentPrompt(String version, String body, LiveContentResources.JudgmentOptionSet optionSet) {

    /** 지시 본문 자원. 판 번호는 파일 이름과 같다. 이전 판(Responses 출력 형식) 자원은 이력으로 남긴다. */
    public static final String RESOURCE = "judgment/prompt-service-v3.txt";
    /** 지금 판 번호(판정 기록 varchar(50) · LABEL 형식 안). */
    public static final String DEFAULT_VERSION = "prompt-service-v3";

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public JudgmentPrompt {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("promptVersion 형식이 올바르지 않다");
        }
        Objects.requireNonNull(body, "body");
        if (body.isBlank()) {
            throw new IllegalArgumentException("판정 지시문이 비었다");
        }
        body = body.strip();
    }

    /** 선택지 판 없이 지시 본문만 있는 판(시험 · 단순 구성). 질문에 지점 질문 · 선택지 뜻이 붙지 않는다. */
    public JudgmentPrompt(String version, String body) {
        this(version, body, null);
    }

    /** 자원 본문과 선택지 판으로 만든 기본 판. */
    public static JudgmentPrompt load(LiveContentResources.JudgmentOptionSet optionSet) {
        Objects.requireNonNull(optionSet, "optionSet");
        return new JudgmentPrompt(DEFAULT_VERSION, readResource(RESOURCE), optionSet);
    }

    /**
     * 지점 하나의 {@code choice} 질문 지시문: 지시 본문 + 그 지점의 질문(판에 있으면). 선택지 뜻은 지시문에 넣지 않고 선택지
     * {@code description}으로 보낸다({@link #choiceDescription}). 아이 발화 같은 데이터는 넣지 않는다(BR2.5).
     */
    public String instructions(JudgmentPoint point) {
        Objects.requireNonNull(point, "point");
        StringBuilder text = new StringBuilder(body);
        pointOptions(point).ifPresent(options -> text.append("\n\n[").append(point.name()).append("] ").append(options.question()));
        return text.toString();
    }

    /** 선택지 판에 있는 그 지점 선택지의 뜻(없으면 null). */
    public String choiceDescription(JudgmentPoint point, String code) {
        return pointOptions(point).flatMap(options -> options.options().stream()
                .filter(option -> option.code().equals(code)).findFirst())
                .map(LiveContentResources.Option::definition).orElse(null);
    }

    /** 판의 지점 열거({@link LiveContentResources.JudgmentPoint})는 판정 기록 열거와 다른 타입이라 이름으로 맞춘다. */
    private Optional<LiveContentResources.PointOptions> pointOptions(JudgmentPoint point) {
        if (optionSet == null) {
            return Optional.empty();
        }
        return optionSet.points().values().stream().filter(options -> options.point().name().equals(point.name())).findFirst();
    }

    private static String readResource(String resource) {
        try (InputStream in = JudgmentPrompt.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("판정 지시문 자원이 없다: " + resource);
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
