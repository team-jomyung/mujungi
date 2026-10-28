package kr.mujoong.core.practicegoal;

import java.util.*;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.knowledge.SeedGenerationService;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.practice.PracticeAccountPort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable result replay registers candidates, then generates seeds outside all result/account locks. */
@Service
public class CandidateSeedDelivery {
    public record Pending(String format,List<GoalCandidateRepository.Draft> candidates){
        public Pending {candidates=List.copyOf(candidates);}
        public static Pending of(List<GoalCandidateRepository.Draft> candidates){return new Pending("goal-candidates-v1",candidates);}
        @Override public String toString(){return "PendingGoals[redacted]";}
    }
    private final GoalCandidateRepository repository;
    private final PracticeAccountPort accounts;
    private final ConsentPolicyService consent;
    private final SeedGenerationService seeds;
    private final NamedParameterJdbcTemplate jdbc;
    private final MujoongClock clock;
    private final EventLogger events;
    private final TransactionTemplate tx;
    public CandidateSeedDelivery(GoalCandidateRepository repository,PracticeAccountPort accounts,ConsentPolicyService consent,
            SeedGenerationService seeds,NamedParameterJdbcTemplate jdbc,MujoongClock clock,EventLogger events,PlatformTransactionManager transactions){
        this.repository=repository;this.accounts=accounts;this.consent=consent;this.seeds=seeds;this.jdbc=jdbc;this.clock=clock;this.events=events;tx=new TransactionTemplate(transactions);
    }
    public void deliver(UUID guardian,UUID child,UUID session,UUID result,Pending pending){
        if(!"goal-candidates-v1".equals(pending.format()))throw new IllegalArgumentException("Unknown pending format");
        List<GoalCandidateRepository.Version> registered=tx.execute(t->{
            requireCurrent(guardian,child,session,result,!pending.candidates().isEmpty());
            repository.invalidatePrevious(child,session,result);
            List<GoalCandidateRepository.Version> versions=new ArrayList<>();
            for(int i=0;i<pending.candidates().size();i++)versions.add(repository.register(child,session,result+":"+i,pending.candidates().get(i),clock.now()));
            return List.copyOf(versions);
        });
        for(var version:registered)generate(guardian,child,session,result,version);
        saveGenerated(guardian,child,session,result,registered);
        recordObservations(guardian,child);
    }
    private void saveGenerated(UUID guardian,UUID child,UUID session,UUID result,List<GoalCandidateRepository.Version> registered){
        try { tx.executeWithoutResult(t->{
            requireCurrent(guardian,child,session,result,!registered.isEmpty());
            boolean free=Boolean.TRUE.equals(jdbc.queryForObject("select activity_type='FREE_TALK' from core.core_activity_record where session_id=:s and child_id=:c",Map.of("s",session,"c",child),Boolean.class));
            if(!free)return;
            boolean changed=false;
            for(var version:registered){
                var current=repository.current(child,version.candidateId()).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));
                changed|=repository.selectGenerated(guardian,current,clock.now());
            }
            // PostgreSQL delivers NOTIFY only after commit; the event contains no goal content.
            if(changed)jdbc.query("select pg_catalog.pg_notify('mujoong_goal_selections_changed',:g)",Map.of("g",guardian.toString()),rs->{});
        }); } catch(CoreApiException changedDuringGeneration){
            // A withdrawn consent or superseded/deleted source must not be auto-saved.
            if(changedDuringGeneration.code()!=ErrorCode.CONSENT_MISSING&&changedDuringGeneration.code()!=ErrorCode.NOT_FOUND)throw changedDuringGeneration;
        }
    }
    private kr.mujoong.core.knowledge.ChildKnowledgeService knowledge;
    @org.springframework.beans.factory.annotation.Autowired
    void connectKnowledge(kr.mujoong.core.knowledge.ChildKnowledgeService knowledge){this.knowledge=knowledge;}
    /** 새 씨앗 연결까지 끝난 뒤 자유대화 태그 횟수를 아이 그래프에 다시 쓴다. 다음 결과 작업이 전체를 다시 세므로 실패해도 따라잡는다. */
    private void recordObservations(UUID guardian,UUID child){
        if(knowledge==null)return;
        try{knowledge.recordObservations(guardian,child,()->repository.freeTalkSkillObservations(child));}
        catch(RuntimeException failed){
            // 관찰 횟수는 장면 참고용 보조 기록이다. 결과 상태와 후보 등록은 바꾸지 않는다.
            events.appError(EventLog.Component.CORE_MAINTENANCE,"SkillObservationSyncFailed");
        }
    }
    private void generate(UUID guardian,UUID child,UUID session,UUID result,GoalCandidateRepository.Version version){
        var draft=version.content();if(!"NONE".equals(draft.skillRef())||draft.structureSignature()==null)return;
        try{
            boolean free=tx.execute(t->{requireCurrent(guardian,child,session,result);return jdbc.queryForObject("select activity_type='FREE_TALK' from core.core_activity_record where session_id=:s and child_id=:c",Map.of("s",session,"c",child),Boolean.class);});
            if(!free)return;
            var generated=seeds.generateSeed(new SeedGenerationService.Request(guardian,child,session,version.candidateId()+":"+version.version(),"NONE",draft.structureSignature()));
            if(generated==null||"NONE".equals(generated.seedId()))return;
            tx.executeWithoutResult(t->{requireCurrent(guardian,child,session,result);repository.linkSeed(version,generated.seedId(),clock.now());});
        }catch(RuntimeException failed){
            // Only independent seed post-processing is recoverable; never change completed result status.
            events.appError(EventLog.Component.CORE_MAINTENANCE,"GoalSeedPostProcessingFailed");
        }
    }
    private void requireCurrent(UUID guardian,UUID child,UUID session,UUID result){
        requireCurrent(guardian,child,session,result,true);
    }
    private void requireCurrent(UUID guardian,UUID child,UUID session,UUID result,boolean requiresConsent){
        if(!child.equals(accounts.requireActive(guardian,true).childId()))throw CoreApiException.of(ErrorCode.NOT_FOUND);
        // Clearing obsolete candidates is not AI processing and must finish after consent withdrawal too.
        if(requiresConsent&&!consent.check(guardian).ok())throw CoreApiException.of(ErrorCode.CONSENT_MISSING);
        Integer count=jdbc.queryForObject("select count(*) from core.core_session_result_state s join core.core_activity_record r on r.session_id=s.session_id and r.guardian_id=s.guardian_id and r.child_id=s.child_id where s.guardian_id=:g and s.child_id=:c and s.session_id=:s and s.current_result_id=:r and s.job_status='DONE'",Map.of("g",guardian,"c",child,"s",session,"r",result),Integer.class);
        if(count==null||count!=1)throw CoreApiException.of(ErrorCode.NOT_FOUND);
    }
}
