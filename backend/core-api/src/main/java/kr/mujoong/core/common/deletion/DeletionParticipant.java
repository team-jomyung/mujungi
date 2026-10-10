package kr.mujoong.core.common.deletion;

import java.util.Map;
import java.util.UUID;

/**
 * 계정 삭제 참가자 포트(계약 8절 DeletionParticipant · SD-21 · J58). 제공(계약)은 U2 삭제 지휘이고 U1은 구현만 한다.
 *
 * <p>규약: 멱등이다(두 번 불러도 결과가 같다). 참가자마다 자기 스키마에서 한 트랜잭션이고 부분 성공이 없다. 예외를 던지면 지휘가
 * 다시 부른다(외래 키 순서 위반 23503 포함). 반환 건수는 첫 호출이 n이고 이후 0일 수 있어 지휘는 건수로 완료를 판정하지 않는다.
 */
public interface DeletionParticipant {

    /**
     * 보호자의 행을 지운다.
     *
     * @param guardianId 삭제를 접수한 보호자
     * @param deletionRequestId 삭제 요청 id(기록용, U2)
     * @return 표(엔터티)별 지운 · 닫은 건수(예: {@code {device: 1}})
     */
    Map<String, Integer> deleteByGuardian(UUID guardianId, UUID deletionRequestId);
}
