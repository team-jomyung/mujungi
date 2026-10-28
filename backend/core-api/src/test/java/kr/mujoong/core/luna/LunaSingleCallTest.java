package kr.mujoong.core.luna;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.mujoong.core.support.TestEvents;
import kr.mujoong.testsupport.luna.LunaResponsesStub;
import kr.mujoong.testsupport.luna.LunaResponsesStub.Reply;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;

@Tag("scenario-generation")
class LunaSingleCallTest {
    LunaResponsesStub stub;MutableClock clock;CoreLunaClient client;
    @BeforeEach void open(){stub=LunaResponsesStub.start();clock=new MutableClock();client=LunaTestFixtures.client(stub.baseUrl(),clock,new TestEvents(clock),new LunaTestFixtures.ClockSleeper(clock),LunaTestFixtures.timing());}
    @AfterEach void close(){stub.close();}
    LunaRequest request(){return new LunaRequest(LunaPurpose.SCENARIO_REVIEW,"scenario-review.v1","검토한다",Map.of("age",8),Map.of(),clock.now().plusSeconds(15),LunaRequest.OutputMode.CONTRACT_SINGLE_CALL);}
    @Test void singleCallUsesContractAndRecordsOneAttempt(){stub.respond(Reply.ok("fake","{\"verdict\":\"APPROVE\",\"reasons\":[],\"findings\":[]}"));var result=client.call(request());assertThat(result.attemptsUsed()).isEqualTo(1);assertThat(stub.requests()).hasSize(1);assertThat(stub.requests().getFirst().body()).contains("json_schema","scenario_review").doesNotContain("json_object");}
    @Test void transientFailuresNeverRetryInsideClient(){for(var reply:List.of(Reply.status(500),Reply.rateLimited(1),Reply.emptyOutput("fake"))){int before=stub.requests().size();stub.respond(reply);assertThatThrownBy(()->client.call(request())).isInstanceOf(LunaException.class);assertThat(stub.requests()).hasSize(before+1);}}
    @Test void schemaFailureDoesNotConsumeAnotherProviderCall(){stub.respond(Reply.ok("fake","{\"wrong\":true}"));assertThatThrownBy(()->client.call(request())).isInstanceOfSatisfying(LunaException.class,e->assertThat(e.reason()).isEqualTo(LunaFailureReason.SCHEMA_INVALID));assertThat(stub.requests()).hasSize(1);}
    @Test void approvalWithReasonIsNotAccepted(){stub.respond(Reply.ok("fake","{\"verdict\":\"APPROVE\",\"reasons\":[\"AGE_MISMATCH\"]}"));assertThatThrownBy(()->client.call(request())).isInstanceOf(LunaException.class);assertThat(stub.requests()).hasSize(1);}
    @Test void permanentFailureIsNotRetried(){stub.respond(Reply.status(401));assertThatThrownBy(()->client.call(request())).isInstanceOfSatisfying(LunaException.class,e->assertThat(e.reason()).isEqualTo(LunaFailureReason.AUTH_FAILED));assertThat(stub.requests()).hasSize(1);}
    @Test void retryAfterIsPreservedForOuterBudget(){stub.respond(Reply.rateLimited(1));assertThatThrownBy(()->client.call(request())).isInstanceOfSatisfying(LunaException.class,e->assertThat(e.retryAfter()).contains(Duration.ofSeconds(1)));assertThat(stub.requests()).hasSize(1);}
    @Test void expiredCallIsNeverSentAndSharedOtherUnitBudgetStillHasThreeAttempts(){var request=request();clock.advance(Duration.ofSeconds(15));assertThatThrownBy(()->client.call(request)).isInstanceOf(LunaException.class);assertThat(stub.requests()).isEmpty();var budget=new LunaCallBudget();assertThat(budget.takeAttempt()).isTrue();assertThat(budget.takeAttempt()).isTrue();assertThat(budget.takeAttempt()).isTrue();assertThat(budget.takeAttempt()).isFalse();}
}
