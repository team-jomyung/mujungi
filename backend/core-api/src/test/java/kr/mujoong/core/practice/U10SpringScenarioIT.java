package kr.mujoong.core.practice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.config.CoreProperties;
import kr.mujoong.core.knowledge.ChildKnowledgeService;
import kr.mujoong.core.luna.*;
import kr.mujoong.core.luna.adapter.*;
import kr.mujoong.core.textai.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 운영 어댑터 설정과 서비스 자동 주입을 실제 Spring 컨텍스트로 대조한다. 전송은 가짜다. */
@Tag("u10") class U10SpringScenarioIT {
    U10StructuredFixture f;CoreLunaClient client;boolean reject;
    @BeforeEach void open(){
        f=new U10StructuredFixture();client=mock(CoreLunaClient.class);
        when(client.call(any())).thenAnswer(call->{
            LunaRequest request=call.getArgument(0);
            Object result=request.purpose()==LunaPurpose.SCENARIO_GENERATION?f.generated("그림").script():
                    (reject?U10ScenarioGenerationTest.reject("AGE_MISMATCH"):U10ScenarioGenerationTest.approve()).review();
            return new LunaResult(JsonCodec.shared().write(result),"fake-spring",request.promptVersion(),Duration.ZERO,1,false);
        });
    }
    @AfterEach void close(){f.close();}
    ApplicationContextRunner context(){
        var properties=mock(CoreProperties.class,RETURNS_DEEP_STUBS);
        when(properties.timing().knowledge().graphBudget()).thenReturn(Duration.ofSeconds(2));
        when(properties.timing().practice().scenarioHistoryTimeout()).thenReturn(Duration.ofMillis(500));
        return new ApplicationContextRunner().withPropertyValues("OPENAI_API_KEY=synthetic-not-a-key")
                .withUserConfiguration(LunaAdapterConfiguration.class)
                .withBean(CoreLunaClient.class,()->client).withBean(MujoongClock.class,()->f.legacy.clock)
                .withBean(JsonCodec.class,JsonCodec::shared)
                .withBean(ScenarioPrivacyContextPort.class,()->f.privacy).withBean(LunaInputSanitizer.class,()->new LunaInputSanitizer(JsonCodec.shared()))
                .withBean(ChildKnowledgeService.class,()->f.graph).withBean(ForbiddenExpressionPolicy.class,ForbiddenExpressionPolicy::new)
                .withBean(TextAiProperties.class,()->new TextAiProperties(Duration.ofSeconds(3),new TextAiProperties.Retry(1,Duration.ofMillis(250))))
                .withBean("textAiExecutor",java.util.concurrent.ExecutorService.class,()->f.executor)
                .withBean(CoreProperties.class,()->properties).withBean(kr.mujoong.core.consent.ConsentPolicyService.class,()->f.consent)
                .withBean(ScenarioService.class,()->new ScenarioService(f.legacy.repository,id->f.legacy.current.get(),id->f.legacy.exposures.get(),f.accounts,f.legacy.knowledge,f.legacy.ai,f.legacy.clock,f.legacy.db.tx(),new ScenarioConfiguration.ScenarioLimits(Duration.ofSeconds(15))));
    }
    @Test void realAdapterBeanUsesSeparateGenerationAndReviewThroughAutowiredService(){context().run(ctx->{
        assertThat(ctx).hasNotFailed();assertThat(ctx.getBean(TextAiProvider.class)).isInstanceOf(LunaTextAiProvider.class);
        var result=ctx.getBean(ScenarioService.class).generate(f.legacy.original());assertThat(result.selfReview().verdict()).isEqualTo("APPROVE");
        var requests=org.mockito.ArgumentCaptor.forClass(LunaRequest.class);verify(client,times(2)).call(requests.capture());
        assertThat(requests.getAllValues()).extracting(LunaRequest::purpose).containsExactly(LunaPurpose.SCENARIO_GENERATION,LunaPurpose.SCENARIO_REVIEW);
        assertThat(requests.getAllValues()).allSatisfy(r->assertThat(r.outputMode()).isEqualTo(LunaRequest.OutputMode.CONTRACT_SINGLE_CALL));verifyNoInteractions(f.legacy.ai);
    });}
    @Test void realAdapterRejectedReviewsCannotIssueOrUseLegacyGateway(){reject=true;context().run(ctx->{
        assertThat(ctx).hasNotFailed();f.legacy.error(()->ctx.getBean(ScenarioService.class).generate(f.legacy.original()),kr.mujoong.contract.error.ErrorCode.SCENARIO_GEN_FAILED);
        verify(client,times(4)).call(any());verifyNoInteractions(f.legacy.ai);
        assertThat(f.legacy.db.jdbc().queryForObject("select count(*) from core.core_scenario where child_id=:id",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id",f.legacy.child),Integer.class)).isZero();
    });}
}
