package kr.mujoong.core.knowledge;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.luna.CoreLunaClient;
import kr.mujoong.core.luna.LunaCallBudget;
import kr.mujoong.core.luna.LunaPurpose;
import kr.mujoong.core.luna.LunaRequest;
import kr.mujoong.core.luna.LunaResult;
import kr.mujoong.core.usage.AiUsageLink;
import kr.mujoong.core.usage.AiUsageScope;

/** BR4: 모델 호출 동안 DB 잠금을 풀고 T2 핸들을 확정한 뒤 T3에서만 투영한다. */
public class SeedGenerationService {
    public record Request(UUID guardianId, UUID childId, UUID activityId, String candidateRef,
            String skillRef, StructureSignature structure) {
        public Request {
            if (guardianId == null || childId == null || activityId == null || structure == null
                    || candidateRef == null || candidateRef.isBlank() || candidateRef.length() > 160) {
                throw new IllegalArgumentException("Invalid generation request");
            }
        }
        @Override public String toString() { return "SeedGenerationRequest[redacted]"; }
    }
    public record Result(String seedId, String reason) {
        static Result none(String reason) { return new Result("NONE", reason); }
    }
    private final ChildKnowledgeService knowledge;
    private final Optional<CoreLunaClient> luna;
    private final SeedContentValidator validator;
    private final boolean enabled;
    private final Duration budget;
    private final String prompt;
    private final JsonCodec json = JsonCodec.shared();

    public SeedGenerationService(ChildKnowledgeService knowledge, Optional<CoreLunaClient> luna,
            SeedContentValidator validator, boolean enabled, Duration budget, String prompt) {
        this.knowledge = knowledge;
        this.luna = luna;
        this.validator = validator;
        this.enabled = enabled;
        this.budget = budget;
        this.prompt = prompt;
    }

    public Result generateSeed(Request request) {
        // 시드는 이 활동의 결과에서 나온 후보로 만든다. 사용량은 이 활동에 붙이고, 목적(SEED_GENERATION)으로 따로 집계한다(JIRA-277)
        return AiUsageScope.call(new AiUsageLink(request.guardianId(), request.activityId()), () -> generateSeedInScope(request));
    }

    private Result generateSeedInScope(Request request) {
        if (!enabled || !knowledge.enabled() || luna.isEmpty() || !"NONE".equals(request.skillRef())) return Result.none("DISABLED_OR_INELIGIBLE");
        List<KnowledgeSeed> catalog = knowledge.seedSummaries(request.guardianId(), request.childId());
        if (catalog.isEmpty()) return Result.none("CATALOG_UNAVAILABLE");
        Instant deadline = knowledge.clock.now().plus(budget);
        KnowledgeLedgerRepository.Operation owned = null;
        try {
            var reservation = knowledge.transaction(() -> {
                eligible(request, deadline);
                knowledge.recover(request.childId());
                return knowledge.ledger.reserve(request.childId(), request.activityId(), request.candidateRef(), deadline);
            });
            if (reservation == null) return Result.none("LIMIT");
            var operation = reservation.operation();
            if (!operation.activityId().equals(request.activityId())) return Result.none("CANDIDATE_SOURCE_MISMATCH");
            if (!reservation.created()) return replay(request, operation, catalog);
            owned = operation;
            return generate(request, operation, catalog);
        } catch (RuntimeException e) {
            // BR4: 실패 사유는 고정 코드로만 반환한다. PROJECTING은 성공으로 승격하지 않는다.
            if (owned != null) finishReserved(request, owned);
            return Result.none("GENERATION_FAILED");
        }
    }

    private Result replay(Request request, KnowledgeLedgerRepository.Operation op, List<KnowledgeSeed> catalog) {
        if ("COMMITTED".equals(op.state()) || "REUSED".equals(op.state())) {
            String id = "REUSED".equals(op.state()) ? op.reusedSeedId() : op.seedId();
            return catalog.stream().anyMatch(s -> s.seedId().equals(id)) ? new Result(id, "REPLAYED") : Result.none("REFERENCE_GONE");
        }
        if ("RESERVED".equals(op.state()) && !knowledge.clock.now().isBefore(op.deadline())) {
            knowledge.transaction(() -> { knowledge.ledger.finish(op, null); return null; });
        }
        return Result.none(op.state());
    }

    private Result generate(Request request, KnowledgeLedgerRepository.Operation operation, List<KnowledgeSeed> catalog) {
        KnowledgeAccessPolicy.Source source = knowledge.transaction(() -> knowledge.access.source(request.guardianId(), request.childId(), request.activityId()));
        validator.checked(Map.of("inference", request.structure().requiredInference()), "inference", source.rawTexts(), source.names());
        Map<String, Object> input = Map.of("candidate_structure", signature(request.structure()), "seed_catalog",
                catalog.stream().map(s -> Map.of("seed_ref", s.seedId(), "skill_name", s.label(),
                        "invariant_sentence", s.invariantSentence(), "family", s.family())).toList());
        LunaCallBudget attempts = new LunaCallBudget();
        while (true) {
            LunaResult result = luna.orElseThrow().call(LunaRequest.of(LunaPurpose.SEED_GENERATION, "seed-generation.v2",
                    prompt, input, Map.of(), operation.deadline()), attempts);
            Map<?, ?> output = json.read(result.outputJson(), Map.class);
            try {
                String outcome = String.valueOf(output.get("outcome"));
                if ("NONE".equals(outcome)) {
                    recordCheck(operation, "MODEL_NONE");
                    return finish(request, operation, null);
                }
                if ("DUPLICATE".equals(outcome)) {
                    String id = String.valueOf(output.get("duplicate_of"));
                    if (catalog.stream().noneMatch(s -> s.seedId().equals(id))) throw new IllegalArgumentException("Unknown duplicate");
                    return finish(request, operation, id);
                }
                KnowledgeSeed seed = validator.generated(operation.seedId(), output, source.rawTexts(), source.names());
                recordCheck(operation, "CONTENT_ACCEPTED");
                Optional<String> duplicate = catalog.stream().filter(s -> sameStructure(s, seed)).map(KnowledgeSeed::seedId).findFirst();
                if (duplicate.isPresent()) return finish(request, operation, duplicate.get());
                return project(request, operation, seed, result);
            } catch (IllegalArgumentException invalid) {
                recordCheck(operation, "CONTENT_REJECTED");
                if (!attempts.takeCorrection()) return finish(request, operation, null);
            }
        }
    }

