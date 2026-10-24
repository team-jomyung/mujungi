package kr.mujoong.core.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 승인 문구 종류 {@code core.core_approved_content} (U1 3.5 Step 10 · entities.md ApprovedContent · J52 · J60 · J114).
 *
 * <p>U1 시드는 세 건이다: {@value #CODE_CLOSING}(마무리 인사) · {@value #CODE_WAITING_FILLER}(게이트 폐기 뒤 대체 문구) ·
 * {@value #CODE_CONSENT_POLICY}(현재 정책 버전 자리). {@code content_code}는 유일하다. 버전 관리와 정식 문구는 U2가 맡는다.
 */
@Entity
@Table(name = "core_approved_content")
public class ApprovedContent {

    /** 끝내기 마무리 인사(BR6.10). */
    public static final String CODE_CLOSING = "CLOSING";
    /** 게이트 폐기 뒤 대기 · 대체 안내(BR7.4). */
    public static final String CODE_WAITING_FILLER = "WAITING_FILLER";
    /** 현재 정책 버전 문자열의 자리(최고 APPROVED 버전 행의 content_text, J114). */
    public static final String CODE_CONSENT_POLICY = "CONSENT_POLICY";

    /** 도움 제안(PRACTICE, U8: 004 시드가 APPROVED_SPEECH v2로 올림). */
    public static final String CODE_HELP_OFFER = "HELP_OFFER";
    /** 시작 안내 중 휴식 요청을 받아 주는 승인 발화(U8 · B §3.1 D_REST_ACK_START). */
    public static final String CODE_D_REST_ACK_START = "D_REST_ACK_START";
    /** 활동 중 휴식 요청을 받아 주는 승인 발화(U8 · B §3.1 D_REST_ACK). */
    public static final String CODE_D_REST_ACK = "D_REST_ACK";
    /** 코치 역할 전환 표현(U8 · B §8.5). */
    public static final String CODE_COACH_SWITCH = "COACH_SWITCH";
    /** 친구 역할 복귀 표현(U8 · B §8.5). */
    public static final String CODE_PEER_RETURN = "PEER_RETURN";

    // 연습 대화 C안 고정 승인 발화(006 시드 · docs/practice-direct-ask.md 5장). 장면 안 무중의 말 중 대본 밖 고정 문장이다
    /** 변형 장면 제안. */
    public static final String CODE_VARIANT_OFFER = "VARIANT_OFFER";
    /** 다시 해 보기 무응답 뒤 다시 청하기. */
    public static final String CODE_RETRY_PROMPT = "RETRY_PROMPT";
    /** 판독 불가 · 판정 보류 뒤 다시 말해 달라고 하기. */
    public static final String CODE_INPUT_CONFIRM = "INPUT_CONFIRM";
    /** 장면 실패 · 거절 · 한도 뒤 장면 닫기. */
    public static final String CODE_SCENE_CLOSE = "SCENE_CLOSE";
    /** 무응답 다시 묻기의 앞머리(뒤에 대본 askPrompt가 붙는다). */
    public static final String CODE_WAIT_LEAD = "WAIT_LEAD";
    /** 옛 대본(askPrompt 없음)의 묻기 대체. */
    public static final String CODE_ASK_DEFAULT = "ASK_DEFAULT";

    /** 승인 음성이 딸린 문구(J117). */
    public static final String TYPE_APPROVED_AUDIO = "APPROVED_AUDIO";
    /**
     * 음성 파일 없이 GPT-Live 지시 + 공백 제거 완전 일치로 재생하는 승인 발화(U8 · BR1.2 · 편차 U8-e). 시작 패키지 approvedSpeech로만
     * 넘긴다.
     */
    public static final String TYPE_APPROVED_SPEECH = "APPROVED_SPEECH";
    /** 정책 버전 문자열(음성 없음, J114 · J 후보). */
    public static final String TYPE_POLICY_VERSION = "POLICY_VERSION";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "content_code", nullable = false, updatable = false, length = 100)
    private String contentCode;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected ApprovedContent() {
    }

    /** 새 승인 문구 종류. {@code now}는 주입한 Clock에서 읽은 값이다. */
    public ApprovedContent(UUID id, String contentCode, String contentType, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.contentCode = requireText(contentCode, "contentCode");
        this.contentType = requireText(contentType, "contentType");
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 비어 있다");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public String getContentCode() {
        return contentCode;
    }

    public String getContentType() {
        return contentType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof ApprovedContent that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ApprovedContent.class.hashCode();
    }

    @Override
    public String toString() {
        return "ApprovedContent[id=" + id + ", code=" + contentCode + "]";
    }
}
