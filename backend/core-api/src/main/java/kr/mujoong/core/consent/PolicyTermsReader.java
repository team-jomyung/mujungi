package kr.mujoong.core.consent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.activity.ApprovedContentCatalog;
import kr.mujoong.core.activity.ApprovedContentCatalog.PinnedContent;
import kr.mujoong.core.consent.ConsentStatusRule.Type;
import org.springframework.stereotype.Component;

/**
 * 02 · 02-1 약관 본문 읽기 (U2 ConsentPolicy · FR1.2 · FR1.3 · AC1.2.2 · AC1.2.9 · R7 · 편차 (U2-c) · U2-J7).
 *
 * <p>현재 정책 버전은 승인 문구 CONSENT_POLICY의 최신 승인 글(U1 J114)이고, 항목별 본문은 승인 문구 {@code POLICY_TERMS_<항목>}의 최신 승인
 * 버전 글(JSON {@code {title, sections[{heading, body}]}})이다. 둘 다 U1 시작 게이트와 같은 {@link ApprovedContentCatalog}에서 읽는다(리뷰 10:
 * 카탈로그는 하나). 네 항목 중 하나라도 없거나 형식이 틀리면 빈 값이다(호출자가 500). 모든 항목에 '생성형 AI 안내' 절이 '동의하지 않으면' 절
 * 바로 앞에 있어야 한다(mockups F2) — 없으면 형식 오류로 본다.
 */
@Component
public class PolicyTermsReader {

    /** 약관 본문 승인 문구 코드 접두. */
    public static final String TERMS_CODE_PREFIX = "POLICY_TERMS_";
    /** F2 소제목. */
    public static final String AI_NOTICE_HEADING = "생성형 AI 안내";
    /** '동의하지 않으면' 소제목(문구표 C.S-02-1.35.9). */
    public static final String IF_DENIED_HEADING = "동의하지 않으면";

    private final ApprovedContentCatalog catalog;
    private final JsonCodec json;

    public PolicyTermsReader(ApprovedContentCatalog catalog, JsonCodec json) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 현재 정책 버전(J114, 시작 게이트와 같은 카탈로그). */
    public Optional<String> currentPolicyVersion() {
        return catalog.currentPolicyVersion();
    }

    /** 항목 하나의 약관(본문 버전 id 포함). */
    public record PolicyItem(Type type, UUID termsVersionId, String title, List<Section> sections) {

        public PolicyItem {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(termsVersionId, "termsVersionId");
            Objects.requireNonNull(title, "title");
            sections = List.copyOf(sections);
        }
    }

    /** 소제목 · 본문 한 절. */
    public record Section(String heading, String body) {
    }

    /** 현재 약관(정책 버전 + 4항목, 화면 순서). */
    public record CurrentPolicy(String policyVersion, List<PolicyItem> items) {

        public CurrentPolicy {
            Objects.requireNonNull(policyVersion, "policyVersion");
            items = List.copyOf(items);
        }

        /** 항목의 본문 버전 id. */
        public UUID termsVersionId(Type type) {
            return items.stream().filter(item -> item.type() == type).findFirst().map(PolicyItem::termsVersionId)
                    .orElseThrow();
        }
    }

    /** 현재 약관을 읽는다. 정책 버전 · 네 항목 중 하나라도 없거나 형식이 틀리면 빈 값. */
    public Optional<CurrentPolicy> current() {
        Optional<String> policyVersion = currentPolicyVersion();
        if (policyVersion.isEmpty() || policyVersion.get().isBlank()) {
            return Optional.empty();
        }
        List<PolicyItem> items = new ArrayList<>();
        for (Type type : Type.values()) {
            Optional<PolicyItem> item = catalog.latestApproved(TERMS_CODE_PREFIX + type.name())
                    .flatMap(content -> parse(type, content));
            if (item.isEmpty()) {
                return Optional.empty();
            }
            items.add(item.get());
        }
        return Optional.of(new CurrentPolicy(policyVersion.get(), items));
    }

    /** 승인 문구 글(JSON)을 읽는다. 형식이 틀리면 빈 값. */
    Optional<PolicyItem> parse(Type type, PinnedContent content) {
        TermsText text;
        try {
            text = json.read(content.text(), TermsText.class);
        } catch (JsonCodec.JsonCodecException e) {
            return Optional.empty();
        }
        if (text == null || isBlank(text.title()) || text.sections() == null || text.sections().isEmpty()) {
            return Optional.empty();
        }
        List<Section> sections = text.sections();
        if (sections.stream().anyMatch(section -> section == null || isBlank(section.heading()) || isBlank(section.body()))) {
            return Optional.empty();
        }
        int aiNotice = indexOf(sections, AI_NOTICE_HEADING);
        int ifDenied = indexOf(sections, IF_DENIED_HEADING);
        if (aiNotice < 0 || ifDenied != aiNotice + 1) {
            return Optional.empty();
        }
        return Optional.of(new PolicyItem(type, content.contentVersionId(), text.title(), sections));
    }

    private static int indexOf(List<Section> sections, String heading) {
        for (int i = 0; i < sections.size(); i++) {
            if (heading.equals(sections.get(i).heading())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 승인 문구 글의 JSON 모양. */
    public record TermsText(String title, List<Section> sections) {
    }
}
