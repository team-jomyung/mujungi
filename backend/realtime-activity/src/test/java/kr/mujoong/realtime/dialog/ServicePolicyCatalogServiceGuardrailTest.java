package kr.mujoong.realtime.dialog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import kr.mujoong.contract.status.ActivityType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("service-guardrail")
class ServicePolicyCatalogServiceGuardrailTest {

    @Test
    void packagedBytesAndManifestAgreeAndRetainKorean() {
        PolicyBundle bundle = new ServicePolicyCatalog().freeTalk();
        assertThat(bundle.promptVersion()).isEqualTo("service-0.1-app.2");
        assertThat(bundle.instructions()).contains("AI 인형 무중", "비밀 보장을 약속하지 않는다", "같은 정보를 얻기 위한 도움");
        assertThat(bundle.appliedSha256()).isEqualTo(ServicePolicyCatalog.sha256(
                bundle.instructions().getBytes(StandardCharsets.UTF_8)));
        assertThat(bundle.sourceSha256()).isEqualTo("1d92f114af7b48cefd86ed3df03feea30cdecc6ac546b128c236427f3d7fe5b7");
        assertThat(bundle.toString()).doesNotContain("너는", "보호자", "instructions=");
    }

    @Test
    void missingManifestAndMissingBodyFailWithoutFallback() {
        assertThatThrownBy(() -> new ServicePolicyCatalog(resources(Map.of())))
                .isInstanceOf(IllegalStateException.class);
        Map<String, byte[]> files = fixture("본문".getBytes(StandardCharsets.UTF_8), null);
        assertThatThrownBy(() -> new ServicePolicyCatalog(resources(Map.of(
                ServicePolicyCatalog.MANIFEST, files.get(ServicePolicyCatalog.MANIFEST)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankBodyFailsEvenWithMatchingDigest() {
        assertThatThrownBy(() -> new ServicePolicyCatalog(resources(fixture(" \n".getBytes(StandardCharsets.UTF_8), null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("비었다");
    }

    @Test
    void hashMismatchFailsBeforeSelectingPolicy() {
        assertThatThrownBy(() -> new ServicePolicyCatalog(resources(fixture("본문".getBytes(StandardCharsets.UTF_8), "0".repeat(64)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("해시");
    }

    @Test
    void conflictingVersionAndEmptyCatalogAreRejected() {
        PolicyBundle first = new ServicePolicyCatalog().freeTalk();
        PolicyBundle conflict = new PolicyBundle(first.promptVersion(), first.policyVersion(), first.sourceSha256(),
                "0".repeat(64), "다른 본문");
        assertThatThrownBy(() -> new ServicePolicyCatalog(List.of(first, conflict)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("충돌");
        assertThatThrownBy(() -> new ServicePolicyCatalog(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ServicePolicyCatalog(List.of(first, first)).freeTalk()).isSameAs(first);
    }

    @Test
    void selectedSessionReferenceRemainsFixedWithoutAnotherResourceRead() {
        byte[] body = "처음 정책".getBytes(StandardCharsets.UTF_8);
        ServicePolicyCatalog catalog = new ServicePolicyCatalog(resources(fixture(body, null)));
        PolicyBundle pinned = catalog.select(ActivityType.FREE_TALK).orElseThrow();
        body[0] = 0;
        assertThat(catalog.select(ActivityType.FREE_TALK).orElseThrow()).isSameAs(pinned);
        assertThat(pinned.instructions()).isEqualTo("처음 정책");
    }

    @Test
    void practiceNeverSelectsFreeTalkPolicy() {
        ServicePolicyCatalog catalog = new ServicePolicyCatalog();
        assertThat(catalog.select(ActivityType.PRACTICE)).isEmpty();
        assertThat(catalog.select(ActivityType.FREE_TALK)).contains(catalog.freeTalk());
        assertThatThrownBy(() -> catalog.select(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void malformedUtf8AndIdentifiersFailExplicitly() {
        assertThatThrownBy(() -> new ServicePolicyCatalog(resources(fixture(new byte[] {(byte) 0xc3, 0x28}, null))))
                .isInstanceOf(java.io.UncheckedIOException.class);
        assertThatThrownBy(() -> new PolicyBundle("../policy", "service-0.1", "0".repeat(64), "0".repeat(64), "본문"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PolicyBundle("valid", "service-0.1", "invalid", "0".repeat(64), "본문"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, byte[]> fixture(byte[] body, String hash) {
        String manifest = "promptVersion=test-v1\npolicyVersion=service-0.1\nsourceSha256=" + "0".repeat(64)
                + "\nappliedSha256=" + (hash == null ? ServicePolicyCatalog.sha256(body) : hash)
                + "\nresource=service-policy/test.txt\n";
        return Map.of(ServicePolicyCatalog.MANIFEST, manifest.getBytes(StandardCharsets.UTF_8),
                "service-policy/test.txt", body);
    }

    private static ClassLoader resources(Map<String, byte[]> files) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                byte[] bytes = files.get(name);
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }
        };
    }
}