    private Result finish(Request request, KnowledgeLedgerRepository.Operation operation, String reused) {
        return knowledge.transaction(() -> {
            eligible(request, operation.deadline());
            if (reused != null && visible(request.childId()).stream().noneMatch(s -> s.seedId().equals(reused))) {
                knowledge.ledger.finish(operation, null);
                return Result.none("REFERENCE_GONE");
            }
            knowledge.ledger.finish(operation, reused);
            if (reused != null) knowledge.ledger.check(operation, "DUPLICATE_REUSED");
            return reused == null ? Result.none("NO_VALID_SEED") : new Result(reused, "REUSED");
        });
    }

    private Result project(Request request, KnowledgeLedgerRepository.Operation op, KnowledgeSeed seed, LunaResult result) {
        String handle = knowledge.transaction(() -> {
            eligible(request, op.deadline());
            String tx = knowledge.graph.begin();
            try { knowledge.ledger.projecting(op, tx, seed, result.responseModel(), result.promptVersion()); }
            catch (RuntimeException failed) { knowledge.graph.terminate(tx); throw failed; }
            return tx;
        });
        return knowledge.transaction(() -> {
            eligible(request, op.deadline());
            var current = knowledge.ledger.operation(op.childId(), op.candidateRef()).orElseThrow();
            if (!"PROJECTING".equals(current.state()) || !handle.equals(current.transactionId())) throw new IllegalStateException("Projection replaced");
            var duplicate = visible(op.childId()).stream().filter(existing -> sameStructure(existing, seed)).findFirst();
            if (duplicate.isPresent()) {
                if (!knowledge.graph.terminate(handle)) throw new IllegalStateException("Projection unresolved");
                knowledge.ledger.finish(current, duplicate.get().seedId());
                return new Result(duplicate.get().seedId(), "REUSED");
            }
            knowledge.graph.writeGeneratedSeed(handle, op.childId(), seed);
            knowledge.requireTime(op.deadline());
            knowledge.graph.commit(handle);
            knowledge.ledger.committed(current, knowledge.clock.now());
            return new Result(seed.seedId(), "GENERATED");
        });
    }

    private void eligible(Request request, Instant deadline) {
        knowledge.access.source(request.guardianId(), request.childId(), request.activityId());
        var state = knowledge.ledger.ensure(request.guardianId(), request.childId());
        ChildKnowledgeService.requireActive(state);
        if (knowledge.ledger.deletedSource(request.childId(), request.activityId())) throw new IllegalStateException("Source deleted");
        knowledge.requireTime(deadline);
    }

    private List<KnowledgeSeed> visible(UUID child) {
        var successful = knowledge.ledger.successful(child).stream().map(KnowledgeSeed::seedId).collect(java.util.stream.Collectors.toSet());
        return knowledge.graph.seeds(child).stream().filter(KnowledgeSeed::enabled)
                .filter(s -> s.origin() == KnowledgeSeed.Origin.POC || successful.contains(s.seedId())).toList();
    }

    private void recordCheck(KnowledgeLedgerRepository.Operation operation, String code) {
        knowledge.transaction(() -> { knowledge.ledger.check(operation, code); return null; });
    }

    private void finishReserved(Request request, KnowledgeLedgerRepository.Operation owned) {
        try {
            knowledge.transaction(() -> {
                knowledge.access.requireAllowed(request.guardianId(), request.childId());
                knowledge.ledger.check(owned, "GENERATION_FAILED");
                knowledge.ledger.finishOwnedReservation(owned);
                return null;
            });
        } catch (RuntimeException ignored) {
            // 삭제/DB 장애 시 예약은 최초 마감 뒤 회수한다. 원문이나 예외를 로그로 내보내지 않는다.
        }
    }

    private static Map<String, Object> signature(StructureSignature value) {
        return Map.of("target_skill", value.targetSkill(), "required_inference", value.requiredInference(),
                "perspective_structure", value.perspectiveStructure(), "decidable_by_evidence", value.decidableByEvidence(),
                "direct_check_allowed", value.directCheckAllowed());
    }

    private static boolean sameStructure(KnowledgeSeed a, KnowledgeSeed b) {
        return a.signature().equals(b.signature()) && a.family().equals(b.family())
                && SeedContentValidator.compact(a.preservationRule()).equals(SeedContentValidator.compact(b.preservationRule()))
                && SeedContentValidator.compact(a.invariantSentence()).equals(SeedContentValidator.compact(b.invariantSentence()));
    }
}
