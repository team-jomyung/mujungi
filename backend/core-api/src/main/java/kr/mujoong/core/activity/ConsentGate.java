package kr.mujoong.core.activity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.identity.ConsentRepository;
import org.springframework.stereotype.Component;

/**
 * 시작 전 동의 확인 (U1 3.5 Step 22 · BR6.1 ① · J114 · AC3.1.6).
 *
 * <p>현재 정책 버전(CONSENT_POLICY APPROVED 최고 버전)의 4항목(VOICE_AND_TEXT · GUARDIAN_VIEW · REPORT_SHARING · RAW_AUDIO)이 그 시각에
 * 모두 GIVEN이어야 통과다. 정책 버전을 읽지 못하면 통과시키지 않는다(fail-closed). 통과하면 패키지의 consentSnapshot에 쓸 정책 버전을
 * 돌려준다. 동의 변경 로직은 U2다.
 */
@Component
public class ConsentGate {

    private final ConsentRepository consents;
    private final ApprovedContentCatalog catalog;

    public ConsentGate(ConsentRepository consents, ApprovedContentCatalog catalog) {
        this.consents = Objects.requireNonNull(consents, "consents");
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
        Optional<String> policyVersion = catalog.currentPolicyVersion();
        if (policyVersion.isEmpty() || !consents.hasAllGiven(childId, policyVersion.get(), at)) {
            return Optional.empty();
        }
        return policyVersion;
    }
}
