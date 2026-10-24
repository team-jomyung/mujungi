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

    /** 지시 본문 자원(연습이 유연 대화 경로일 때). 판 번호는 파일 이름과 같다. 이전 판(Responses 출력 형식) 자원은 이력으로 남긴다. */
    public static final String RESOURCE = "judgment/prompt-service-v3.txt";
    /** 유연 대화 경로의 판 번호(판정 기록 varchar(50) · LABEL 형식 안). 선택지 판 {@code judgment-service-v1}과 짝이다. */
    public static final String DEFAULT_VERSION = "prompt-service-v3";
    /**
     * C안 '무중이 직접 묻기'의 지시 본문 자원. v3을 바탕으로 역할 대화 전제 문단과 J3 {@code ROLE_DIALOGUE_CLOSURE} 단락을 빼고, 무중이 상대 말을 전하고
     * 묻는 틀 · 전하는 식 대답 인정 · 방법만 묻는 말은 HELP를 넣었다(docs/practice-direct-ask.md 6장).
     */
    public static final String DIRECT_ASK_RESOURCE = "judgment/prompt-service-v4.txt";
    /** C안 판 번호. 선택지 판 {@code judgment-service-v2}와 짝이다. */
    public static final String DIRECT_ASK_VERSION = "prompt-service-v4";

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

    /**
     * 자원 본문과 선택지 판으로 만든 판. 지시문 판은 선택지 판과 한 쌍이라 선택지 판을 보고 고른다: C안 선택지 판
     * ({@value LiveContentResources#DIRECT_ASK_JUDGMENT_VERSION})이면 {@value #DIRECT_ASK_VERSION}, 그 밖은 {@value #DEFAULT_VERSION}이다. 어느
     * 쌍을 쓸지는 기동 때 연습 경로 설정이 선택지 판으로 정한다({@link LiveContentResources#loadFor}).
     */
    public static JudgmentPrompt load(LiveContentResources.JudgmentOptionSet optionSet) {
        Objects.requireNonNull(optionSet, "optionSet");
        if (LiveContentResources.DIRECT_ASK_JUDGMENT_VERSION.equals(optionSet.version())) {
            return new JudgmentPrompt(DIRECT_ASK_VERSION, readResource(DIRECT_ASK_RESOURCE), optionSet);
        }
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
