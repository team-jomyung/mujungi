package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kr.mujoong.core.common.db.DbTestSupport;
import org.junit.jupiter.api.*;
@Tag("u6") @Tag("db")
class ResultJobRepositoryIT {
    DbTestSupport db;ResultJobRepository jobs;Instant now=Instant.parse("2026-10-14T00:00:00Z");
    @BeforeEach void open(){db=DbTestSupport.coreApp();jobs=new ResultJobRepository(db.jdbc());}
    @AfterEach void close(){db.close();}
    ResultJob submit(UUID subject,String key){return jobs.submit("SESSION_RESULT",subject,UUID.randomUUID(),key,"RECONCILE",null,now);}
    @Test void sameKeyReturnsOriginal(){db.inRolledBackTx(t->{UUID s=UUID.randomUUID(),g=UUID.randomUUID();String k=UUID.randomUUID().toString();var one=jobs.submit("SESSION_RESULT",s,g,k,"RECONCILE",null,now);assertThat(jobs.submit("SESSION_RESULT",s,g,k,"RECONCILE",null,now).id()).isEqualTo(one.id());return null;});}
    @Test void activeSubjectReturnsOriginal(){db.inRolledBackTx(t->{UUID s=UUID.randomUUID();var one=submit(s,UUID.randomUUID().toString());assertThat(submit(s,UUID.randomUUID().toString()).id()).isEqualTo(one.id());return null;});}
    @Test void onlyDueJobsAreClaimed(){db.inRolledBackTx(t->{var one=submit(UUID.randomUUID(),UUID.randomUUID().toString());assertThat(jobs.claim("SESSION_RESULT",1,now.minusSeconds(1),Duration.ofSeconds(30))).isEmpty();assertThat(jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30))).extracting(ResultJob::id).containsExactly(one.id());return null;});}
    @Test void staleAttemptCannotTransition(){db.inRolledBackTx(t->{var one=submit(UUID.randomUUID(),UUID.randomUUID().toString());var claimed=jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30)).getFirst();assertThat(jobs.transition(one,"DONE",null,null,now,"SUCCEEDED")).isFalse();assertThat(jobs.transition(claimed,"DONE",null,null,now,"SUCCEEDED")).isTrue();assertThat(jobs.transition(claimed,"FAILED","INTERNAL",null,now,"FAILED")).isFalse();return null;});}
    @Test void startupFailsOnlyRunning(){db.inRolledBackTx(t->{submit(UUID.randomUUID(),UUID.randomUUID().toString());var running=jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30)).getFirst();var waiting=submit(UUID.randomUUID(),UUID.randomUUID().toString());assertThat(jobs.startupCheck(now)).isEqualTo(1);assertThat(jobs.find(running.id()).orElseThrow().lastErrorCode()).isEqualTo("SERVER_RESTART");assertThat(jobs.find(waiting.id()).orElseThrow().status()).isEqualTo("REQUESTED");return null;});}
    @Test void expiredLeaseIsVisibleAtBoundary(){db.inRolledBackTx(t->{submit(UUID.randomUUID(),UUID.randomUUID().toString());jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30));assertThat(jobs.expired(now.plusSeconds(29))).isEmpty();assertThat(jobs.expired(now.plusSeconds(30))).hasSize(1);return null;});}
    @Test void retriesCreateDistinctAttemptNumbers(){db.inRolledBackTx(t->{submit(UUID.randomUUID(),UUID.randomUUID().toString());var first=jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30)).getFirst();jobs.transition(first,"REQUESTED","TIMEOUT",now.plusSeconds(5),now,"FAILED");assertThat(jobs.claim("SESSION_RESULT",1,now.plusSeconds(4),Duration.ofSeconds(30))).isEmpty();var second=jobs.claim("SESSION_RESULT",1,now.plusSeconds(5),Duration.ofSeconds(30)).getFirst();assertThat(second.attemptCount()).isEqualTo(2);assertThat(db.jdbc().queryForObject("select count(*) from core.core_result_job_attempt where job_id=:id",java.util.Map.of("id",first.id()),Integer.class)).isEqualTo(2);return null;});}
    @Test void claimedRowIsNotClaimedTwice(){db.inRolledBackTx(t->{submit(UUID.randomUUID(),UUID.randomUUID().toString());assertThat(jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30))).hasSize(1);assertThat(jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30))).isEmpty();return null;});}
    @Test void simultaneousClaimsSkipLockedRows(){
        UUID s1=UUID.randomUUID(),s2=UUID.randomUUID();
        db.inTx(t->{submit(s1,UUID.randomUUID().toString());submit(s2,UUID.randomUUID().toString());return null;});
        try {db.inRolledBackTx(t->{var first=jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30)).getFirst();
            var second=DbTestSupport.await(db.start(()->db.inRolledBackTx(other->jobs.claim("SESSION_RESULT",1,now,Duration.ofSeconds(30)).getFirst())));
            assertThat(first.id()).isNotEqualTo(second.id());return null;});
        }finally{db.jdbc().update("delete from core.core_result_job where subject_id in (:ids)",java.util.Map.of("ids",java.util.List.of(s1,s2)));}
    }
    @Test void simultaneousSubmitsUseOneActiveSubject(){
        UUID subject=UUID.randomUUID();var pid=new java.util.concurrent.atomic.AtomicInteger();
        var waiting=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<ResultJob>>();
        try{var one=db.inTx(t->{var created=submit(subject,UUID.randomUUID().toString());
            waiting.set(db.startTx(pid,other->submit(subject,UUID.randomUUID().toString())));db.awaitLockWait(pid);return created;});
            assertThat(DbTestSupport.await(waiting.get()).id()).isEqualTo(one.id());
        }finally{db.jdbc().update("delete from core.core_result_job where subject_id=:id",java.util.Map.of("id",subject));}
    }
}
