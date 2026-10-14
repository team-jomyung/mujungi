package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import kr.mujoong.core.common.db.DbTestSupport;
import org.junit.jupiter.api.*;
@Tag("u6") @Tag("db")
class SessionResultStateRepositoryIT {
    DbTestSupport db;SessionResultStateRepository states;ActivityResultRepository results;ResultJobRepository jobs;
    Instant now=Instant.parse("2026-10-14T00:00:00Z");UUID s=UUID.randomUUID(),g=UUID.randomUUID(),c=UUID.randomUUID();
    @BeforeEach void open(){db=DbTestSupport.coreApp();states=new SessionResultStateRepository(db.jdbc());results=new ActivityResultRepository(db.jdbc());jobs=new ResultJobRepository(db.jdbc());}
    @AfterEach void close(){db.close();}
    @Test void insertionIsIdempotent(){db.inRolledBackTx(t->{assertThat(states.insert(s,g,c,"FREE_TALK",true,now)).isTrue();assertThat(states.insert(s,g,c,"FREE_TALK",true,now)).isFalse();assertThat(states.find(s,g).orElseThrow().runOrdinal()).isEqualTo(1);return null;});}
    @Test void onlyFailedHeadRetries(){db.inRolledBackTx(t->{states.insert(s,g,c,"FREE_TALK",true,now);var job=jobs.submit("SESSION_RESULT",s,g,s.toString(),"RECONCILE",null,now);assertThat(states.retry(s,job.id(),2,now)).isFalse();db.jdbc().update("update core.core_session_result_state set job_status='FAILED' where session_id=:s",java.util.Map.of("s",s));assertThat(states.retry(s,job.id(),2,now)).isTrue();assertThat(states.retry(s,job.id(),3,now)).isFalse();return null;});}
    @Test void anotherGuardianCannotRead(){db.inRolledBackTx(t->{states.insert(s,g,c,"FREE_TALK",false,now);assertThat(states.find(s,UUID.randomUUID())).isEmpty();assertThat(states.lock(s,UUID.randomUUID())).isEmpty();return null;});}
    @Test void currentVersionAndInvalidationAreIndependent(){db.inRolledBackTx(t->{var j=setup();var first=insert(j);var second=insert(j);assertThat(second.version()).isEqualTo(2);assertThat(results.current(s).orElseThrow().id()).isEqualTo(second.id());results.invalidate(first.id(),now);assertThat(results.find(first.id()).orElseThrow().invalidatedAt()).isEqualTo(now);assertThat(results.current(s)).isPresent();return null;});}
    @Test void registrationMarkerRemovesPendingResult(){db.inRolledBackTx(t->{var result=insert(setup());db.jdbc().update("update core.core_session_result_state set job_status='DONE' where session_id=:s",java.util.Map.of("s",s));assertThat(results.pendingCandidates()).extracting(ActivityResultRepository.Result::id).contains(result.id());results.candidatesRegistered(result.id(),now);assertThat(results.pendingCandidates()).extracting(ActivityResultRepository.Result::id).doesNotContain(result.id());return null;});}
    UUID setup(){states.insert(s,g,c,"FREE_TALK",true,now);var job=jobs.submit("SESSION_RESULT",s,g,s.toString(),"RECONCILE",null,now);states.attach(s,job.id(),1,now);return job.id();}
    ActivityResultRepository.Result insert(UUID job){return results.insert(s,job,"PARTIAL","[]","[]","[]","NEED_MORE_OBSERVATION","[]","[]",now);}
}
