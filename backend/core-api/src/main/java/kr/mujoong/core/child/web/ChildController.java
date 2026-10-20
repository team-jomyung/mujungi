package kr.mujoong.core.child.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.child.ChildInput;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.child.ChildProfileService;
import kr.mujoong.core.child.ChildProfileService.ChildView;
import kr.mujoong.core.child.ChildProfileService.StopExpressions;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 03 아이 정보 {@code /api/child} · SET-01 종료 표현 {@code /api/child/stop-expressions} (U2 ChildProfile · 계약 3절 · FR1.4 · FR2.4 ·
 * AC1.3.1 ~ AC1.3.10 · AC2.3.1 ~ AC2.3.6 · R8 · R9 · 편차 (U2-i)).
 *
 * <p>체인 C-GRD(보호자 세션, 변경은 CSRF). 보호자는 세션의 id뿐이고 본문에서 받지 않는다(남의 아이를 고를 수 없다, NFR9). 계정 삭제가
 * 접수됐으면 403. 본문이 JSON 객체가 아니거나 검증에 어긋나면 400 VALIDATION_FAILED(입력값은 응답 · 로그에 없다).
 */
@RestController
public class ChildController {

    private final GuardianRepository guardians;
    private final ChildProfileService children;
    private final JsonCodec json;
    private final kr.mujoong.contract.time.MujoongClock clock;

    public ChildController(GuardianRepository guardians, ChildProfileService children, JsonCodec json, kr.mujoong.contract.time.MujoongClock clock) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.children = Objects.requireNonNull(children, "children");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = clock;
    }

    @GetMapping("/api/child")
    ResponseEntity<ChildView> get(@AuthenticationPrincipal GuardianPrincipal guardian) {
        return ApiResponses.json(200, children.get(GuardianPrincipal.requireActive(guardian, guardians)));
    }

    @PostMapping("/api/child")
    ResponseEntity<ChildView> register(
            @AuthenticationPrincipal GuardianPrincipal guardian, @RequestBody(required = false) String body) {
        UUID guardianId = GuardianPrincipal.requireActive(guardian, guardians);
        ChildRequest request = ApiResponses.readJson(json, body, ChildRequest.class, ErrorCode.VALIDATION_FAILED);
        ChildInput.Valid input = ChildInput.forCreate(request.displayName(), request.birthDate(), request.interests(), clock.now().atZone(kr.mujoong.core.child.BirthDateRules.ZONE).toLocalDate());
        return ApiResponses.json(201, children.register(guardianId, input));
    }

    @PatchMapping("/api/child")
    ResponseEntity<ChildView> update(
            @AuthenticationPrincipal GuardianPrincipal guardian, @RequestBody(required = false) String body) {
        UUID guardianId = GuardianPrincipal.requireActive(guardian, guardians);
        ChildRequest request = ApiResponses.readJson(json, body, ChildRequest.class, ErrorCode.VALIDATION_FAILED);
        return ApiResponses.json(200, children.update(guardianId, request.displayName(), request.birthDate(), request.interests()));
    }

    @GetMapping("/api/child/stop-expressions")
    ResponseEntity<Map<String, Object>> stopExpressions(@AuthenticationPrincipal GuardianPrincipal guardian) {
        StopExpressions expressions = children.stopExpressions(GuardianPrincipal.requireActive(guardian, guardians));
        List<StopExpressionBody> items = expressions.items().stream()
                .map(item -> new StopExpressionBody(item.id(), item.text(), item.sortOrder()))
                .toList();
        // JSON 이름 'default'는 Java 예약어라 순서 있는 지도로 쓴다(stop-expressions-response.schema.json)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("default", expressions.defaultExpression());
        body.put("items", items);
        return ApiResponses.json(200, body);
    }

    @PutMapping("/api/child/stop-expressions")
    ResponseEntity<Void> replaceStopExpressions(
            @AuthenticationPrincipal GuardianPrincipal guardian, @RequestBody(required = false) String body) {
        UUID guardianId = GuardianPrincipal.requireActive(guardian, guardians);
        StopExpressionsRequest request = ApiResponses.readJson(json, body, StopExpressionsRequest.class,
                ErrorCode.VALIDATION_FAILED);
        if (request.items() == null || request.items().stream().anyMatch(Objects::isNull)) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        children.replaceStopExpressions(guardianId, request.items().stream().map(StopExpressionText::text).toList());
        return ResponseEntity.noContent().build();
    }

    /** 03 입력(child-request.schema.json). 이름 · 관심사는 문자열 표현에 내지 않는다. */
    public record ChildRequest(String displayName, String birthDate, List<String> interests) {

        @Override
        public String toString() {
            return "ChildRequest[***]";
        }
    }

    /** 추가 표현 한 칸(stop-expressions-response.schema.json items). */
    public record StopExpressionBody(UUID id, String text, int sortOrder) {
    }

    /** SET-01 저장 요청(stop-expressions-request.schema.json). */
    public record StopExpressionsRequest(List<StopExpressionText> items) {
    }

    /** 표현 한 개. 글은 문자열 표현에 내지 않는다. */
    public record StopExpressionText(String text) {

        @Override
        public String toString() {
            return "StopExpressionText[***]";
        }
    }
}
