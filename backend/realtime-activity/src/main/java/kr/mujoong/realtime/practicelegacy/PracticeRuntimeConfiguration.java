package kr.mujoong.realtime.practicelegacy;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.DelayQueueTimerDriver;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.TimerDriver;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.observability.EventLogger;
import kr.mujoong.realtime.practiceflow.PracticeConversation;
import kr.mujoong.realtime.practiceflow.PracticeFlowRecorder;
import kr.mujoong.realtime.practiceflow.PracticeFlowRepository;
import kr.mujoong.realtime.practiceflow.PracticeFlowRouter;
import kr.mujoong.realtime.practiceflow.PracticeFlowSettings;
import kr.mujoong.realtime.practiceflow.PracticeScriptRequester;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.PracticeSessionExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class PracticeRuntimeConfiguration {
    @Bean @ConditionalOnMissingBean(PracticeRepository.class)
    PracticeRepository practiceRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcPracticeRepository(jdbc, new TransactionTemplate(manager));
    }
    @Bean
    PracticeProperties practiceProperties(ConfigurableEnvironment environment) throws IOException {
        var sources = new MutablePropertySources();
        environment.getPropertySources().forEach(sources::addLast);
        for (var source : new YamlPropertySourceLoader().load("practice-legacy-runtime", new ClassPathResource("practice/practice-legacy-runtime.yml"))) {
            sources.addLast(source);
        }
        return new Binder(ConfigurationPropertySources.from(sources)).bind("mujoong.timing.practice", Bindable.of(PracticeProperties.class)).get();
    }
    @Bean(destroyMethod = "close")
    Resources practiceResources(MujoongClock clock) { return new Resources(clock); }
    @Bean @ConditionalOnMissingBean(EvaluationProvider.class)
    EvaluationProvider evaluationProvider() {
        return request -> CompletableFuture.failedFuture(new IllegalStateException("판정 공급자 미연결"));
    }
    @Bean @ConditionalOnMissingBean(CueProvider.class)
    CueProvider cueProvider() { return CueProvider.UNAVAILABLE; }
    @Bean @ConditionalOnMissingBean(PracticePorts.ScenarioPort.class)
    PracticePorts.ScenarioPort practiceScenarios(InternalHttpClient client, Resources resources, PracticeProperties settings) {
        return new PracticeScenarioClient(client, resources.calls, settings.scenarioBudget());
    }
    @Bean @ConditionalOnMissingBean(PracticeSessionExtension.Factory.class)
    PracticeSessionExtension.Factory practiceFactory(ActivitySessionRepository sessions, PracticeRepository repository,
            MujoongClock clock, Resources resources, PracticeProperties settings, PracticePorts.ScenarioPort scenarios,
            EvaluationProvider evaluations, CueProvider cues, EventLogger events, InternalHttpClient client,
            PracticeFlowSettings flowSettings, PracticeFlowRepository flowRepository, PlatformTransactionManager manager) {
        // U12 D1: Factory 시그니처는 그대로 두고 세션마다 얇은 라우터를 만든다. 라우터가 onActive에서 대본 scriptVersion을 보고 새 흐름 또는
        // 이 레거시 확장 한쪽에 위임한다. 다음 목표 대본 요청의 제한 시간은 레거시와 같은 상황 생성 예산(scenarioBudget)을 쓴다(안쪽 재시도 없음)
        PracticeScriptRequester flowScripts = new PracticeScriptRequester(client, resources.calls, settings.scenarioBudget());
        return session -> {
            UUID guardian = sessions.find(session).orElseThrow().guardianId();
            RuntimePracticeExtension legacy = new RuntimePracticeExtension(session, guardian,
                    repository, clock, resources.clock, resources.timers, resources.calls, resources.finalizations,
                    settings, scenarios, evaluations, cues,
                    failure -> events.appError(EventLog.Component.SESSION_LIFECYCLE, failure));
            return new PracticeFlowRouter(legacy, () -> new PracticeConversation(session, guardian, clock, flowSettings,
                    flowScripts, new PracticeFlowRecorder(flowRepository, new TransactionTemplate(manager)),
                    failure -> events.appError(EventLog.Component.SESSION_LIFECYCLE, failure)));
        };
    }
    static final class Resources implements AutoCloseable {
        final EvaluationClock clock;
        final TimerDriver timers;
        final ExecutorService calls = Executors.newFixedThreadPool(8, Thread.ofVirtual().name("practice-call-", 0).factory());
        final ExecutorService finalizations = Executors.newFixedThreadPool(4, Thread.ofVirtual().name("practice-finalize-", 0).factory());
        Resources(MujoongClock source) {
            clock = new EvaluationClock(source);
            timers = new DelayQueueTimerDriver(clock, "practice-evaluation-timer");
        }
        @Override public void close() { timers.close(); calls.shutdownNow(); finalizations.shutdownNow(); }
    }
}
