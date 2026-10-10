package kr.mujoong.realtime.testing;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.mujoong.realtime.RealtimeApplication;
import kr.mujoong.realtime.gateway.WebSocketConfig;
import kr.mujoong.realtime.startup.RealtimeStartupChecks;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Realtime 앱 전체를 test 프로필로 띄우는 시험 보조(Step 30 · 33). 공용 PostgreSQL(앱 역할 realtime_app) · 계약 대역(Core) · 가짜 공급자
 * 주소를 명령행 인자(설정 파일보다 우선)로 넘기고, 비밀(내부 토큰 · 공급자 키)은 실행마다 만든다. 웹 서버는 무작위 포트다. 시험 소스 클래스는
 * {@link ItTypeExcludeFilter}로 스캔에서 빼고, 기기는 운영 끝점({@link WebSocketConfig#DEVICE_PATH}, Step 33)으로 붙는다.
 */
public final class RealtimeTestApp implements AutoCloseable {

    private final ConfigurableApplicationContext context;
    private final String internalToken;

    private RealtimeTestApp(ConfigurableApplicationContext context, String internalToken) {
        this.context = context;
        this.internalToken = internalToken;
    }

    /** 기동 인자 묶음. */
    public static Map<String, String> defaults(MujoongPostgres postgres, URI coreBaseUrl, URI providerUrl,
            String internalToken) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("server.port", "0");
        args.put("spring.datasource.url", postgres.jdbcUrl());
        args.put("spring.datasource.username", postgres.username(Role.REALTIME_APP));
        args.put("spring.datasource.password", postgres.password(Role.REALTIME_APP));
        args.put(RealtimeStartupChecks.INTERNAL_TOKEN, internalToken);
        args.put(RealtimeStartupChecks.PROVIDER_API_KEY, TestSecrets.base64Url(24));
        args.put("mujoong.internal-http.core-base-url", coreBaseUrl.toString());
        args.put("mujoong.provider.url", providerUrl.toString());
        return args;
    }

    /** 띄운다(test 프로필 + 추가 프로필). */
    public static RealtimeTestApp start(Map<String, String> args, String... extraProfiles) {
        return start(args, context -> { }, extraProfiles);
    }

    /**
     * 띄운다. {@code initializer}는 로깅 초기화 뒤 · 빈 생성 전에 불린다(로그 수집기 · 수신기 등록).
     */
    public static RealtimeTestApp start(Map<String, String> args,
            ApplicationContextInitializer<ConfigurableApplicationContext> initializer, String... extraProfiles) {
        List<String> profiles = new ArrayList<>();
        profiles.add("test");
        profiles.addAll(List.of(extraProfiles));
        String[] commandLine = args.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RealtimeApplication.class)
                .profiles(profiles.toArray(String[]::new))
                .initializers(applicationContext -> applicationContext.getBeanFactory()
                        .registerSingleton("itTypeExcludeFilter", new ItTypeExcludeFilter()), initializer)
                .run(commandLine);
        return new RealtimeTestApp(context, args.get(RealtimeStartupChecks.INTERNAL_TOKEN));
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    /** 웹 서버 포트. */
    public int port() {
        return Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    /** 기기 WSS 주소(운영 끝점). */
    public URI deviceUri() {
        return URI.create("ws://127.0.0.1:" + port() + WebSocketConfig.DEVICE_PATH);
    }

    /** 내부 API 주소. */
    public URI internalUri(String pathAndQuery) {
        return URI.create("http://127.0.0.1:" + port() + pathAndQuery);
    }

    public String internalToken() {
        return internalToken;
    }

    @Override
    public void close() {
        context.close();
    }
}
