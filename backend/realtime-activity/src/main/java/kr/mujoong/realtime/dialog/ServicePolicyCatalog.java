package kr.mujoong.realtime.dialog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import kr.mujoong.contract.status.ActivityType;
import org.springframework.stereotype.Component;

/** 앱 초기화 때 바이트와 판을 검증한다. 일반 턴에서는 불변 참조만 돌려준다. */
@Component
public final class ServicePolicyCatalog {

    public static final String MANIFEST = "service-policy/manifest.properties";
    private final PolicyBundle freeTalk;

    public ServicePolicyCatalog() {
        this(ServicePolicyCatalog.class.getClassLoader());
    }

    ServicePolicyCatalog(ClassLoader resources) {
        Properties manifest = new Properties();
        try (InputStream in = open(resources, MANIFEST)) {
            manifest.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("서비스 정책 manifest 읽기 실패", e);
        }
        freeTalk = load(resources, manifest);
    }

    /** 중복 판은 같은 바이트에만 허용한다. 시험과 오프라인 패키지 검증도 같은 경계를 쓴다. */
    ServicePolicyCatalog(List<PolicyBundle> bundles) {
        Map<String, PolicyBundle> versions = new HashMap<>();
        for (PolicyBundle bundle : bundles) {
            PolicyBundle previous = versions.putIfAbsent(bundle.promptVersion(), bundle);
            if (previous != null && !previous.equals(bundle)) {
                throw new IllegalArgumentException("동일 서비스 정책 판의 내용이 충돌한다");
            }
        }
        if (versions.size() != 1) {
            throw new IllegalArgumentException("현재 서비스 정책 판은 하나여야 한다");
        }
        freeTalk = versions.values().iterator().next();
    }

    public Optional<PolicyBundle> select(ActivityType mode) {
        return Objects.requireNonNull(mode, "mode") == ActivityType.FREE_TALK
                ? Optional.of(freeTalk) : Optional.empty();
    }

    public PolicyBundle freeTalk() {
        return freeTalk;
    }

    private static PolicyBundle load(ClassLoader resources, Properties manifest) {
        String path = manifest.getProperty("resource");
        if (path == null || !path.matches("service-policy/[A-Za-z0-9._-]+\\.txt")) {
            throw new IllegalArgumentException("서비스 정책 자원 경로가 올바르지 않다");
        }
        try (InputStream in = open(resources, path)) {
            byte[] bytes = in.readAllBytes();
            String hash = sha256(bytes);
            if (!hash.equals(manifest.getProperty("appliedSha256"))) {
                throw new IllegalArgumentException("서비스 정책 해시가 일치하지 않는다");
            }
            String body = decode(bytes);
            return new PolicyBundle(manifest.getProperty("promptVersion"), manifest.getProperty("policyVersion"),
                    manifest.getProperty("sourceSha256"), hash, body);
        } catch (IOException e) {
            throw new UncheckedIOException("서비스 정책 읽기 실패", e);
        }
    }

    private static InputStream open(ClassLoader resources, String path) {
        InputStream stream = resources.getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("서비스 정책 자원이 없다");
        }
        return stream;
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("필수 SHA-256 알고리즘이 없다", e);
        }
    }
}
