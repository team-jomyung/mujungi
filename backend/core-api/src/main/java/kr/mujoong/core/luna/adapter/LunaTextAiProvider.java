package kr.mujoong.core.luna.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.luna.CoreLunaClient;
import kr.mujoong.core.luna.LunaException;
import kr.mujoong.core.luna.LunaFailureReason;
import kr.mujoong.core.luna.LunaPurpose;
import kr.mujoong.core.luna.LunaRequest;
import kr.mujoong.core.luna.LunaResult;
import kr.mujoong.core.textai.GeneratedScenario;
import kr.mujoong.core.textai.ScenarioPrompt;
import kr.mujoong.core.textai.TextAiException;
import kr.mujoong.core.textai.TextAiProvider;
import org.springframework.core.io.ClassPathResource;

/**
 * 기존 {@link TextAiProvider}의 Luna 구현 (U9 FR1.3 · W3 · BR3.4 · BR4.2 · BR7.2 · BR8.1 · SD11).
 *
 * <p>contracts/ai-io.md 7절 상황 생성 지시(실행 자원 {@value #INSTRUCTIONS})와 {@link ScenarioPrompt} 입력을 {@code SCENARIO_GENERATION}
 * 목적 · {@code json_object}로 <b>한 번</b> 부른다(재시도는 바깥 {@code TextAiGateway}가 한다, BR3.4). 길이 · 판 정규식 · 금지 표현 검사는
 * 그대로 게이트웨이와 {@code ScenarioService}가 한다(BR7.2). 출력은 {@link GeneratedScenario}로 읽고 {@code modelVersion} = 응답 모델,
 * {@code promptVersion} = 보낸 판이다. 실패는 BR8.1 결정표로 {@link TextAiException}에 바꾼다.
 *
 * <p>입력에 아이 관심사({@code interests})는 넣지 않는다: Luna 호출부의 금지 키(BR2.1 — 기존 ResultTextGateway PRIVATE_KEYS)라 넣어도
 * 지워진다. 새 상황 스키마로 바꾸는 U10이 관심사 전달 여부를 다시 정한다.
 */
public final class LunaTextAiProvider implements TextAiProvider,kr.mujoong.core.textai.ScenarioScriptProvider {

    /** 상황 생성 지시 실행 자원. */
    public static final String INSTRUCTIONS = "prompts/scenario/generation.v1.txt";
    /** 보낸 프롬프트 판(GeneratedScenario.promptVersion · LUNA_CALL promptVersion). */
    public static final String PROMPT_VERSION = "scenario-generation.v1";

    private final CoreLunaClient client;
    private final MujoongClock clock;
    private final JsonCodec json;
    private final String instructions;
    private final String scriptInstructions;
    private final String reviewInstructions;

    public LunaTextAiProvider(CoreLunaClient client, MujoongClock clock, JsonCodec json) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
        try {
            this.instructions = new ClassPathResource(INSTRUCTIONS).getContentAsString(StandardCharsets.UTF_8);
            this.scriptInstructions = new ClassPathResource("prompts/scenario/generation.v2.txt").getContentAsString(StandardCharsets.UTF_8);
            this.reviewInstructions = new ClassPathResource("prompts/scenario/review.v1.txt").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("상황 생성 지시 자원이 없다: " + INSTRUCTIONS, e);
        }
    }

    @Override public Generated generate(Call request) {
        var result=structured(request,LunaPurpose.SCENARIO_GENERATION,"scenario-generation.v2",scriptInstructions);
        try { return new Generated(json.read(result.outputJson(),kr.mujoong.core.textai.ScenarioScript.class),result.responseModel()); }
        catch(RuntimeException invalid){throw new Failure(Failure.Kind.CONTENT,java.util.Optional.empty());}
    }
    @Override public Reviewed review(Call request) {
        var result=structured(request,LunaPurpose.SCENARIO_REVIEW,"scenario-review.v1",reviewInstructions);
        try { return new Reviewed(json.read(result.outputJson(),kr.mujoong.core.textai.ScenarioReview.class),result.responseModel()); }
        catch(RuntimeException invalid){throw new Failure(Failure.Kind.CONTENT,java.util.Optional.empty());}
    }
    private LunaResult structured(Call request,LunaPurpose purpose,String version,String resource) {
        try {return client.call(new LunaRequest(purpose,version,resource,request.input(),request.aliases(),request.deadline(),LunaRequest.OutputMode.CONTRACT_SINGLE_CALL));}
        catch(LunaException failure) {
            var kind=failure.isTransient()?Failure.Kind.TRANSIENT:switch(failure.reason()) {
                case LENGTH_EXCEEDED,REFUSED,SCHEMA_INVALID,MODEL_MISSING -> Failure.Kind.CONTENT;
                default -> Failure.Kind.PERMANENT;
            };
            throw new Failure(kind,failure.retryAfter());
        }
    }

    @Override
    public GeneratedScenario generateScenario(ScenarioPrompt prompt, Duration timeout) {
        Objects.requireNonNull(prompt, "prompt");
        Instant deadline = clock.now().plus(Objects.requireNonNull(timeout, "timeout"));
        LunaResult result;
        try {
            result = client.call(LunaRequest.legacy(LunaPurpose.SCENARIO_GENERATION, PROMPT_VERSION, instructions,
                    input(prompt), Map.of(), deadline));
        } catch (LunaException failure) {
            throw translate(failure.reason());
        }
        Map<?, ?> output;
        try {
            output = json.read(result.outputJson(), Map.class);
        } catch (RuntimeException e) {
            // 호출부가 JSON 객체임을 확인했으므로 오지 않는 길이다. 와도 출력 형식 오류로 돌린다(원인 메시지 버림, BR6.2)
            throw new TextAiException(TextAiException.Reason.INVALID_OUTPUT);
        }
        return new GeneratedScenario(text(output, "scenarioText"), text(output, "triggerScript"), text(output, "relationType"),
                text(output, "opportunityCondition"), result.responseModel(), result.promptVersion());
    }

    /** BR8.1 결정표. TextAiGateway는 retryable이고 UNAVAILABLE · TIMEOUT일 때만 기술 재시도한다. */
    static TextAiException translate(LunaFailureReason reason) {
        return switch (reason) {
            case TIMEOUT -> new TextAiException(TextAiException.Reason.TIMEOUT, true);
            case TRANSPORT_ERROR, RATE_LIMITED, SERVER_ERROR, EMPTY_OUTPUT ->
                    new TextAiException(TextAiException.Reason.UNAVAILABLE, true);
            case REQUEST_REJECTED, AUTH_FAILED, FORBIDDEN_INPUT -> new TextAiException(TextAiException.Reason.UNAVAILABLE, false);
            case LENGTH_EXCEEDED, REFUSED, SCHEMA_INVALID, MODEL_MISSING ->
                    new TextAiException(TextAiException.Reason.INVALID_OUTPUT, false);
        };
    }

    private static Map<String, Object> input(ScenarioPrompt prompt) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("goalDisplayName", prompt.goalDisplayName());
        input.put("behaviorDefinition", prompt.behaviorDefinition());
        input.put("opportunityCondition", prompt.opportunityCondition());
        input.put("scenarioCondition", prompt.scenarioCondition());
        input.put("relationType", prompt.relationType());
        input.put("age", prompt.age());
        if (prompt.previousScenarioText() != null) {
            input.put("previousScenarioText", prompt.previousScenarioText());
        }
        return input;
    }

    private static String text(Map<?, ?> output, String key) {
        return output.get(key) instanceof String value ? value : null;
    }
}
