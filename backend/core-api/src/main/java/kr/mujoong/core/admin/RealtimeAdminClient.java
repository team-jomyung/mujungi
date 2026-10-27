package kr.mujoong.core.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.internalhttp.InternalHttpClient;
import kr.mujoong.core.internalhttp.InternalHttpClient.Call;
import kr.mujoong.core.internalhttp.InternalHttpClient.CallException;
import kr.mujoong.core.internalhttp.InternalHttpClient.Response;
import kr.mujoong.core.internalhttp.InternalHttpClient.Target;
import org.springframework.stereotype.Component;

/**
 * 관리자 조회용 Realtime 내부 호출 (JIRA-280 · contracts/internal-api.yaml {@code /internal/sessions/admin-*}).
 *
 * <p>Core는 Realtime 스키마를 읽지 않으므로(R-F5) 연습 장면 실행 · 도움 기록과 Realtime AI 사용량을 내부 HTTP로 받는다. 재시도 · 차단기는
 * 없고, 응답이 없거나 형식이 틀리면 그 관리자 요청 전체를 503 REALTIME_UNAVAILABLE로 끝낸다(빈 값을 0으로 채우지 않는다). 세션 id는 한 번에
 * {@value #BATCH}개씩 보낸다.
 */
@Component
public class RealtimeAdminClient {

    static final int BATCH = 500;
    static final String FACTS_PATH = "/internal/sessions/admin-facts";
    static final String USAGE_PATH = "/internal/sessions/admin-usage";

    private final InternalHttpClient http;
    private final JsonCodec json;
    private final Duration timeout;

    public RealtimeAdminClient(InternalHttpClient http, JsonCodec json, CoreProperties properties) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.timeout = properties.internalHttp().adminFactsTimeout();
    }

    /** 도움 기록 한 건(분류 코드만, 원문 없음). */
    public record HelpFact(UUID helpId, String kind, String consentAnswer, boolean exampleGiven) {
    }

    /** 장면 실행 한 건. 횟수 · 사실은 Realtime이 저장한 값 그대로다. */
    public record SceneRunFacts(UUID sceneRunId, String sceneKind, String liveTiming, String variantSkipReason,
            Integer retryCount, Integer reexplainCount, List<String> facts, List<HelpFact> helps) {

        public SceneRunFacts {
            facts = facts == null ? List.of() : List.copyOf(facts);
            helps = helps == null ? List.of() : List.copyOf(helps);
        }
    }

    /**
     * 세션 · 출처 · 모델 하나의 Realtime 사용량 합. 표시용 합(voiceSeconds · inputTokens · …)은 필드마다 아는 값을 더한 것이고, 금액 계산용
     * 합({@code measured*})은 측정된 행만 더한 것이다.
     *
     * @param source VOICE(음성 초) · LUNA(진행 중 판정 토큰)
     * @param measuredCalls 금액을 계산할 수 있는 호출 수(음성: 초 있음, 판정: 입력 · 출력 모두 있음)
     * @param knownCalls 값을 하나라도 아는 호출 수
     */
    public record UsageAggregate(UUID sessionId, String source, String model, int calls, int measuredCalls, int knownCalls,
            int completeCalls, Double voiceSeconds, Long inputTokens, Long outputTokens, Long cachedInputTokens,
            Long reasoningTokens, Long measuredInputTokens, Long measuredOutputTokens, Long measuredCachedInputTokens,
            Instant lastObservedAt) {
    }

    record FactsRequest(List<UUID> sessionIds) {
    }

    record FactsResponse(List<SessionFacts> sessions) {
    }

    record SessionFacts(UUID sessionId, List<SceneRunFacts> sceneRuns) {
    }

    record UsageRequest(List<UUID> sessionIds, Instant from, Instant to) {
    }

    record UsageResponse(List<UsageAggregate> usage) {
    }

    /** 세션별 장면 실행. 장면 실행이 없는 세션은 결과에 없다. */
    public Map<UUID, List<SceneRunFacts>> facts(List<UUID> sessionIds) {
        Map<UUID, List<SceneRunFacts>> result = new HashMap<>();
        for (List<UUID> chunk : chunks(sessionIds)) {
            FactsResponse response = call(Target.ADMIN_FACTS, FACTS_PATH, new FactsRequest(chunk), FactsResponse.class);
            if (response.sessions() == null) {
                throw unavailable();
            }
            for (SessionFacts session : response.sessions()) {
                if (session == null || session.sessionId() == null) {
                    throw unavailable();
                }
                result.put(session.sessionId(), session.sceneRuns() == null ? List.of() : List.copyOf(session.sceneRuns()));
            }
        }
        return result;
    }

    /** 세션들의 Realtime 사용량 전체(관측 시각 제한 없음). */
    public List<UsageAggregate> usageOf(List<UUID> sessionIds) {
        List<UsageAggregate> result = new ArrayList<>();
        for (List<UUID> chunk : chunks(sessionIds)) {
            result.addAll(usage(new UsageRequest(chunk, null, null)));
        }
        return result;
    }

    /** 관측 시각이 [from, to)인 Realtime 사용량. */
    public List<UsageAggregate> usageBetween(Instant from, Instant to) {
        return usage(new UsageRequest(null, from, to));
    }

    private List<UsageAggregate> usage(UsageRequest request) {
        UsageResponse response = call(Target.ADMIN_USAGE, USAGE_PATH, request, UsageResponse.class);
        if (response.usage() == null || response.usage().stream().anyMatch(row -> row == null || row.sessionId() == null
                || row.source() == null || row.lastObservedAt() == null)) {
            throw unavailable();
        }
        return response.usage();
    }

    private <T> T call(Target target, String path, Object body, Class<T> type) {
        Response response;
        try {
            response = http.send(target, new Call("POST", path, json.write(body), timeout, 1, null));
        } catch (CallException e) {
            throw unavailable();
        }
        if (!response.isSuccess()) {
            throw unavailable();
        }
        try {
            T parsed = json.read(response.body(), type);
            if (parsed == null) {
                throw unavailable();
            }
            return parsed;
        } catch (JsonCodec.JsonCodecException e) {
            throw unavailable();
        }
    }

    private static List<List<UUID>> chunks(List<UUID> ids) {
        List<List<UUID>> chunks = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += BATCH) {
            chunks.add(List.copyOf(ids.subList(start, Math.min(ids.size(), start + BATCH))));
        }
        return chunks;
    }

    private static CoreApiException unavailable() {
        return CoreApiException.of(ErrorCode.REALTIME_UNAVAILABLE);
    }
}
