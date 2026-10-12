package kr.mujoong.core.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.db.ClockParams;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 승인 문구 카탈로그 (U2 ApprovedContentCatalog 완성 · FR7.2 · A5 · 계약 8절 ApprovedContentPin · U2-J17).
 *
 * <ul>
 *   <li>읽기: 코드의 APPROVED 최고 버전(세션 패키지 · 02-1 약관). {@link #pin}은 여러 코드를 한 번에 고정해 contentVersionId를 준다 — 세션은
 *       시작 때 받은 버전을 끝까지 쓴다(A5).
 *   <li>버전은 덮어쓰지 않는다: {@link #addVersion}은 같은 코드의 다음 번호로 DRAFT 행을 새로 만들고, {@link #approve}는 그 행을 APPROVED로
 *       바꾸며 같은 코드의 앞 APPROVED 행을 RETIRED로 바꾼다(한 트랜잭션, 같은 코드 종류 행 잠금).
 *   <li>NFR1 · NFR2 금지 표현 검사는 승인 전 사람 · 사전 검사가 한다(OQ15, 이 서비스는 상태만 바꾼다). 문구 글은 로그에 남기지 않는다.
 * </ul>
 */
@Component
public class ContentCatalogService {

    private final ApprovedContentRepository repository;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MujoongClock clock;

    public ContentCatalogService(
            ApprovedContentRepository repository,
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            MujoongClock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 고정된 승인 문구 한 칸(계약 8절 ApprovedContentPin 출력 · 5절 approvedContent 값 모양, 음성이 없으면 audioUrl null). */
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

    /** 코드의 APPROVED 최고 버전. */
    public Optional<PinnedContent> latestApproved(String contentCode) {
        return repository.findLatestApproved(contentCode)
                .map(version -> new PinnedContent(version.getId(), version.getContentText(), version.audioUrl().orElse(null)));
    }

    /**
     * 여러 코드를 한 번에 고정한다(계약 8절 ApprovedContentPin.pin). 승인 버전이 없는 코드는 결과에 없다 — 호출자가 시작 실패로 다룬다.
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

    /**
     * 새 DRAFT 버전을 만든다. 종류 행이 없으면 함께 만든다.
     *
     * @param audioPath 승인 음성 파일 이름(.wav) 또는 null
     * @return 새 버전 행
     */
    public ApprovedContentVersion addVersion(String contentCode, String contentType, String text, String audioPath) {
        requireText(contentCode, "contentCode");
        requireText(contentType, "contentType");
        requireText(text, "text");
        return transactions.execute(status -> {
            Instant now = clock.now();
            UUID contentId = lockOrCreateContent(contentCode, contentType, now);
            Integer max = jdbc.queryForObject(
                    "select coalesce(max(version), 0) from core.core_approved_content_version where content_id = :contentId",
                    ClockParams.uuid(new MapSqlParameterSource(), "contentId", contentId), Integer.class);
            ApprovedContentVersion version = new ApprovedContentVersion(UUID.randomUUID(), contentId,
                    (max == null ? 0 : max) + 1, text, audioPath, ApprovedContentVersion.STATUS_DRAFT, now);
            MapSqlParameterSource params = ClockParams.at(now);
            ClockParams.uuid(params, "id", version.getId());
            ClockParams.uuid(params, "contentId", contentId);
            ClockParams.integer(params, "version", version.getVersion());
            ClockParams.text(params, "text", text);
            ClockParams.text(params, "audio", audioPath);
            jdbc.update("""
                    insert into core.core_approved_content_version (id, content_id, version, content_text, audio_path, status,
                                                                    created_at)
                    values (:id, :contentId, :version, :text, :audio, 'DRAFT', :now)
                    """, params);
            return version;
        });
    }

    /**
     * DRAFT 버전을 승인한다. 같은 코드의 앞 APPROVED 버전은 RETIRED가 된다(세션은 contentVersionId로 고정해 영향 없음).
     *
     * @return 승인했으면 true(없는 버전 · DRAFT가 아님이면 false)
     */
    public boolean approve(UUID versionId) {
        Objects.requireNonNull(versionId, "versionId");
        Boolean approved = transactions.execute(status -> {
            MapSqlParameterSource params = ClockParams.uuid(new MapSqlParameterSource(), "id", versionId);
            List<UUID> contents = jdbc.queryForList("""
                    select c.id from core.core_approved_content c
                      join core.core_approved_content_version v on v.content_id = c.id
                     where v.id = :id and v.status = 'DRAFT'
                       for update of c
                    """, params, UUID.class);
            if (contents.isEmpty()) {
                return false;
            }
            ClockParams.uuid(params, "contentId", contents.get(0));
            jdbc.update("""
                    update core.core_approved_content_version set status = 'RETIRED'
                     where content_id = :contentId and status = 'APPROVED'
                    """, params);
            return jdbc.update("update core.core_approved_content_version set status = 'APPROVED' where id = :id", params) == 1;
        });
        return Boolean.TRUE.equals(approved);
    }

    private UUID lockOrCreateContent(String contentCode, String contentType, Instant now) {
        MapSqlParameterSource params = ClockParams.at(now);
        ClockParams.text(params, "code", contentCode);
        ClockParams.text(params, "type", contentType);
        ClockParams.uuid(params, "id", UUID.randomUUID());
        jdbc.update("""
                insert into core.core_approved_content (id, content_code, content_type, created_at)
                values (:id, :code, :type, :now)
                on conflict (content_code) do nothing
                """, params);
        return jdbc.queryForObject("select id from core.core_approved_content where content_code = :code for update",
                params, UUID.class);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 비어 있다");
        }
    }
}
