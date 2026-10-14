package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.textai.TextAiProperties;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.*;
@Tag("u6")
class ResultTextGatewayTest {
    ExecutorService executor;MutableClock clock;FakeResultTextProvider provider;
    @BeforeEach void setup(){executor=Executors.newVirtualThreadPerTaskExecutor();clock=new MutableClock();provider=new FakeResultTextProvider();}
    @AfterEach void close(){executor.shutdownNow();}
    static TextAiProperties properties(Duration timeout){return new TextAiProperties(Duration.ofSeconds(3),new TextAiProperties.Retry(1,Duration.ofMillis(250)),new TextAiProperties.Result(timeout,0,0.2,"v1"));}
    ResultTextGateway gateway(Duration timeout){return new ResultTextGateway(provider,properties(timeout),clock,executor,JsonCodec.shared());}
    @Test void defaultProviderIsUnavailable(){var gateway=new ResultTextGateway(new ResultTextConfiguration().unavailableResultProvider(),properties(Duration.ofSeconds(10)),clock,executor,JsonCodec.shared());assertThatThrownBy(()->gateway.call("sentences",Map.of(),Map.of(),clock.now().plusSeconds(30))).hasMessage("PROVIDER_UNAVAILABLE");}
    @Test void requestTimeoutCancelsAndDoesNotRetry(){var interrupted=new CountDownLatch(1);provider.response=r->{try{new CountDownLatch(1).await();return "{}";}catch(InterruptedException e){interrupted.countDown();throw new ResultAiException("TIMEOUT");}};assertThatThrownBy(()->gateway(Duration.ofMillis(50)).call("sentences",Map.of(),Map.of(),clock.now().plusSeconds(30))).hasMessage("TIMEOUT");assertThat(provider.requests).hasSize(1);try{assertThat(interrupted.await(2,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new AssertionError(e);}}
    @Test void malformedAndExtraFieldsAreRejected(){provider.response=r->"{\"sentences\":[],\"secret\":1}";assertThatThrownBy(()->gateway(Duration.ofSeconds(1)).call("sentences",Map.of(),Map.of(),clock.now().plusSeconds(30))).hasMessage("SCHEMA_INVALID");}
    @Test void namesIdentifiersAudioAndBackgroundAreRemovedFromNonStructureRequest(){UUID session=UUID.randomUUID(),goal=UUID.randomUUID();provider.response=r->"{\"sentences\":[]}";gateway(Duration.ofSeconds(1)).call("sentences",Map.of("sessionId",session,"goalSelectionId",goal,"audioRefs","private.wav","knownContent","보호자 비밀","text","민수는 함께 놀자고 했어"),Map.of("민수","아이"),clock.now().plusSeconds(30));String sent=JsonCodec.shared().write(provider.requests.getFirst().input());assertThat(sent).doesNotContain("민수",session.toString(),goal.toString(),"private.wav","보호자 비밀").contains("아이","참조1");assertThat(provider.requests.getFirst().toString()).doesNotContain("함께 놀자");}
    @Test void structureAloneReceivesBackground(){provider.response=r->"{\"characters\":[],\"situation\":null,\"partnerUtterance\":null,\"childResponse\":null,\"explanation\":null}";gateway(Duration.ofSeconds(1)).call("structure",Map.of("knownContent","보호자 배경"),Map.of(),clock.now().plusSeconds(30));assertThat(provider.requests.getFirst().input()).containsEntry("knownContent","보호자 배경");}
    @Test void providerFailureDoesNotExposeItsBodyOrRetry(){provider.response=r->{throw new IllegalStateException("민감 원문");};assertThatThrownBy(()->gateway(Duration.ofSeconds(1)).call("sentences",Map.of(),Map.of(),clock.now().plusSeconds(30))).hasMessage("PROVIDER_ERROR").hasNoCause();assertThat(provider.requests).hasSize(1);}
}
