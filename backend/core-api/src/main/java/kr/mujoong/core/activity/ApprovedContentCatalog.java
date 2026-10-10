package kr.mujoong.core.activity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.catalog.ApprovedContent;
import kr.mujoong.core.catalog.ApprovedContentRepository;
import kr.mujoong.core.catalog.ApprovedContentVersion;
import org.springframework.stereotype.Component;

/**
 * 승인 문구 · 현재 정책 버전 읽기 (U1 3.5 Step 22 · BR6.5 · J114 · J60 · J102).
 *
 * <p>세션 패키지에 넣을 CLOSING · WAITING_FILLER는 각 코드의 APPROVED 최고 버전이다(contentVersionId · text · {@code /media/approved/} 상대
 * audioUrl, 세션에 고정). 현재 정책 버전은 CONSENT_POLICY의 APPROVED 최고 버전 content_text다(J114, 임시 '1.0'). B1은 캐시 없이 DB를
 * 직접 읽는다(PD-7 캐시는 3.6 이후, R3-21 344행). 승인 문구 · 음성이 없으면 시작할 수 없으므로 빈 값을 돌려주고 호출자가 실패로 다룬다.
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

    private Optional<ApprovedItem> item(String contentCode) {
        return repository.findLatestApproved(contentCode)
                .filter(ApprovedContentVersion::isApproved)
                .flatMap(version -> version.audioUrl()
                        .map(url -> new ApprovedItem(version.getId(), version.getContentText(), url)));
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
}
