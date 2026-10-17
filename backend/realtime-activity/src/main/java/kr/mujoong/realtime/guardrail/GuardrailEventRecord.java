package kr.mujoong.realtime.guardrail;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 제브 검사 결과의 영구 기록 한 건 (U13 BR8.1, 표 {@code realtime.guardrail_event}). 이유 코드 · 정책 판 · 지연 · 판정만 담고 전사 원문 ·
 * 후보 문장 · 맥락은 담지 않는다(R-F14) — 그런 칸이 레코드에도 표에도 없다. {@code id}는 검사 요청 id라 같은 검사의 재시도 쓰기가 덮어쓰기다.
 *
 * <p>값이 표의 CHECK 제약을 어기면 기록기가 영구 실패로 버리게 되므로, 만들 때 같은 규칙으로 거절한다(호출 쪽 결함을 바로 드러낸다).
 *
 * @param mode {@link GuardrailMode#REAL} 또는 {@link GuardrailMode#STUB}. OFF는 제브 단계를 건너뛰어 기록이 없다(BR1.5)
 * @param reasonCode {@code ^[A-Z][A-Z0-9_]{0,79}$}(사유 코드만, 문장 · 원문 불가)
 * @param latencyMs 요청 보낸 뒤 결과까지(원문 재생 생략은 0)
 * @param createdAt {@code MujoongClock}으로 읽은 값(DB 기본값 없음)
 */
public record GuardrailEventRecord(UUID id, UUID guardianId, UUID sessionId, GuardrailMode mode, SourceKind sourceKind,
        Decision decision, String reasonCode, String policyVersion, int latencyMs, Instant createdAt) {

    /** 사유 코드 형식(guardrail-result.schema.json reasonCode와 같다). */
    public static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,79}$");
    /** 정책 판 길이 상한(표 칸 크기와 요청 스키마 policyVersion 상한). */
    public static final int POLICY_VERSION_MAX = 80;

    /** 검사 대상의 출처 구분(BR7.5). */
    public enum SourceKind {
        /** GPT-Live가 그 자리에서 만든 새 발화(제브가 검사한다). */
        NEW_UTTERANCE,
        /** 승인 원문과 완전히 일치해 제브를 부르지 않은 재생. */
        APPROVED_VERBATIM_SKIPPED
    }

    /** 기록하는 판정. */
    public enum Decision {
        ALLOW,
        BLOCK,
        REVIEW,
        /** 시간 초과. */
        TIMEOUT,
        /** 전송 오류 · 잘못된 JSON · 정책 판 불일치 · 어댑터 없음 · 요청 거절. */
        ERROR,
        /** 늦은 ALLOW 무효(원인은 사유 코드). */
        VOIDED,
        /** 대기열이 차 폐기된 구간. */
        QUEUE_FULL
    }

    public GuardrailEventRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(mode, "mode");
        if (mode == GuardrailMode.OFF) {
            throw new IllegalArgumentException("OFF 모드는 제브 검사 기록을 남기지 않는다");
        }
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException("reasonCode 형식이 올바르지 않다");
        }
        Objects.requireNonNull(policyVersion, "policyVersion");
        if (policyVersion.isEmpty() || policyVersion.length() > POLICY_VERSION_MAX) {
            throw new IllegalArgumentException("policyVersion 길이가 올바르지 않다");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs는 0 이상이어야 한다");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
