package kr.mujoong.realtime.practiceflow;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 새 연습 흐름의 내부 값 (U12 entities.md SceneRun · PracticeFact · HelpRecord). 열거 값의 이름은 V7 표의 검사 제약과 {@code practice-record-v2}
 * 스키마의 열거 값과 같다. 아이 전사 · 도움 발화 · 대본 문장을 담는 칸은 어디에도 없다(R-F14 · BR7.1, 편차 U12-D2).
 */
public final class PracticeFlowTypes {

    private PracticeFlowTypes() {
    }

    /** 장면 종류: 원 장면(BASE) 또는 변형(VARIANT). */
    public enum SceneKind { BASE, VARIANT }

    /** 진행 중 판정 요약 시점. {@code practice-record-v2} liveOutcome.timing. */
    public enum LiveTiming { IMMEDIATE, AFTER_REASK, AFTER_HELP, NONE, ABSTAINED }

    /** 변형 생략 사유(BR6.2). {@code practice-record-v2} variantSkipReason(null 허용은 사유 없음). */
    public enum VariantSkipReason { NO_VARIANT, DECLINED, TIME_LIMIT, FOCUS_LIMIT, END_OR_REST }

    /** 기록 사실 15종(FR7.3, {@code practice-record-v2} facts). 받는 쪽은 모르는 값을 무시한다. */
    public enum FactKind {
        REASK_RESOLVED, SITUATION_EXPLAINED, FOCUS_HELP, EXPRESSION_EXAMPLE, HELP_ACCEPTED, HELP_DECLINED, COACH_SWITCH,
        COACH_RETURN, ORIGINAL_SCENE_RETURN, VARIANT_PLAYED, VARIANT_SKIPPED, END_REQUEST, TIMEOUT, TRANSCRIPT_FAILED,
        PLAYBACK_FAILED
    }

    /** 도움 · 역할 전환 기록 종류. */
    public enum HelpKind { ROLE_SWITCH, ROLE_RETURN, FOCUS_HELP, EXPRESSION_EXAMPLE, SITUATION_EXPLAIN }

    /** 말 상대 역할. */
    public enum FlowRole { PEER, COACH }

    /** 도움 동의 응답 분류. */
    public enum ConsentAnswer { ACCEPT, DECLINE, UNCLEAR, REQUESTED }

    /** 일어난 사실 하나와 그 시각. */
    public record Fact(FactKind kind, Instant at) {
        public Fact {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * 장면 실행 한 행(BR7.3). 도입 재생이 성공한 뒤에만 만든다.
     *
     * @param id realtime이 만든 sceneRunId
     * @param goalRef 시작 패키지 목표의 goalSelectionId(U14 goalRef)
     * @param scenarioRef 대본 전체의 scenarioId
     * @param facts 일어난 순서대로 쌓인 사실(변경 불가 복사본)
     */
    public record SceneRunRecord(UUID id, UUID sessionId, UUID guardianId, UUID goalRef, int goalIndex, UUID scenarioRef,
            SceneKind sceneKind, Instant startedAt, Instant endedAt, LiveTiming liveTiming,
            VariantSkipReason variantSkipReason, int retryCount, int reexplainCount, List<Fact> facts) {

        public SceneRunRecord {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(guardianId, "guardianId");
            Objects.requireNonNull(goalRef, "goalRef");
            Objects.requireNonNull(scenarioRef, "scenarioRef");
            Objects.requireNonNull(sceneKind, "sceneKind");
            Objects.requireNonNull(startedAt, "startedAt");
            if (goalIndex < 0) {
                throw new IllegalArgumentException("goalIndex는 0 이상이어야 한다");
            }
            if (retryCount < 0 || reexplainCount < 0) {
                throw new IllegalArgumentException("횟수는 0 이상이어야 한다");
            }
            if (endedAt != null && endedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("endedAt이 startedAt보다 앞선다");
            }
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        /** 막 시작한 장면(끝 · 결과 · 사실 없음). */
        public static SceneRunRecord started(UUID id, UUID sessionId, UUID guardianId, UUID goalRef, int goalIndex,
                UUID scenarioRef, SceneKind kind, Instant startedAt) {
            return new SceneRunRecord(id, sessionId, guardianId, goalRef, goalIndex, scenarioRef, kind, startedAt, null, null,
                    null, 0, 0, List.of());
        }

        @Override
        public String toString() {
            return "SceneRunRecord[id=" + id + ", sceneKind=" + sceneKind + "]";
        }
    }

    /**
     * 도움 · 역할 전환 기록 한 건(U12-D2: 분류 코드와 길이만).
     *
     * @param contentChars 도움 발화의 길이(글자 수). 내용은 저장하지 않는다
     */
    public record HelpRecord(UUID id, UUID sceneRunId, UUID guardianId, HelpKind kind, FlowRole role, boolean exampleGiven,
            ConsentAnswer consentAnswer, int contentChars, Instant occurredAt) {

        public HelpRecord {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sceneRunId, "sceneRunId");
            Objects.requireNonNull(guardianId, "guardianId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (contentChars < 0) {
                throw new IllegalArgumentException("contentChars는 0 이상이어야 한다");
            }
        }
    }
}
