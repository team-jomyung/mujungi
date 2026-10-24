package kr.mujoong.realtime.usage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.status.ActivityType;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.realtime.session.ActivitySessionRepository;
import kr.mujoong.realtime.session.ChildContext;
import kr.mujoong.realtime.session.ConsentSnapshot;
import kr.mujoong.realtime.session.NewActivitySession;
import kr.mujoong.realtime.testing.RealtimeDb;
import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** 명시적으로 실행하는 실호출 시험의 설정·합성 세션·숫자 보고서. 기본 test에서는 호출하지 않는다. */
public final class AiUsageLiveSupport {
    private AiUsageLiveSupport() { }

    public static void requireKey(String source) throws IOException {
        Path dir = Path.of("build/ai-usage-live");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(source + ".txt"), "REAL_API_SYNTHETIC · 현재 실행" + System.lineSeparator());
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            report(source, "SKIPPED_NO_KEY · 실호출 없음 · 통과 아님");
        }
        Assumptions.assumeTrue(key != null && !key.isBlank(), "SKIPPED_NO_KEY: 실호출 안 함, 통과 아님");
    }

    public static <T> T setting(String prefix, Class<T> type) throws IOException {
        var sources = new YamlPropertySourceLoader().load("usage-live-config", new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(sources)).bind(prefix, Bindable.of(type)).get();
    }

    public static UUID session(RealtimeDb db, MujoongClock clock) {
        var row = new NewActivitySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ActivityType.FREE_TALK, UUID.randomUUID().toString(), new ConsentSnapshot("1.0", true, true, false, false),
                new ChildContext("합성", 8, List.of("그림")), null, null);
        db.inTransaction(() -> new ActivitySessionRepository(db.jdbc()).insert(row, clock.now()));
        return row.id();
    }

    public static synchronized void report(String source, String line) throws IOException {
        Path dir = Path.of("build/ai-usage-live");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(source + ".txt"), line + System.lineSeparator(), StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
