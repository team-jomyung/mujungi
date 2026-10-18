package kr.mujoong.realtime.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import kr.mujoong.realtime.startup.RealtimeStartupChecks.Inputs;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** GPT-Live 키 {@code OPENAI_API_KEY}는 demo 프로필에서만 필수다(U11 BR12.2). 키 값은 실행 때 만든 가짜 문자열이다(R-F3). */
@Tag("judgment-turn")
class OpenAiKeyStartupChecksTest {

    private static final URI LOOPBACK = URI.create("ws://127.0.0.1:9/fake-provider");

    private final String token = TestSecrets.internalServiceToken();
    private final String owner = TestSecrets.ownerHashSecret();

    private List<String> problems(String key, URI url, String... profiles) {
        return RealtimeStartupChecks.problems(new Inputs(token, key, owner, url, List.of(profiles)));
    }

    @Test
    void demoWithoutTheKeyIsRejectedByNameOnly() {
        URI allowed = URI.create("wss://" + kr.mujoong.realtime.voice.ProviderHosts.ALLOWED_HOSTS.iterator().next() + "/v1/live/sessions");

        assertThat(problems(null, allowed, "demo")).containsExactly(RealtimeStartupChecks.OPENAI_API_KEY);
        assertThat(problems("  ", allowed, "demo")).containsExactly(RealtimeStartupChecks.OPENAI_API_KEY);
        assertThat(problems(TestSecrets.base64Url(24), allowed, "demo")).isEmpty();
    }

    @Test
    void otherProfilesStartWithoutTheKey() {
        assertThat(problems(null, LOOPBACK, "test")).isEmpty();
        assertThat(problems("", LOOPBACK, "test", "ci")).isEmpty();
        assertThat(problems(null, LOOPBACK)).as("프로필 없음(로컬)").isEmpty();
    }

    @Test
    void theKeyNameIsTheUnifiedOne() {
        assertThat(RealtimeStartupChecks.OPENAI_API_KEY).isEqualTo("OPENAI_API_KEY");
    }
}
