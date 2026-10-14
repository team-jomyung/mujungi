package kr.mujoong.realtime.practice;

import java.util.Objects;
import java.util.UUID;

/** U5b 자기 표만 삭제한다. U4b 순서 150의 실제 참가자 등록은 후속 통합 경계다. */
public final class PracticeDeletionService {
    private final PracticeRepository repository;
    public PracticeDeletionService(PracticeRepository repository) { this.repository = Objects.requireNonNull(repository); }
    public void delete(UUID guardianId) { repository.delete(Objects.requireNonNull(guardianId)); }
}
