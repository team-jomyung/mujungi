package kr.mujoong.core.activity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.catalog.ApprovedContentRepository;
import kr.mujoong.core.catalog.ApprovedContentVersion;
import org.springframework.stereotype.Component;

/**
 * 승인 문구 · 현재 정책 버전 읽기 (U1 3.5 Step 22 · BR6.5 · J114 · J60 · J102, U2가 계약 8절 ApprovedContentPin을 더함 · 리뷰 10).
 *
 * <p>승인 문구를 읽는 곳은 이 카탈로그 하나다(U1 세션 패키지 · 시작 게이트, U2 02-1 약관 · 동의 상태가 같은 정책 버전 · 같은 승인 버전을 본다).
 * 세션 패키지에 넣을 CLOSING · WAITING_FILLER는 각 코드의 APPROVED 최고 버전이다(contentVersionId · text · {@code /media/approved/} 상대
 * audioUrl, 세션에 고정). 현재 정책 버전은 CONSENT_POLICY의 APPROVED 최고 버전 content_text다(J114, 임시 '1.0'). B1은 캐시 없이 DB를
 * 직접 읽는다(PD-7 캐시는 3.6 이후, R3-21 344행). 승인 문구 · 음성이 없으면 시작할 수 없으므로 빈 값을 돌려주고 호출자가 실패로 다룬다.
 * 새 버전 · 승인은 참조 시드(infra/seed)로 넣는다 — 앱에 쓰기 경로는 없다(U2-J17).
 */
@Component
public class ApprovedContentCatalog {

    private final ApprovedContentRepository repository;

    public ApprovedContentCatalog(ApprovedContentRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** 현재 정책 버전(J114). */
    public Optional<String> currentPolicyVersion() {
        return repository.findCurrentPolicyVersion();
    }

    /** CLOSING 승인 문구. */
    public Optional<ApprovedItem> closing() {
        return item(ApprovedContent.CODE_CLOSING);
    }

    /** WAITING_FILLER 승인 문구. */
    public Optional<ApprovedItem> waitingFiller() {
        return item(ApprovedContent.CODE_WAITING_FILLER);
    }

    /** 코드의 APPROVED 최고 버전(음성이 없어도 된다 — 02-1 약관 본문 등). */
    public Optional<PinnedContent> latestApproved(String contentCode) {
        return repository.findLatestApproved(contentCode)
                .filter(ApprovedContentVersion::isApproved)
                .map(version -> new PinnedContent(version.getId(), version.getContentText(), version.audioUrl().orElse(null)));
    }

    /**
     * 여러 코드를 한 번에 고정한다(계약 8절 ApprovedContentPin.pin, U4가 부른다). 승인 버전이 없는 코드는 결과에 없다 — 호출자가 시작
     * 실패로 다룬다. 세션은 받은 contentVersionId를 끝까지 쓴다(A5).
     *
     * @return 코드 → 고정 문구(입력 순서)
     */
    public Map<String, PinnedContent> pin(Collection<String> contentCodes) {
        Map<String, PinnedContent> pinned = new LinkedHashMap<>();
        for (String code : Objects.requireNonNull(contentCodes, "contentCodes")) {
            latestApproved(code).ifPresent(content -> pinned.put(code, content));
        }
        return pinned;
    }

    /** 세션 패키지 문구: 같은 조회({@link #latestApproved})에 '음성 있음'만 더한다(2차 리뷰 6). */
    private Optional<ApprovedItem> item(String contentCode) {
        return latestApproved(contentCode)
                .filter(content -> content.audioUrl() != null)
                .map(content -> new ApprovedItem(content.contentVersionId(), content.text(), content.audioUrl()));
    }

    /** 패키지의 승인 문구 한 칸(계약 approvedContent 값 모양). */
    public record ApprovedItem(UUID contentVersionId, String text, String audioUrl) {

        public ApprovedItem {
            Objects.requireNonNull(contentVersionId, "contentVersionId");
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("승인 문구 text가 비었다");
            }
            if (audioUrl == null || !audioUrl.startsWith(ApprovedContentVersion.AUDIO_URL_PREFIX)) {
                throw new IllegalArgumentException("audioUrl은 /media/approved/ 상대 경로여야 한다");
            }
        }
    }

    /** 고정된 승인 문구 한 칸(계약 8절 ApprovedContentPin 출력, 음성이 없으면 audioUrl null). */
    public record PinnedContent(UUID contentVersionId, String text, String audioUrl) {

        public PinnedContent {
            Objects.requireNonNull(contentVersionId, "contentVersionId");
            Objects.requireNonNull(text, "text");
        }

        /** 글은 문자열 표현에 내지 않는다. */
        @Override
        public String toString() {
            return "PinnedContent[contentVersionId=" + contentVersionId + "]";
        }
    }
}
