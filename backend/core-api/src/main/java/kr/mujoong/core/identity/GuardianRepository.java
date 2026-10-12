package kr.mujoong.core.identity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 보호자 {@code core.core_guardian} JPA 저장소 (U1 3.5 Step 15 · entities.md Guardian).
 *
 * <p>U1은 dev 시드 보호자 읽기(시험 로그인 BR8.4 · 삭제 접수 빠른 거절 BR2.2 · BR6.1 ⓪)만 한다. 보호자 행 잠금(FOR SHARE · FOR UPDATE ·
 * ACTIVE 확인) SQL은 {@link GuardianWriteLock} 한 곳에 있다. 등록 트랜잭션의 락 순서(보호자 → 코드 → 옛 기기)는
 * {@code DeviceJdbcRepository.lockGuardianStatusForShare}가 그 첫 잠금을 부르며 지킨다(U2 2차 리뷰 5).
 */
public interface GuardianRepository extends JpaRepository<Guardian, UUID> {
}
