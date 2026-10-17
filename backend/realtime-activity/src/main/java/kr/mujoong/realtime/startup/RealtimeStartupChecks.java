package kr.mujoong.realtime.startup;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import kr.mujoong.contract.log.OwnerHasher;
import kr.mujoong.realtime.config.RealtimeProperties;
import kr.mujoong.realtime.content.LiveContentResources;
import kr.mujoong.realtime.gate.SeedDictionary;
import kr.mujoong.realtime.voice.ProviderHosts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Realtime 기동 점검 S1b (U1 3.5 Step 26 · RD-12 · R3-12 · SD-12 · SD-18 · SD-25 · A5 · J124 · J141).
 *
 * <p>빈 생성 단계(웹 서버가 포트를 열기 전)에서 기동 거부 조건 셋을 본다. 하나라도 걸리면 표준 오류에 거부 줄(환경 변수 · 키 이름과 사유
 * 열거값만, 값 없음)을 쓰고 예외로 기동을 끝낸다(main 스레드 예외 → 종료 코드 1).
 *
 * <ol>
 *   <li>필수 비밀: {@value #INTERNAL_TOKEN}(base64url {@value #MIN_INTERNAL_TOKEN_CHARS}자 이상 = 256비트) · {@value #OPENAI_API_KEY}
 *       (demo 프로필만, U11 BR12.2 — 다른 프로필은 키 없이 가짜 공급자에 연결) · {@value #OWNER_HASH_SECRET}(demo · prod 필수, 값이 있으면 32바이트 이상, OD-4).
 *   <li>공급자 주소 {@code mujoong.provider.url}: 비면 거부(J141), demo · prod는 허용 호스트 목록 밖이면 거부(SD-25), test 프로필은 루프백
 *       호스트만(J141). 규칙은 {@link ProviderHosts} 한 곳이다.
 *   <li>시드 사전 로딩: 파일 없음 · 파싱 실패 · 중복 id · 빈 범주 · 패턴 문법 위반이면 거부(SD-18 · A5). 로딩한 사전은
 *       {@link #dictionary()}로 출력 게이트와 S2b가 쓴다(읽기 전용 상수, 핫 리로드 없음).
 *   <li>U8 판 리소스 적재: 판정 선택지 판 · 가드레일 정책 판이 없거나 형식이 틀리면 거부(BR2.1 · SD5). 로딩한 판은
 *       {@link #liveContent()}로 세션 콘텐츠 계획({@code LiveContentRegistry})이 쓴다.
 * </ol>
 *
 * <p>Core에도 같은 비밀 규칙의 점검이 따로 있다(LC-11). 내부 토큰 형식 판정 {@link #isValidInternalToken}은 내부 HTTP 인증 장치도 쓴다.
 */
@Component
public class RealtimeStartupChecks {

    /** 내부 토큰 환경 변수 이름(SD-8). */
    public static final String INTERNAL_TOKEN = "INTERNAL_SERVICE_TOKEN";
    /** GPT-Live 공급자 키 환경 변수 이름(U11 BR12.1: Luna 판정 키와 하나로 통일). */
    public static final String OPENAI_API_KEY = "OPENAI_API_KEY";
    /** ownerHash 비밀 환경 변수 이름(OD-4). */
    public static final String OWNER_HASH_SECRET = "MUJOONG_OBSERVABILITY_OWNER_HASH_SECRET";
    /** 공급자 주소 설정 키(J109 · J141). */
    public static final String PROVIDER_URL_KEY = "mujoong.provider.url";
    /** 내부 토큰 최소 길이(base64url 43자 = 256비트, J124). */
    public static final int MIN_INTERNAL_TOKEN_CHARS = 43;

    /** 거부 줄의 errorKind. */
    static final String ERROR_KIND = "RealtimeStartupRejected";

    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final String DEMO_PROFILE = "demo";
    private static final Set<String> PUBLIC_PROFILES = Set.of("demo", "prod");

    private final SeedDictionary dictionary;
    private final LiveContentResources liveContent;

    /** Spring 생성자: 환경 변수 · 설정 · classpath 사전 · 판 리소스로 점검한다. */
    @Autowired
    public RealtimeStartupChecks(Environment environment, RealtimeProperties properties) {
        this(new Inputs(
                        environment.getProperty(INTERNAL_TOKEN),
                        environment.getProperty(OPENAI_API_KEY),
                        properties.observability().ownerHashSecret(),
                        properties.provider().url(),
                        List.of(environment.getActiveProfiles())),
                SeedDictionary::loadDefault, LiveContentResources::loadDefault);
    }

    /**
     * 시험 · 대역 생성자(U1 모양). 판 리소스는 classpath 기본 판을 읽는다.
     *
     * @param dictionaryLoader 사전 적재기(운영은 classpath 리소스)
     */
    public RealtimeStartupChecks(Inputs inputs, Supplier<SeedDictionary> dictionaryLoader) {
        this(inputs, dictionaryLoader, LiveContentResources::loadDefault);
    }

    /**
     * 시험 · 대역 생성자(U8). 판 리소스(판정 선택지 · 가드레일 정책) 적재가 실패해도 기동을 거부한다(BR2.1 · NFR2.3 · SD5).
     *
     * @param dictionaryLoader 사전 적재기(운영은 classpath 리소스)
     * @param liveContentLoader 판 리소스 적재기(운영은 classpath 리소스)
     */
    public RealtimeStartupChecks(Inputs inputs, Supplier<SeedDictionary> dictionaryLoader,
            Supplier<LiveContentResources> liveContentLoader) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(dictionaryLoader, "dictionaryLoader");
        Objects.requireNonNull(liveContentLoader, "liveContentLoader");
        List<String> problems = problems(inputs);
        if (!problems.isEmpty()) {
            throw reject("기동 점검 S1b 거부(값 미출력): " + String.join(", ", problems));
        }
        try {
            this.dictionary = Objects.requireNonNull(dictionaryLoader.get(), "dictionary");
        } catch (SeedDictionary.LoadException e) {
            // 메시지는 사유 열거값과 항목 id · 범주 이름뿐이다(패턴 · 전사 값 없음, SD-18)
            throw reject("기동 점검 S1b 거부: " + e.getMessage());
        }
        try {
            this.liveContent = Objects.requireNonNull(liveContentLoader.get(), "liveContent");
        } catch (LiveContentResources.LoadException e) {
            // 메시지는 사유 열거값과 자원 · 지점 · 키 이름뿐이다(질문 · 선택지 · 프롬프트 원문 없음, U8 SD5)
            throw reject("기동 점검 S1b 거부: " + e.getMessage());
        }
    }

    /** 로딩을 마친 시드 사전(읽기 전용). */
    public SeedDictionary dictionary() {
        return dictionary;
    }

    /** 로딩을 마친 판 리소스(읽기 전용, U8). */
    public LiveContentResources liveContent() {
        return liveContent;
    }

    /**
     * 문제가 있는 환경 변수 · 설정 키 이름 목록(값 없음). 비어 있으면 통과.
     */
    public static List<String> problems(Inputs inputs) {
        List<String> problems = new ArrayList<>();
        String[] profiles = inputs.activeProfiles().toArray(String[]::new);
        if (!isValidInternalToken(inputs.internalToken())) {
            problems.add(INTERNAL_TOKEN);
        }
        // U11 BR12.2: GPT-Live 키는 demo 프로필에서만 필수다(CI · 검증 · 로컬은 키 없이 가짜 공급자에 연결)
        if (inputs.activeProfiles().contains(DEMO_PROFILE) && (inputs.openAiApiKey() == null || inputs.openAiApiKey().isBlank())) {
            problems.add(OPENAI_API_KEY);
        }
        boolean publicEnvironment = Arrays.stream(profiles).anyMatch(PUBLIC_PROFILES::contains);
        String ownerSecret = inputs.ownerHashSecret();
        boolean ownerSecretBlank = ownerSecret == null || ownerSecret.isBlank();
        if ((ownerSecretBlank && publicEnvironment)
                || (!ownerSecretBlank && ownerSecret.getBytes(StandardCharsets.UTF_8).length < OwnerHasher.MIN_SECRET_BYTES)) {
            problems.add(OWNER_HASH_SECRET);
        }
        Optional<ProviderHosts.Problem> provider = ProviderHosts.check(inputs.providerUrl(), profiles);
        provider.ifPresent(problem -> problems.add(PROVIDER_URL_KEY + "(" + problem + ")"));
        return problems;
    }

    /** 내부 토큰 형식(base64url {@value #MIN_INTERNAL_TOKEN_CHARS}자 이상)인가. */
    public static boolean isValidInternalToken(String token) {
        return token != null && token.length() >= MIN_INTERNAL_TOKEN_CHARS && BASE64URL.matcher(token).matches();
    }

    private static StartupRejectedException reject(String reason) {
        StartupFailureLine.print(ERROR_KIND, reason);
        return new StartupRejectedException(reason);
    }

    /**
     * 점검 입력. 문자열 표현에 비밀 값을 내지 않는다.
     *
     * @param activeProfiles 활성 프로필
     */
    public record Inputs(
            String internalToken, String openAiApiKey, String ownerHashSecret, URI providerUrl,
            List<String> activeProfiles) {

        public Inputs {
            activeProfiles = activeProfiles == null ? List.of() : List.copyOf(activeProfiles);
        }

        @Override
        public String toString() {
            return "Inputs[profiles=" + activeProfiles + "]";
        }
    }

    /** 기동 거부(S1b · S2 · S2b). 메시지는 키 · 표 이름과 사유뿐이다. */
    public static final class StartupRejectedException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public StartupRejectedException(String reason) {
            super(reason);
        }
    }
}
