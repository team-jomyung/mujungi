package kr.mujoong.realtime.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import kr.mujoong.realtime.common.db.DbRetry;
import kr.mujoong.realtime.messaging.RealtimeDeletionParticipant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("u5b")
class RealtimePracticeDeletionParticipantTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final RealtimePracticeDeletionParticipant participant = new RealtimePracticeDeletionParticipant(
            jdbc, new DbRetry(0, new java.util.Random(1), ignored -> {}), transactions);

    @Test
    void springFindsThePracticeParticipantBeforeTheSessionParticipant() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(RealtimeDeletionParticipant.class));
        assertThat(scanner.findCandidateComponents("kr.mujoong.realtime.practice"))
                .extracting(bean -> bean.getBeanClassName())
                .contains(RealtimePracticeDeletionParticipant.class.getName());
        assertThat(participant.order()).isEqualTo(150).isLessThan(RealtimeDeletionParticipant.ORDER_SESSION);
    }

    @Test
    void missingGuardianOrRequestIsRejectedBeforeOpeningATransaction() {
        assertThatThrownBy(() -> participant.deleteByGuardian(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> participant.deleteByGuardian(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(jdbc, transactions);
    }
}
