package kr.mujoong.realtime.practiceflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.Fact;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.FactKind;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.HelpRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.LiveTiming;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneRunRecord;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.VariantSkipReason;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 세션 하나의 운영 기록기 (U12 FR4.1 · FR4.2 · BR7.1 ~ BR7.3). 상태 기계가 남기는 장면 · 사실 · 도움을 V7 표에 쓰고({@link PracticeFlowRepository}),
 * 정산 때 {@code record.practice} 값을 같은 값에서 조립한다({@link PracticeFlowRecordAssembler}).
 *
 * <ul>
 *   <li>값은 먼저 메모리에 반영한 뒤 표에 쓴다. 표 쓰기가 실패해도(예외는 상태 기계가 앱 오류 한 줄로 남기고 연습을 잇는다) 정산 값은 메모리에서
 *       조립되어 이 세션의 기록이 비지 않는다. 같은 세션의 정산 복구는 표만 읽는다({@link PracticeFlowRecordRecovery}).
 *   <li>쓰기는 호출마다 짧은 트랜잭션 하나다(저장소가 활성 트랜잭션을 요구한다, RD-22). 호출은 실행기 스레드에서 직렬로 온다.
 *   <li>원문(아이 전사 · 도움 발화 · 대본 문장)을 받는 메서드가 없다(R-F14 · BR7.1). 이 클래스는 로그를 쓰지 않는다.
 * </ul>
 */
public final class PracticeFlowRecorder implements FlowRecorder {

    private final PracticeFlowRepository repository;
    private final TransactionTemplate transactions;
    private final Map<UUID, SceneRunRecord> runs = new LinkedHashMap<>();

    public PracticeFlowRecorder(PracticeFlowRepository repository, TransactionTemplate transactions) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void sceneStarted(SceneRunRecord started) {
        Objects.requireNonNull(started, "started");
        runs.put(started.id(), started);
        transactions.executeWithoutResult(status -> repository.insertSceneRun(started));
    }

    @Override
    public void fact(UUID sceneRunId, FactKind kind, Instant at) {
        SceneRunRecord row = runs.get(sceneRunId);
        if (row == null) {
            return;
        }
        List<Fact> facts = new ArrayList<>(row.facts());
        facts.add(new Fact(kind, at));
        runs.put(sceneRunId, with(row, row.endedAt(), row.liveTiming(), row.variantSkipReason(), row.retryCount(),
                row.reexplainCount(), facts));
        transactions.executeWithoutResult(status -> repository.appendFact(sceneRunId, kind, at));
    }

    @Override
    public void help(HelpRecord help) {
        Objects.requireNonNull(help, "help");
        if (!runs.containsKey(help.sceneRunId())) {
            return;
        }
        transactions.executeWithoutResult(status -> repository.insertHelp(help));
    }

    @Override
    public void sceneOutcome(UUID sceneRunId, Instant endedAt, LiveTiming timing, VariantSkipReason skip, int retryCount,
            int reexplainCount) {
        SceneRunRecord row = runs.get(sceneRunId);
        if (row == null) {
            return;
        }
        runs.put(sceneRunId, with(row, endedAt, timing, skip, retryCount, reexplainCount, row.facts()));
        transactions.executeWithoutResult(
                status -> repository.updateOutcome(sceneRunId, endedAt, timing, skip, retryCount, reexplainCount));
    }

    /** 정산 시점의 {@code practice} 값. 장면이 하나도 없어도 계약 모양(scenes 빈 배열)이다. */
    @Override
    public Object practiceRecord() {
        return PracticeFlowRecordAssembler.assemble(List.copyOf(runs.values()));
    }

    private static SceneRunRecord with(SceneRunRecord row, Instant endedAt, LiveTiming timing, VariantSkipReason skip,
            int retryCount, int reexplainCount, List<Fact> facts) {
        return new SceneRunRecord(row.id(), row.sessionId(), row.guardianId(), row.goalRef(), row.goalIndex(),
                row.scenarioRef(), row.sceneKind(), row.startedAt(), endedAt, timing, skip, retryCount, reexplainCount, facts);
    }
}
