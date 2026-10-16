package kr.mujoong.core.luna.adapter;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.luna.CoreLunaClient;
import kr.mujoong.core.luna.LunaException;
import kr.mujoong.core.luna.LunaFailureReason;
import kr.mujoong.core.luna.LunaPurpose;
import kr.mujoong.core.luna.LunaRequest;
import kr.mujoong.core.result.ResultAiException;
import kr.mujoong.core.result.ResultTextProvider;

/**
 * 기존 {@link ResultTextProvider}의 Luna 구현 (U9 FR1.3 · W3 · BR1.8 · BR3.4 · BR7.2 · BR8.2 · SD11 · AC2.4.2 · AC2.4.3).
 *
 * <p>{@code ResultTextGateway}가 정리한 입력(식별자 키 제거 · UUID → '참조N' · 연락처 · 별칭)과 {@code prompts/result/{task}.{판}.txt}
 * 지시를 {@code RESULT_TEXT} 목적 · {@code json_object}로 한 번 부르고 출력 문자열만 돌려준다. 참조 복원 · {@code ResultAiSchema} 검증 ·
 * 인용 대조 · {@code OutputChecker}(1차 사전 · classify)는 바깥에서 그대로 돈다(BR7.2). {@code request.temperature}는 받기만 하고 보내지
 * 않는다(BR1.8). 실패는 BR8.2 결정표로 {@link ResultAiException}에 바꾼다 — 결과 작업은 어느 사유든 시도 실패로 세고 작업 재시도로 다시 한다.
 */
public final class LunaResultTextProvider implements ResultTextProvider {

    private final CoreLunaClient client;
    private final MujoongClock clock;

    public LunaResultTextProvider(CoreLunaClient client, MujoongClock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String generate(Request request, Duration timeout) {
        Objects.requireNonNull(request, "request");
        LunaRequest lunaRequest = LunaRequest.legacy(LunaPurpose.RESULT_TEXT, request.promptVersion(), request.instructions(),
                request.input(), Map.of(), clock.now().plus(Objects.requireNonNull(timeout, "timeout")));
        try {
            return client.call(lunaRequest).outputJson();
        } catch (LunaException failure) {
            throw new ResultAiException(translate(failure.reason()));
        }
    }

    /** BR8.2 결정표(빈 출력은 지금 게이트웨이처럼 SCHEMA_INVALID). */
    static String translate(LunaFailureReason reason) {
        return switch (reason) {
            case TIMEOUT -> "TIMEOUT";
            case EMPTY_OUTPUT, LENGTH_EXCEEDED, REFUSED, SCHEMA_INVALID -> "SCHEMA_INVALID";
            case TRANSPORT_ERROR, RATE_LIMITED, SERVER_ERROR, REQUEST_REJECTED, AUTH_FAILED, MODEL_MISSING -> "PROVIDER_ERROR";
            case FORBIDDEN_INPUT -> "INTERNAL";
        };
    }
}
