package kr.mujoong.core.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 아이 {@code core.core_child} JPA 저장소 (U1 3.5 Step 15 · entities.md Child).
 *
 * <p>보호자당 한 명(guardian_id 유일)이라 보호자로 찾으면 최대 한 행이다. 세션 패키지의 대화 문맥(BR6.5)과 홈 응답(J119)이 쓴다.
 */
public interface ChildRepository extends JpaRepository<Child, UUID> {

    /** 보호자의 아이(최대 한 명). */
    Optional<Child> findByGuardianId(UUID guardianId);
}
