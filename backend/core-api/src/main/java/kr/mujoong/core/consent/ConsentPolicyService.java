package kr.mujoong.core.consent;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentStatusRule.Evaluation;
import kr.mujoong.core.consent.ConsentStatusRule.Status;
import kr.mujoong.core.consent.ConsentStatusRule.Type;
import kr.mujoong.core.consent.PolicyTermsReader.CurrentPolicy;
import kr.mujoong.core.identity.Child;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.identity.Consent;
import kr.mujoong.core.identity.ConsentRepository;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 동의 정책 (U2 ConsentPolicy · FR1.2 · AC1.2.4 · AC1.2.8 · R6 · 계약 8절 ConsentGate 포트 · 편차 (U2-f)(U2-g)).
 *
 * <ul>
 *   <li>상태: 보호자의 동의 행을 {@link ConsentStatusRule}로 판정한다(NOT_GIVEN · GIVEN · OUTDATED).
 *   <li>저장: 4항목이 한 번씩, 모두 agreed=true, 정책 버전이 현재 버전일 때만 받는다(어긋나면 400). 이미 현재 버전 4항목이 GIVEN이면 아무것도
 *       쓰지 않는다. 아니면 항목마다 consent_version을 올린 새 GIVEN 행(효력 시작 = 지금, 아이가 있으면 그 아이, 약관 본문 버전)을 쓴다.
 *       보호자 행을 잠가 같은 보호자의 동시 저장 · 계정 삭제 접수와 줄 세우고, 잠근 행이 ACTIVE가 아니면 403
 *       ACCOUNT_DELETION_IN_PROGRESS다({@link GuardianWriteLock}, 리뷰 9). 변경 · 철회 API는 없다(철회 = 계정 삭제, AC1.2.8).
 * </ul>
 */
@Component
public class ConsentPolicyService {

    private final ConsentRepository consents;
    private final ChildRepository children;
    private final PolicyTermsReader terms;
    private final GuardianWriteLock guardianLock;
    private final TransactionTemplate transactions;
    private final MujoongClock clock;

    public ConsentPolicyService(
            ConsentRepository consents,
            ChildRepository children,
            PolicyTermsReader terms,
            GuardianWriteLock guardianLock,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this.consents = Objects.requireNonNull(consents, "consents");
        this.children = Objects.requireNonNull(children, "children");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.guardianLock = Objects.requireNonNull(guardianLock, "guardianLock");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 현재 정책 버전 기준 상태(정책 버전을 모르면 NOT_GIVEN · 빈 버전). */
    public record ConsentState(Evaluation evaluation, Optional<String> policyVersion) {

        public Status status() {
            return evaluation.status();
        }
    }

    /** 보호자의 동의 상태. */
    public ConsentState state(UUID guardianId) {
        Objects.requireNonNull(guardianId, "guardianId");
        Optional<String> policyVersion = terms.currentPolicyVersion();
        Evaluation evaluation = ConsentStatusRule.evaluate(consents.findByActorGuardianId(guardianId), policyVersion,
                clock.now());
        return new ConsentState(evaluation, policyVersion);
    }

    /** 세션 패키지 consentSnapshot 모양(계약 5절 · 8절). */
    public record Snapshot(
            String policyVersion, boolean voiceAndText, boolean guardianView, boolean reportSharing, boolean rawAudio) {
    }

    /** 시작 게이트 응답(계약 8절 ConsentGate.check). */
    public record GateResult(boolean ok, Status status, Snapshot snapshot) {
    }

    /** 시작 게이트(U4 · U5가 부르는 포트). 통과가 아니어도 상태와 항목별 값을 준다. */
    public GateResult check(UUID guardianId) {
        ConsentState state = state(guardianId);
        Map<Type, Boolean> given = state.evaluation().givenByType();
        Snapshot snapshot = new Snapshot(state.policyVersion().orElse(null), given.get(Type.VOICE_AND_TEXT),
                given.get(Type.GUARDIAN_VIEW), given.get(Type.REPORT_SHARING), given.get(Type.RAW_AUDIO));
        return new GateResult(state.evaluation().ok(), state.status(), snapshot);
    }

    /** 저장 요청 항목 하나(type은 선 형식 문자열, 모르면 400). */
    public record Agreement(String type, Boolean agreed) {
    }

    /**
     * 4항목 동의를 저장한다.
     *
     * @return 새로 쓴 행 수(이미 GIVEN이면 0)
     * @throws CoreApiException VALIDATION_FAILED(4개 아님 · 중복 · 모르는 항목 · false · 현재 버전 아님), INTERNAL_ERROR(약관 없음)
     */
    public int give(UUID guardianId, String policyVersion, Collection<Agreement> agreements) {
        Objects.requireNonNull(guardianId, "guardianId");
        validate(agreements);
        CurrentPolicy current = terms.current().orElseThrow(() -> new IllegalStateException("현재 약관이 카탈로그에 없다"));
        if (policyVersion == null || !policyVersion.equals(current.policyVersion())) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        Integer written = transactions.execute(status -> {
            guardianLock.lockActive(guardianId);
            Instant now = clock.now();
            List<Consent> existing = consents.findByActorGuardianId(guardianId);
            if (ConsentStatusRule.evaluate(existing, Optional.of(current.policyVersion()), now).ok()) {
                return 0;
            }
            UUID childId = children.findByGuardianId(guardianId).map(Child::getId).orElse(null);
            for (Type type : Type.values()) {
                int nextVersion = existing.stream().filter(row -> type.name().equals(row.getConsentType()))
                        .mapToInt(Consent::getConsentVersion).max().orElse(0) + 1;
                consents.save(new Consent(UUID.randomUUID(), guardianId, childId, type.name(), nextVersion,
                        current.policyVersion(), current.termsVersionId(type), now));
            }
            consents.flush();
            return Type.values().length;
        });
        return written == null ? 0 : written;
    }

    private static void validate(Collection<Agreement> agreements) {
        if (agreements == null || agreements.size() != Type.values().length) {
            throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
        }
        Set<Type> seen = EnumSet.noneOf(Type.class);
        for (Agreement agreement : agreements) {
            if (agreement == null || !Boolean.TRUE.equals(agreement.agreed())) {
                throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
            }
            Type type = Type.fromWire(agreement.type()).orElseThrow(() -> CoreApiException.of(ErrorCode.VALIDATION_FAILED));
            if (!seen.add(type)) {
                throw CoreApiException.of(ErrorCode.VALIDATION_FAILED);
            }
        }
    }

}
