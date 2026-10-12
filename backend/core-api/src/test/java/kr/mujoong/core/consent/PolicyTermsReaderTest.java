package kr.mujoong.core.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.catalog.ApprovedContentRepository;
import kr.mujoong.core.catalog.ContentCatalogService;
import kr.mujoong.core.catalog.ContentCatalogService.PinnedContent;
import kr.mujoong.core.consent.ConsentStatusRule.Type;
import kr.mujoong.core.consent.PolicyTermsDevSeed.TermsSeed;
import kr.mujoong.core.consent.PolicyTermsReader.CurrentPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 02-1 약관 본문 읽기(U2 · R7 · AC1.2.2 · AC1.2.9 · 편차 (U2-c)): 네 항목 · 생성형 AI 안내 절 위치 · 형식 오류는 닫힌 쪽. */
@Tag("u2")
class PolicyTermsReaderTest {

    private final ApprovedContentRepository repository = mock(ApprovedContentRepository.class);
    private final ContentCatalogService catalog = mock(ContentCatalogService.class);
    private final PolicyTermsReader reader = new PolicyTermsReader(repository, catalog, JsonCodec.shared());

    @BeforeEach
    void seedTerms() {
        when(repository.findCurrentPolicyVersion()).thenReturn(Optional.of("1.0"));
        for (TermsSeed seed : PolicyTermsDevSeed.TERMS) {
            when(catalog.latestApproved(seed.code())).thenReturn(Optional.of(new PinnedContent(seed.versionId(), seed.text(), null)));
        }
    }

    @Test
    void readsTheFourItemsInScreenOrderWithTheAiNoticeRightBeforeIfDenied() {
        CurrentPolicy policy = reader.current().orElseThrow();

        assertThat(policy.policyVersion()).isEqualTo("1.0");
        assertThat(policy.items()).extracting(item -> item.type())
                .containsExactly(Type.VOICE_AND_TEXT, Type.GUARDIAN_VIEW, Type.REPORT_SHARING, Type.RAW_AUDIO);
        assertThat(policy.items()).allSatisfy(item -> {
            assertThat(item.sections()).extracting(section -> section.heading())
                    .containsSubsequence(PolicyTermsReader.AI_NOTICE_HEADING, PolicyTermsReader.IF_DENIED_HEADING);
            assertThat(item.sections().get(item.sections().size() - 1).heading()).isEqualTo("동의하지 않으면");
            assertThat(item.sections().get(item.sections().size() - 2).body()).contains("생성형 AI");
        });
        assertThat(policy.items().get(0).title()).isEqualTo("음성 수집 · 글로 옮겨 저장");
        assertThat(policy.termsVersionId(Type.RAW_AUDIO)).isEqualTo(PolicyTermsDevSeed.TERMS.get(3).versionId());
    }

    @Test
    void missingPolicyVersionOrAnyItemMakesTheWholePolicyUnavailable() {
        when(repository.findCurrentPolicyVersion()).thenReturn(Optional.empty());
        assertThat(reader.current()).isEmpty();

        when(repository.findCurrentPolicyVersion()).thenReturn(Optional.of("1.0"));
        when(catalog.latestApproved("POLICY_TERMS_RAW_AUDIO")).thenReturn(Optional.empty());
        assertThat(reader.current()).isEmpty();
        assertThat(reader.currentPolicyVersion()).contains("1.0");
    }

    @Test
    void malformedTermsTextIsRejected() {
        UUID id = UUID.randomUUID();
        assertThat(reader.parse(Type.RAW_AUDIO, new PinnedContent(id, "not json", null))).isEmpty();
        assertThat(reader.parse(Type.RAW_AUDIO, new PinnedContent(id, "{\"title\":\" \",\"sections\":[]}", null))).isEmpty();
        assertThat(reader.parse(Type.RAW_AUDIO, new PinnedContent(id, "{\"title\":\"t\",\"sections\":[]}", null))).isEmpty();
        assertThat(reader.parse(Type.RAW_AUDIO, new PinnedContent(id,
                "{\"title\":\"t\",\"sections\":[{\"heading\":\"동의하지 않으면\",\"body\":\"b\"}]}", null)))
                .as("생성형 AI 안내 절 없음").isEmpty();
        assertThat(reader.parse(Type.RAW_AUDIO, new PinnedContent(id,
                "{\"title\":\"t\",\"sections\":[{\"heading\":\"생성형 AI 안내\",\"body\":\"b\"},{\"heading\":\"x\",\"body\":\"b\"},"
                        + "{\"heading\":\"동의하지 않으면\",\"body\":\"b\"}]}", null)))
                .as("AI 안내가 '동의하지 않으면' 바로 앞이 아님").isEmpty();
        assertThat(reader.parse(Type.RAW_AUDIO, new PinnedContent(id,
                "{\"title\":\"t\",\"sections\":[{\"heading\":\"생성형 AI 안내\",\"body\":\" \"},"
                        + "{\"heading\":\"동의하지 않으면\",\"body\":\"b\"}]}", null))).as("빈 본문").isEmpty();
        when(catalog.latestApproved(anyString())).thenReturn(Optional.empty());
        assertThat(reader.current()).isEmpty();
    }
}
