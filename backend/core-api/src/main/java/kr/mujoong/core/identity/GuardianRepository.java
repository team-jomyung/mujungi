package kr.mujoong.core.identity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 보호자 {@code core.core_guardian} JPA 저장소 (U1 3.5 Step 15 · entities.md Guardian).
 *
 * <p>U1은 dev 시드 보호자 읽기(시험 로그인 BR8.4 · 삭제 접수 빠른 거절 BR2.2 · BR6.1 ⓪)만 한다. 등록 트랜잭션의 FOR SHARE 잠금은
 * 락 순서(보호자 → 코드 → 옛 기기)를 한 곳에 두려고 {@code DeviceJdbcRepository.lockGuardianStatusForShare}가 맡는다.
 */
public interface GuardianRepository extends JpaRepository<Guardian, UUID> {
}
