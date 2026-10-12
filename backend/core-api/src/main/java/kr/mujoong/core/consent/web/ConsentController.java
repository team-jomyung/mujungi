package kr.mujoong.core.consent.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.consent.ConsentPolicyService.Agreement;
import kr.mujoong.core.consent.ConsentPolicyService.ConsentState;
import kr.mujoong.core.consent.ConsentStatusRule.Type;
import kr.mujoong.core.consent.PolicyTermsReader;
import kr.mujoong.core.consent.PolicyTermsReader.CurrentPolicy;
import kr.mujoong.core.consent.PolicyTermsReader.PolicyItem;
import kr.mujoong.core.consent.PolicyTermsReader.Section;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 02 · 02-1 약관 {@code GET /api/policies/current} · 동의 {@code GET · PUT /api/consents} (U2 ConsentPolicy · 계약 3절 · FR1.2 · FR1.3 ·
 * AC1.2.1 ~ AC1.2.4 · AC1.2.9 · R6 · R7 · 편차 (U2-b)(U2-c)(U2-f)(U2-g)).
 *
 * <p>모두 체인 C-GRD(보호자 세션, PUT은 CSRF)이고 계정 삭제가 접수됐으면 403. 약관이 카탈로그에 없으면 500(설정 오류, 시드 002 확인).
 */
@RestController
public class ConsentController {

    private final GuardianRepository guardians;
    private final ConsentPolicyService consents;
    private final PolicyTermsReader terms;
    private final JsonCodec json;

    public ConsentController(
            GuardianRepository guardians, ConsentPolicyService consents, PolicyTermsReader terms, JsonCodec json) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.consents = Objects.requireNonNull(consents, "consents");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.json = Objects.requireNonNull(json, "json");
    }

    @GetMapping("/api/policies/current")
    ResponseEntity<PolicyBody> policy(@AuthenticationPrincipal GuardianPrincipal guardian) {
        GuardianPrincipal.requireActive(guardian, guardians);
        CurrentPolicy current = terms.current().orElseThrow(() -> new IllegalStateException("현재 약관이 카탈로그에 없다"));
        List<PolicyItemBody> items = current.items().stream().map(ConsentController::body).toList();
        return ApiResponses.json(200, new PolicyBody(current.policyVersion(), items));
    }

    @GetMapping("/api/consents")
    ResponseEntity<ConsentsBody> consents(@AuthenticationPrincipal GuardianPrincipal guardian) {
        UUID guardianId = GuardianPrincipal.requireActive(guardian, guardians);
        ConsentState state = consents.state(guardianId);
        String policyVersion = state.policyVersion().orElseThrow(() -> new IllegalStateException("현재 정책 버전이 없다"));
        Map<Type, Boolean> given = state.evaluation().givenByType();
        List<ConsentItemBody> items = new ArrayList<>();
        for (Type type : Type.values()) {
            items.add(new ConsentItemBody(type.name(), Boolean.TRUE.equals(given.get(type)) ? "GIVEN" : "NOT_GIVEN"));
        }
        return ApiResponses.json(200, new ConsentsBody(state.status().name(), policyVersion, items));
    }

    @PutMapping("/api/consents")
    ResponseEntity<Void> give(
            @AuthenticationPrincipal GuardianPrincipal guardian, @RequestBody(required = false) String body) {
        UUID guardianId = GuardianPrincipal.requireActive(guardian, guardians);
        GiveRequest request = ApiResponses.readJson(json, body, GiveRequest.class, ErrorCode.VALIDATION_FAILED);
        consents.give(guardianId, request.policyVersion(), request.items());
        return ResponseEntity.noContent().build();
    }

    /** 약관 응답(policy-current-response.schema.json). */
    public record PolicyBody(String policyVersion, List<PolicyItemBody> items) {
    }

    /** 약관 항목. */
    public record PolicyItemBody(String type, String title, List<Section> sections) {
    }

    /** 동의 상태 응답(consents-response.schema.json). */
    public record ConsentsBody(String status, String policyVersion, List<ConsentItemBody> items) {
    }

    /** 항목별 상태. */
    public record ConsentItemBody(String type, String status) {
    }

    /** 저장 요청(consents-request.schema.json). */
    public record GiveRequest(String policyVersion, List<Agreement> items) {
    }

    /** {@link PolicyItem}은 본문 버전 id를 담지만 응답에는 내지 않는다(type · title · sections만). */
    static PolicyItemBody body(PolicyItem item) {
        return new PolicyItemBody(item.type().name(), item.title(), item.sections());
    }
}
