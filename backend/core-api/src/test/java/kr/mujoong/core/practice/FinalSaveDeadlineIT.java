package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.textai.GeneratedScenario;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.*;

@Tag("scenario-generation") class FinalSaveDeadlineIT {
    StructuredFixture f;
    @BeforeEach void open(){f=new StructuredFixture();}
    @AfterEach void close(){f.close();}
    void noIssuedOrReady(){
        f.legacy.error(()->f.legacy.service.generate(f.legacy.original()),kr.mujoong.contract.error.ErrorCode.SCENARIO_GEN_FAILED);
        assertThat(f.legacy.db.jdbc().queryForObject("select count(*) from core.core_scenario where guardian_id=:g",Map.of("g",f.legacy.guardian),Integer.class)).isZero();
        assertThat(f.legacy.db.jdbc().queryForObject("select count(*) from core.core_scenario_request where guardian_id=:g and status='READY'",Map.of("g",f.legacy.guardian),Integer.class)).isZero();
    }
    @Test void actualDatabaseWaitUsesOnlyOriginalRemainingMilliseconds(){
        f.legacy.repository=new ScenarioRepository(f.legacy.db.jdbc(),JsonCodec.shared()){
            @Override public Scenario save(String key,UUID claim,PracticeSessionContextPort.SessionContext context,PracticeSessionContextPort.GoalSnapshot goal,ScenarioService.Request request,GeneratedScenario output,Instant now,Details details,ScenarioDeadline deadline){
                f.legacy.db.jdbc().queryForObject("select pg_sleep(:delay)",Map.of("delay",0.2),(rs,row)->true);
                return super.save(key,claim,context,goal,request,output,now,details,deadline);
            }
        };f.connectService();
        when(f.provider.review(any())).thenAnswer(c->{f.legacy.clock.advance(Duration.ofMillis(14900));return ScenarioGenerationTest.approve();});
        noIssuedOrReady();
    }
    void advanceAfterSql(String match){
        var jdbc=spy(f.legacy.db.jdbc());
        doAnswer(call->{int count=(Integer)call.callRealMethod();if(((String)call.getArgument(0)).contains(match))f.legacy.clock.advance(Duration.ofSeconds(15));return count;})
                .when(jdbc).update(anyString(),any(SqlParameterSource.class));
        f.legacy.repository=new ScenarioRepository(jdbc,JsonCodec.shared());f.connectService();
    }
    @Test void exactExpiryAfterInsertRollsBackInsertAndReadyTogether(){advanceAfterSql("insert into core.core_scenario(");noIssuedOrReady();}
    @Test void exactExpiryAfterReadyUpdateStillRollsBackBothRows(){advanceAfterSql("update core.core_scenario_request set status='READY'");noIssuedOrReady();}
    @Test void freshClockAtBeforeCommitRejectsExpiryAfterAllSql(){
        f.legacy.repository=new ScenarioRepository(f.legacy.db.jdbc(),JsonCodec.shared()){
            @Override public Scenario save(String key,UUID claim,PracticeSessionContextPort.SessionContext context,PracticeSessionContextPort.GoalSnapshot goal,ScenarioService.Request request,GeneratedScenario output,Instant now,Details details,ScenarioDeadline deadline){
                var result=super.save(key,claim,context,goal,request,output,now,details,deadline);
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public int getOrder(){return Integer.MIN_VALUE;}
                    @Override public void beforeCommit(boolean readOnly){f.legacy.clock.advance(Duration.ofSeconds(15));}
                });return result;
            }
        };f.connectService();noIssuedOrReady();
    }
    @Test void transactionDeadlineNeverEnlargesExistingFiniteDatabaseLimits(){
        f.legacy.db.inTx(tx->{
            var jdbc=f.legacy.db.jdbc();var before=jdbc.queryForList("select name,setting::bigint as millis from pg_settings where name in ('lock_timeout','statement_timeout')",Map.of());
            f.legacy.repository.beginDeadline(new ScenarioDeadline(f.legacy.clock,f.legacy.clock.now().plusSeconds(15)));
            for(var setting:before){long initial=((Number)setting.get("millis")).longValue();long limited=jdbc.queryForObject("select setting::bigint from pg_settings where name=:name",Map.of("name",setting.get("name")),Long.class);assertThat(limited).isPositive();if(initial>0)assertThat(limited).isLessThanOrEqualTo(initial);}
            return null;
        });
    }
    @Test void unlimitedDatabaseSettingBecomesFiniteRemainingBudgetLocally(){
        f.legacy.db.inTx(tx->{var jdbc=f.legacy.db.jdbc();jdbc.queryForList("select set_config('statement_timeout','0',true),set_config('lock_timeout','0',true)",Map.of());
            f.legacy.repository.beginDeadline(new ScenarioDeadline(f.legacy.clock,f.legacy.clock.now().plusSeconds(15)));
            var values=jdbc.queryForList("select setting::bigint from pg_settings where name in ('lock_timeout','statement_timeout')",Map.of(),Long.class);
            assertThat(values).hasSize(2).allSatisfy(value->assertThat(value).isBetween(1L,15000L));return null;
        });
    }
    @Test void submillisecondRemainingFailsInsteadOfSettingUnlimitedZero(){
        f.legacy.db.inTx(tx->{assertThatThrownBy(()->f.legacy.repository.beginDeadline(new ScenarioDeadline(f.legacy.clock,f.legacy.clock.now().plusNanos(999999))))
                .isInstanceOf(kr.mujoong.core.common.error.CoreApiException.class);return null;});
    }
}
