package kr.mujoong.core.child;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.child.ChildInput.Valid;
import kr.mujoong.core.child.StopExpressionRepository.StoredExpression;
import kr.mujoong.core.common.db.SqlErrors;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.Child;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.identity.GuardianWriteLock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아이 정보 · 종료 표현 (U2 ChildProfile · FR1.4 · FR2.4 · AC1.3.1 ~ AC1.3.9 · AC2.3.4 ~ AC2.3.6 · R8 · R9 · 계약 8절 ChildContext).
 *
 * <ul>
 *   <li>계정당 아이 하나(core_child.guardian_id 유일). 두 번째 등록은 409 CHILD_EXISTS, 없는 아이는 404 CHILD_NOT_REGISTERED.
 *   <li>수정은 같은 행을 바꾼다(childId · 보호자 그대로, 기존 활동 · 기록의 대상 아이가 바뀌지 않음, AC1.3.9).
 *   <li>종료 표현은 통째 교체. 저장한 표현은 다음 세션 패키지부터 쓰인다(AC2.3.7, U4가 포트로 읽음).
 *   <li>등록 · 수정 · 교체는 트랜잭션 첫머리에 보호자 행을 잠그고 ACTIVE인지 다시 확인한다 — 계정 삭제가 접수됐으면 403
 *       ACCOUNT_DELETION_IN_PROGRESS({@link GuardianWriteLock}, 리뷰 9).
 * </ul>
 */
@Component
public class ChildProfileService {

    private final ChildRepository children;
    private final StopExpressionRepository expressions;
    private final GuardianWriteLock guardianLock;
    private final TransactionTemplate transactions;
    private final JsonCodec json;
    private final MujoongClock clock;
    private java.util.function.BiConsumer<UUID, UUID> knowledgeInitializer = (guardian, child) -> {};

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void connectKnowledge(kr.mujoong.core.knowledge.ChildKnowledgeService knowledge) {
        knowledgeInitializer = knowledge::requestInitialization;
    }

