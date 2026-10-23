package kr.mujoong.core.report;

import static kr.mujoong.core.result.ResultData.*;

import java.util.*;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.knowledge.BehaviorDefinitionLookup;
import kr.mujoong.core.record.ResultRecordReader;
import kr.mujoong.core.result.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WF9 · BR9: 읽기 스냅숏으로 10항목을 서버 규칙만으로 만들고 쓰기 확인 안에서 확정한다.
 * 리포트 생성 때 AI(글 생성 · 금지 표현 2차 분류)를 부르지 않는다(JIRA-267, contracts/deviations.md FT-1023-c).
 *
 * <p>고른 활동 중 결과 정리가 아직 끝나지 않은 활동(정리 대기 · 정리 중, 결과 접수 전)이 있으면 이번 시도를 미루고
 * 작업 재시도 간격(result.job-retry-backoff)으로 다시 본다. 리포트 절대 마감(report.ready-target) 안에 다음 시도가 없으면
 * 그때 있는 자료로 만든다. 정리하지 못한(FAILED) 활동 · 결과 대상이 아닌 활동 · 동의 철회 활동은 기다리지 않는다.
 */
@Component
public class ReportHandler implements JobHandler {
    private final ReportRepository reports;
    private final ResultRecordReader records;
    private final SessionResultStateRepository states;
    private final ActivityResultRepository results;
    private final SceneJudgmentRepository scenes;
    private final StructuredExperienceRepository structures;
    private final BehaviorDefinitionLookup definitions;
    private final ReportAssembler assembler;
    private final ReportRuleSections ruleSections;
    private final ConsentPolicyService consent;
    private final TransactionTemplate tx;

    public ReportHandler(ReportRepository reports, ResultRecordReader records, SessionResultStateRepository states,
            ActivityResultRepository results, SceneJudgmentRepository scenes, StructuredExperienceRepository structures,
            BehaviorDefinitionLookup definitions, ReportAssembler assembler, ReportRuleSections ruleSections,
            PlatformTransactionManager transactions, ConsentPolicyService consent) {
        this.reports = reports;
        this.records = records;
        this.states = states;
        this.results = results;
        this.scenes = scenes;
        this.structures = structures;
        this.definitions = definitions;
        this.assembler = assembler;
        this.ruleSections = ruleSections;
        this.consent = consent;
        this.tx = new TransactionTemplate(transactions);
    }

    public String jobType() {
        return "REPORT";
    }

    /** settling: 결과 정리를 기다릴 활동이 있다. */
    private record Snapshot(ReportRepository.Report report, List<ReportInput> inputs, boolean settling) {}

    /** 결과가 곧 생길 수 있는 정리 상태. 결과 머리가 아직 없는 활동(접수 전)도 기다린다. */
    private static final Set<String> SETTLING = Set.of("REQUESTED", "RUNNING");

    public Outcome handle(JobContext job) {
        requireReportAccess(job);
        Snapshot snapshot = tx.execute(t -> load(job));
        if (snapshot == null) {
            return Outcome.failed("REPORT_DISCARDED", false);
        }
        if (snapshot.settling() && job.retryAvailable()) {
            return Outcome.deferred();
        }
        var inputs = snapshot.inputs();
        Map<Integer, ReportRepository.Section> sections = new TreeMap<>(assembler.assemble(snapshot.report(), inputs));
        sections.putAll(ruleSections.write(inputs));
        boolean done = job.commit(() -> {
            // 읽은 뒤 동의 철회 · 계정 삭제가 들어오면 확정하지 않는다.
            requireReportAccess(job);
            for (var input : inputs) {
                reports.resultStatus(job.subjectId(), input.record().sessionId(), input.status());
            }
            if (!reports.writeSections(job.subjectId(), job.jobId(), assembler.level(inputs), List.copyOf(sections.values()))) {
                throw new IllegalStateException("REPORT_DISCARDED");
            }
        }, () -> {});
        return done ? Outcome.done() : Outcome.failed("REPORT_DISCARDED", false);
    }

    /**
     * 리포트를 만들 수 있는 조건: 보호자가 ACTIVE이고 지금 음성 · 대화 기록 동의가 있다.
     * AI 전송은 없어졌지만 리포트는 저장 전사 · 결과를 읽으므로, 동의 철회 뒤에는 이전처럼 만들지 않는다(재시도 없이 실패).
     */
    private void requireReportAccess(JobContext job) {
        job.requireActiveGuardian();
        if (!consent.check(job.guardianId()).snapshot().voiceAndText()) {
            throw new ResultAiException("CONSENT_WITHDRAWN");
        }
    }

    private Snapshot load(JobContext job) {
        var report = reports.findOwned(job.subjectId(), job.guardianId()).orElse(null);
        if (report == null || !report.status().equals("GENERATING")) {
            return null;
        }
        List<ReportInput> inputs = new ArrayList<>();
        int ordinal = 0;
        boolean settling = false;
        for (var selected : reports.activities(report.id())) {
            var record = records.find(selected.sessionId(), job.guardianId()).orElseThrow().record();
            var state = states.find(record.sessionId(), job.guardianId());
            String status = state.map(s -> s.jobStatus()).orElse("NONE");
            var result = status.equals("DONE") ? results.current(record.sessionId()).orElse(null) : null;
            List<Map<String, Object>> defs = new ArrayList<>();
            for (var g : goals(practice(record.practiceJson()))) {
                try {
                    var d = definitions.get(text(g, "behaviorKey"), (int) number(g, "behaviorVersion"));
                    defs.add(Map.of("displayName", d.displayName(), "coreFunction", d.behaviorDefinitionText()));
                } catch (IllegalArgumentException missing) {
                    // BR12.2: 없는 고정 버전은 7항목에서 뺀다.
                }
            }
            var input = new ReportInput(++ordinal, record, status, result,
                    result == null ? List.of() : scenes.find(result.id()),
                    result == null ? Map.of() : structures.find(result.id()).map(s -> practice(s)).orElse(Map.of()),
                    defs);
            inputs.add(input);
            // 결과 머리가 있고 NONE이면 결과 대상이 아닌 활동이라 기다려도 결과가 생기지 않는다.
            settling |= input.contentAllowed() && (state.isEmpty() || SETTLING.contains(status));
        }
        return new Snapshot(report, List.copyOf(inputs), settling);
    }
}
