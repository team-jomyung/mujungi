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
 * 판정 지시문과 그 판 번호 (U11 BR1.1). 지시문은 요청 {@code instructions} 자리로만 가고 아이 발화 같은 데이터는 섞지 않는다(U9 BR1.8). 판
 * 번호는 {@code LunaRequest.promptVersion} 형식을 따르고 판정 기록의 {@code promptVersion}으로 남는다. 기본 지시문은 자원 파일
 * {@value #RESOURCE}에 지시 본문을 두고, 지점별 질문 · 선택지 뜻은 세션에 고정되는 판정 선택지 판에서 이어 붙인다(선택지 정의를 두 곳에 두지 않는다).
 * Decisions 경로(FT-1023-d)는 같은 본문 · 같은 판에서 지점 하나의 질문 지시문과 선택지 뜻을 따로 꺼낸다.
 *
 * @param instructions Responses 경로의 지시문(본문 + 모든 지점 질문 · 선택지 뜻)
 * @param body 지점 질문을 붙이기 전 지시 본문(비면 {@code instructions})
 * @param optionSet 지시문을 만든 선택지 판(없으면 null)
 */
public record JudgmentPrompt(String version, String instructions, String body,
        LiveContentResources.JudgmentOptionSet optionSet) {

    /** 지시 본문 자원. 판 번호는 파일 이름의 {@code prompt-v1}이다. */
    public static final String RESOURCE = "judgment/prompt-service-v2.txt";
    /** 지금 판 번호. */
    public static final String DEFAULT_VERSION = "prompt-service-v2";
    /** Decisions 질문에 덧붙이는 응답 방식 자원(FT-1023-d). 판 번호는 {@value #DECISIONS_VERSION}이다. */
    public static final String DECISIONS_RESOURCE = "judgment/decisions-question-v1.txt";
    /** Decisions 덧붙임 판 번호. Decisions 판정 기록의 promptVersion은 {@code <판 번호>.decisions-v1}이다. */
    public static final String DECISIONS_VERSION = "decisions-v1";

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public JudgmentPrompt {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("promptVersion 형식이 올바르지 않다");
        }
        Objects.requireNonNull(instructions, "instructions");
        if (instructions.isBlank()) {
            throw new IllegalArgumentException("판정 지시문이 비었다");
        }
        body = body == null || body.isBlank() ? instructions : body;
    }

    /** 선택지 판 없이 지시문만 있는 판(시험 · 단순 구성). Decisions 질문에는 지점 질문 · 선택지 뜻이 붙지 않는다. */
    public JudgmentPrompt(String version, String instructions) {
        this(version, instructions, instructions, null);
    }

    /** 자원 본문에 선택지 판의 지점별 질문 · 선택지 뜻을 이어 붙인 기본 지시문. */
    public static JudgmentPrompt load(LiveContentResources.JudgmentOptionSet optionSet) {
        Objects.requireNonNull(optionSet, "optionSet");
        String body = readResource(RESOURCE).strip();
        StringBuilder text = new StringBuilder(body);
        for (LiveContentResources.PointOptions point : optionSet.points().values()) {
            text.append("\n\n[").append(point.point().name()).append("] ").append(point.question());
            for (LiveContentResources.Option option : point.options()) {
                text.append("\n- ").append(option.code()).append(": ").append(option.definition());
            }
        }
        return new JudgmentPrompt(DEFAULT_VERSION, text.toString(), body, optionSet);
    }

    /** Decisions 판정 기록의 판 번호(Responses 판과 섞이지 않게, FT-1023-d). */
    public String decisionsVersion() {
        return version + "." + DECISIONS_VERSION;
    }

    /**
     * Decisions {@code choice} 질문 지시문(FT-1023-d). 지시 본문 + 그 지점의 질문(판에 있으면) + 응답 방식 덧붙임이다. 선택지 뜻은 지시문에 넣지 않고
     * 선택지 {@code description}으로 보낸다({@link #choiceDescription}). 아이 발화 같은 데이터는 넣지 않는다(BR2.5).
     */
    public String decisionsInstructions(JudgmentPoint point) {
        Objects.requireNonNull(point, "point");
        StringBuilder text = new StringBuilder(body.strip());
        pointOptions(point).ifPresent(options -> text.append("\n\n[").append(point.name()).append("] ").append(options.question()));
        text.append("\n\n").append(readResource(DECISIONS_RESOURCE).strip());
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
