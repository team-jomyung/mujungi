package kr.mujoong.realtime.practice;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.mujoong.contract.log.EventLog;
import kr.mujoong.contract.time.DelayQueueTimerDriver;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.contract.time.TimerDriver;
import kr.mujoong.realtime.internalhttp.InternalHttpClient;
import kr.mujoong.realtime.observability.EventLogger;
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
        for (var source : new YamlPropertySourceLoader().load("u5b-runtime", new ClassPathResource("practice/u5b-runtime.yml"))) {
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
            EvaluationProvider evaluations, CueProvider cues, EventLogger events) {
        return session -> new RuntimePracticeExtension(session, sessions.find(session).orElseThrow().guardianId(),
                repository, clock, resources.clock, resources.timers, resources.calls, resources.finalizations,
                settings, scenarios, evaluations, cues,
                failure -> events.appError(EventLog.Component.SESSION_LIFECYCLE, failure));
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
