package kr.mujoong.core.common.deletion;

import java.util.Map;
import java.util.UUID;

/**
 * 계정 삭제 참가자 포트(계약 8절 DeletionParticipant · SD-21 · J58). 제공(계약)은 U2 삭제 지휘이고 U1은 구현만 한다.
 *
 * <p>규약: 멱등이다(두 번 불러도 결과가 같다). 참가자마다 자기 스키마에서 한 트랜잭션이고 부분 성공이 없다. 예외를 던지면 지휘가
 * 다시 부른다(외래 키 순서 위반 23503 포함). 반환 건수는 첫 호출이 n이고 이후 0일 수 있어 지휘는 건수로 완료를 판정하지 않는다.
 *
 * <p>등록(U2 지휘 틀): 참가자는 Spring 빈이면 지휘가 모두 모아 부르고, 순서는 빈의 {@code @Order} 값이다(작을수록 먼저). 외래 키
 * 순서(활동 → 기기 → 아이 쪽 → 보호자)를 아래 상수로 맞춘다. 뒤 유닛은 자기 표를 가리키는 표보다 먼저 오도록 값을 고른다.
 * 쓰는 값: 60(U7 푸시) · 90(U4a 활동 기록) · 100 · 150(U5a 연습) · 200 · 250 · 300 · 900. 같은 값은 순서가 정해지지 않으니 새 상수는 이와 다른 값을 쓴다.
 */
public interface DeletionParticipant {

    /** U7 푸시 구독과 발송 기록 — 활동 기록·활동 사본을 지우기 전에 정리한다. */
    int ORDER_PUSH = 60;

    /** core_activity_record(활동 기록, U4a) — core_activity를 가리킨다. */
    int ORDER_ACTIVITY_RECORD = 90;
    /** core_activity(세션 사본, U1 데이터 · U4a가 넘겨받음, 08 배경 core_activity_context 포함) — core_device · core_child를 가리킨다. */
    int ORDER_ACTIVITY = 100;
    /**
     * U5a 연습 데이터(상황 요청 · 상황 · 목표 선택 · 추천 근거 · 추천) — 목표 선택 · 추천이 core_child를 ON DELETE RESTRICT로 가리키므로 아이(300)보다
     * 먼저 지운다.
     */
    int ORDER_PRACTICE = 150;
    /** core_device(U1). */
    int ORDER_DEVICE = 200;
    /** U2 동의(core_consent — core_child를 가리킨다). */
    int ORDER_CONSENT = 250;
    /** U2 아이 쪽 데이터(종료 표현 → 아이). */
    int ORDER_CHILD_PROFILE = 300;
    /** Core 메시징(Outbox/Inbox, U1). 전달이 끝난 행만 지우므로 맨 뒤에 둔다. */
    int ORDER_MESSAGING = 900;

    /**
     * 보호자의 행을 지운다.
     *
     * @param guardianId 삭제를 접수한 보호자
     * @param deletionRequestId 삭제 요청 id(기록용, U2)
     * @return 표(엔터티)별 지운 · 닫은 건수(예: {@code {device: 1}})
     */
    Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId);
}
