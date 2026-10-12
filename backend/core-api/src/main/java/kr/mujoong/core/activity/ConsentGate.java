package kr.mujoong.core.activity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.consent.ConsentStatusRule;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.identity.ConsentRepository;
import org.springframework.stereotype.Component;

/**
 * 시작 전 동의 확인 (U1 3.5 Step 22 · BR6.1 ① · J114 · AC3.1.6, U2가 보호자 기준 판정으로 바꿈 · U2-J10).
 *
 * <p>현재 정책 버전(CONSENT_POLICY APPROVED 최고 버전)의 4항목(VOICE_AND_TEXT · GUARDIAN_VIEW · REPORT_SHARING · RAW_AUDIO)이 그 시각에
 * 모두 GIVEN이어야 통과다. 동의는 아이 등록(03)보다 먼저라 동의 행은 아이가 아니라 동의한 보호자(actor_guardian_id)로 찾고, 판정은 U2
 * {@link ConsentStatusRule}(상태 없는 규칙)이 한다. 아이 행이 없거나 정책 버전을 읽지 못하면 통과시키지 않는다(fail-closed). 통과하면
 * 패키지의 consentSnapshot에 쓸 정책 버전을 돌려준다. 계약 8절 ConsentGate 포트(보호자 id 입력)는 U2 ConsentPolicyService다.
 */
@Component
public class ConsentGate {

    private final ConsentRepository consents;
    private final ChildRepository children;
    private final ApprovedContentCatalog catalog;

    public ConsentGate(ConsentRepository consents, ChildRepository children, ApprovedContentCatalog catalog) {
        this.consents = Objects.requireNonNull(consents, "consents");
        this.children = Objects.requireNonNull(children, "children");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * 확인한다.
     *
     * @return 통과면 현재 정책 버전, 아니면 빈 값(403 CONSENT_MISSING)
     */
    public Optional<String> check(UUID childId, Instant at) {
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(at, "at");
        Optional<UUID> guardianId = children.findById(childId).map(child -> child.getGuardianId());
        if (guardianId.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> policyVersion = catalog.currentPolicyVersion();
        if (policyVersion.isEmpty()) {
            return Optional.empty();
        }
        boolean ok = ConsentStatusRule.evaluate(consents.findByActorGuardianId(guardianId.get()), policyVersion, at).ok();
        return ok ? policyVersion : Optional.empty();
    }
}
