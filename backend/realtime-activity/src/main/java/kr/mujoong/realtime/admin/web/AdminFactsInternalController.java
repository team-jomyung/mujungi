package kr.mujoong.realtime.admin.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.realtime.admin.AdminFactsRepository;
import kr.mujoong.realtime.admin.AdminFactsRepository.SceneRun;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.web.InternalBody;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 조회용 Realtime 내부 API (JIRA-280 · contracts/internal-api.yaml {@code /internal/sessions/admin-*}).
 *
 * <p>C-INT 체인(내부 토큰) 뒤에서만 닿고 Core 관리자 API만 부른다. 읽기 전용이지만 세션 id 목록을 본문으로 받으려고 POST를 쓴다. 원문 · 개인정보는
 * 돌려주지 않는다(분류 코드 · 횟수 · 토큰 · 초 · 시각만). 형식이 틀리면 400 VALIDATION_FAILED다.
 *
 * <ul>
 *   <li>{@code POST /internal/sessions/admin-facts} {sessionIds 1 ~ 500}: 세션별 연습 장면 실행 · 사실 · 도움 기록.
 *   <li>{@code POST /internal/sessions/admin-usage} {sessionIds 1 ~ 500} 또는 {from, to}: 세션 · 출처 · 모델별 AI 사용량 합.
 * </ul>
 */
@RestController
@RequestMapping("/internal/sessions")
public class AdminFactsInternalController {

    /** 한 요청의 최대 세션 수. */
    static final int MAX_SESSIONS = 500;

    private final AdminFactsRepository facts;
    private final JsonCodec json;
    private final long bodyMax;

    public AdminFactsInternalController(AdminFactsRepository facts, JsonCodec json, RealtimeProperties properties) {
        this.facts = Objects.requireNonNull(facts, "facts");
        this.json = Objects.requireNonNull(json, "json");
        this.bodyMax = properties.limits().internalEventBodyMax().toBytes();
    }

    record FactsBody(List<UUID> sessionIds) {
    }

    record UsageBody(List<UUID> sessionIds, Instant from, Instant to) {
    }

    record SessionFacts(UUID sessionId, List<SceneRun> sceneRuns) {
    }

    @PostMapping("/admin-facts")
    ResponseEntity<String> facts(HttpServletRequest request) {
        FactsBody body = InternalBody.parse(json, InternalBody.read(request, bodyMax, ErrorCode.VALIDATION_FAILED),
                FactsBody.class, ErrorCode.VALIDATION_FAILED);
        List<UUID> ids = sessionIds(body.sessionIds());
        List<SessionFacts> sessions = new ArrayList<>();
        facts.sceneRuns(ids).forEach((sessionId, runs) -> sessions.add(new SessionFacts(sessionId, runs)));
        return json(Map.of("sessions", sessions));
    }

    @PostMapping("/admin-usage")
    ResponseEntity<String> usage(HttpServletRequest request) {
        UsageBody body = InternalBody.parse(json, InternalBody.read(request, bodyMax, ErrorCode.VALIDATION_FAILED),
                UsageBody.class, ErrorCode.VALIDATION_FAILED);
        boolean byRange = body.from() != null || body.to() != null;
        InternalBody.check(byRange != (body.sessionIds() != null), "sessionIds");
        if (byRange) {
            InternalBody.check(body.from() != null && body.to() != null && body.from().isBefore(body.to()), "from");
            return json(Map.of("usage", facts.usageBetween(body.from(), body.to())));
        }
        return json(Map.of("usage", facts.usageOf(sessionIds(body.sessionIds()))));
    }

    private static List<UUID> sessionIds(List<UUID> values) {
        InternalBody.check(values != null && !values.isEmpty() && values.size() <= MAX_SESSIONS, "sessionIds");
        InternalBody.check(values.stream().allMatch(Objects::nonNull), "sessionIds");
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private ResponseEntity<String> json(Object body) {
        return ResponseEntity.status(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(json.write(body));
    }
}
