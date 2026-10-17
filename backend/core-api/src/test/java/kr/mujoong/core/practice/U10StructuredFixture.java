package kr.mujoong.core.practice;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.knowledge.ChildKnowledgeService;
import kr.mujoong.core.luna.LunaInputSanitizer;
import kr.mujoong.core.textai.*;

final class U10StructuredFixture implements AutoCloseable {
    final ScenarioServiceTest legacy=new ScenarioServiceTest();
    final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    final TextAiProvider transport=mock(TextAiProvider.class,withSettings().extraInterfaces(ScenarioScriptProvider.class));
    final ScenarioScriptProvider provider=(ScenarioScriptProvider)transport;
    final ScenarioPrivacyContextPort privacy=mock(ScenarioPrivacyContextPort.class);
    final ChildKnowledgeService graph=mock(ChildKnowledgeService.class);
    final kr.mujoong.core.consent.ConsentPolicyService consent=mock(kr.mujoong.core.consent.ConsentPolicyService.class);
    JdbcTestPracticeAccounts accounts;
    U10StructuredFixture(){
        legacy.setUp();
        accounts=spy(new JdbcTestPracticeAccounts(legacy.db.jdbc()));
        legacy.service=new ScenarioService(legacy.repository,id->legacy.current.get(),id->legacy.exposures.get(),accounts,legacy.knowledge,legacy.ai,legacy.clock,legacy.db.tx(),new ScenarioConfiguration.ScenarioLimits(Duration.ofSeconds(15)));
        when(consent.check(any())).thenReturn(new kr.mujoong.core.consent.ConsentPolicyService.GateResult(true,kr.mujoong.core.consent.ConsentStatusRule.Status.GIVEN,null));
        legacy.service.connectConsent(consent);
        when(privacy.require(legacy.guardian,legacy.child)).thenReturn(new ScenarioPrivacyContextPort.Context("합성아이"));
        var properties=mock(CoreProperties.class,RETURNS_DEEP_STUBS);
        when(properties.timing().knowledge().graphBudget()).thenReturn(Duration.ofSeconds(2));
        when(properties.timing().practice().scenarioHistoryTimeout()).thenReturn(Duration.ofMillis(500));
        legacy.service.connectStructured(transport,privacy,new LunaInputSanitizer(JsonCodec.shared()),graph,new ForbiddenExpressionPolicy(),
                new TextAiProperties(Duration.ofSeconds(3),new TextAiProperties.Retry(1,Duration.ofMillis(250))),executor,properties,JsonCodec.shared());
        when(provider.generate(any())).thenAnswer(c->generated("그림"));
        when(provider.review(any())).thenReturn(U10ScenarioGenerationTest.approve());
    }
    ScenarioScriptProvider.Generated generated(String material){
        var s=new ScenarioScript.Scene("base","BASE",material+" 놀이 중. "+legacy.opportunity+" 이제 그만할래.","이제 그만할래.","친구가 쉬려 해.",List.of(),List.of(),"PEER","교실","놀이",null,null);
        return new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(s)),"fake");
    }
    @Override public void close(){executor.shutdownNow();legacy.cleanUp();}
}