    public ChildProfileService(
            ChildRepository children,
            StopExpressionRepository expressions,
            GuardianWriteLock guardianLock,
            PlatformTransactionManager transactionManager,
            JsonCodec json,
            MujoongClock clock) {
        this.children = Objects.requireNonNull(children, "children");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
        this.guardianLock = Objects.requireNonNull(guardianLock, "guardianLock");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 아이 정보(응답 모양 child-response.schema.json). */
    public record ChildView(UUID childId, String displayName, int age, List<String> interests, LocalDate birthDate, LocalDate registeredOn) {

        public ChildView {
            interests = List.copyOf(interests);
        }

        /** 이름 · 관심사는 문자열 표현에 내지 않는다(BR8.7). */
        @Override
        public String toString() {
            return "ChildView[childId=" + childId + "]";
        }
    }

    /** 보호자의 아이(없으면 빈 값). */
    public Optional<ChildView> find(UUID guardianId) {
        return activeChild(guardianId).map(this::view);
    }

    /** 보호자의 아이(없으면 404 CHILD_NOT_REGISTERED). */
    public ChildView get(UUID guardianId) {
        return find(guardianId).orElseThrow(() -> CoreApiException.of(ErrorCode.CHILD_NOT_REGISTERED));
    }

    /** 03 처음 등록. */
    public ChildView register(UUID guardianId, Valid input) {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(input, "input");
        try {
            ChildView registered = transactions.execute(status -> {
                guardianLock.lockActive(guardianId);
                if (children.findByGuardianId(guardianId).isPresent()) {
                    throw CoreApiException.of(ErrorCode.CHILD_EXISTS);
                }
                var now = clock.now();
                var registeredOn = now.atZone(BirthDateRules.ZONE).toLocalDate();
                BirthDateRules.requireEligible(input.birthDate(), registeredOn);
                Child child = new Child(UUID.randomUUID(), guardianId, input.displayName(), BirthDateRules.ageOn(input.birthDate(), registeredOn),
                        json.write(input.interests()), now);
                child.setBirthDate(input.birthDate());
                children.saveAndFlush(child);
                return view(child);
            });
            knowledgeInitializer.accept(guardianId, registered.childId());
            return registered;
        } catch (DataIntegrityViolationException e) {
            // 같은 보호자의 동시 등록: guardian_id 유일 제약이 하나만 남긴다
            if (SqlErrors.isUniqueViolation(e)) {
                throw CoreApiException.of(ErrorCode.CHILD_EXISTS);
            }
            throw e;
        }
    }

    /** 03 수정(온 값만). */
    public ChildView update(UUID guardianId, String displayName, String birthDate, List<String> interests) {
        Objects.requireNonNull(guardianId, "guardianId");
        return transactions.execute(status -> {
            guardianLock.lockActive(guardianId);
            Child child = activeChild(guardianId).orElseThrow(() -> CoreApiException.of(ErrorCode.CHILD_NOT_REGISTERED));
            ChildView current = view(child);
            Valid next = ChildInput.forUpdate(new Valid(current.displayName(), current.birthDate(), current.interests()),
                    displayName, birthDate, interests, child.getCreatedAt().atZone(BirthDateRules.ZONE).toLocalDate());
            child.setBirthDate(next.birthDate());
            child.update(next.displayName(), next.birthDate() == null ? child.getAge() : BirthDateRules.ageOn(next.birthDate(), clock.now().atZone(BirthDateRules.ZONE).toLocalDate()), json.write(next.interests()), clock.now());
            children.saveAndFlush(child);
            return view(child);
        });
    }

    /** 종료 표현 목록(기본 + 추가). */
    public record StopExpressions(String defaultExpression, List<StoredExpression> items) {

        public StopExpressions {
            items = List.copyOf(items);
        }
    }

    /** 종료 표현(아이가 없으면 404). */
    public StopExpressions stopExpressions(UUID guardianId) {
        Child child = activeChild(guardianId).orElseThrow(() -> CoreApiException.of(ErrorCode.CHILD_NOT_REGISTERED));
        return new StopExpressions(StopExpressionRules.DEFAULT_EXPRESSION, expressions.findByChild(child.getId()));
    }

    /** 종료 표현 통째 교체(아이가 없으면 404, 검증 실패 400). */
    public void replaceStopExpressions(UUID guardianId, List<String> texts) {
        List<StopExpressionRules.Expression> valid = StopExpressionRules.validate(texts);
        transactions.executeWithoutResult(status -> {
            // 보호자 행 잠금이 같은 보호자의 동시 교체도 줄 세운다(아이는 보호자당 하나)
            guardianLock.lockActive(guardianId);
            Child child = activeChild(guardianId).orElseThrow(() -> CoreApiException.of(ErrorCode.CHILD_NOT_REGISTERED));
            expressions.replaceAll(child.getId(), valid, clock.now());
        });
    }

    /** 세션 시작용 최소 아이 문맥(계약 8절 ChildContext.get). 종료 표현은 기본이 맨 앞이다. */
    public record ChildContext(
            UUID childId, String displayName, int age, List<String> interests, List<StopExpression> stopExpressions) {

        public ChildContext {
            interests = List.copyOf(interests);
            stopExpressions = List.copyOf(stopExpressions);
        }

        /** 이름 · 관심사 · 표현은 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "ChildContext[childId=" + childId + "]";
        }
    }

    /** 세션 패키지의 종료 표현 한 칸(계약 5절 stopExpressions). */
    public record StopExpression(String text, boolean isDefault) {
    }

    /** ChildContext 포트(U4 ActivityCoordinator · U6 Report가 부른다). 아이가 없으면 빈 값. */
    public Optional<ChildContext> childContext(UUID guardianId) {
        return activeChild(guardianId).map(child -> {
            ChildView view = view(child);
            List<StopExpression> stops = new ArrayList<>();
            stops.add(new StopExpression(StopExpressionRules.DEFAULT_EXPRESSION, true));
            expressions.findByChild(child.getId()).forEach(stored -> stops.add(new StopExpression(stored.text(), false)));
            return new ChildContext(view.childId(), view.displayName(), view.age(), view.interests(), stops);
        });
    }

    private Optional<Child> activeChild(UUID guardianId) {
        return children.findByGuardianId(Objects.requireNonNull(guardianId, "guardianId"))
                .filter(child -> Child.STATUS_ACTIVE.equals(child.getStatus()));
    }

    private ChildView view(Child child) {
        String[] interests = child.getInterestsJson() == null ? new String[0] : json.read(child.getInterestsJson(), String[].class);
        // 저장 값에 null 칸이 있어도(U1 시드 · 손으로 넣은 행) 조회가 깨지지 않게 뺀다(U4a — 세션 시작이 이 포트를 쓴다)
        return new ChildView(child.getId(), child.getDisplayName(), child.getBirthDate() == null ? (child.getAge() == null ? 0 : child.getAge()) : BirthDateRules.ageOn(child.getBirthDate(), clock.now().atZone(BirthDateRules.ZONE).toLocalDate()),
                Arrays.stream(interests).filter(Objects::nonNull).toList(), child.getBirthDate(), child.getCreatedAt().atZone(BirthDateRules.ZONE).toLocalDate());
    }
}
