package kr.mujoong.realtime.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import kr.mujoong.realtime.practice.JdbcPracticeRecordRecovery;
import kr.mujoong.realtime.practiceflow.PracticeFlowRecordRecovery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;

/**
 * 연습 정산 복구 분기 시험 (U12 BR7.4 · BR10.1). 새 흐름 장면이 있으면 그 값을, 없으면 레거시 복구를 부르고, 레거시의 실패는 그대로 올라오며
 * 정산 서비스가 하나만 받도록 {@code @Primary}인지 본다. 협력 객체는 대역이다.
 */
@Tag("u12")
class PracticeRecoveryRouterTest {

    private final PracticeFlowRecordRecovery flow = mock(PracticeFlowRecordRecovery.class);
    private final JdbcPracticeRecordRecovery legacy = mock(JdbcPracticeRecordRecovery.class);
    private final PracticeRecoveryRouter router = new PracticeRecoveryRouter(flow, legacy);

    @Test
    void aSessionWithNewFlowScenesIsRecoveredFromTheNewTablesWithoutTouchingLegacy() {
        UUID session = UUID.randomUUID();
        Object value = new Object();
        when(flow.recover(session)).thenReturn(Optional.of(value));

        assertThat(router.recover(session)).isSameAs(value);
        verify(legacy, never()).recover(session);
    }

    @Test
    void aSessionWithoutNewFlowScenesFallsBackToLegacyAndItsFailureStaysAsIs() {
        UUID session = UUID.randomUUID();
        Object legacyValue = new Object();
        when(flow.recover(session)).thenReturn(Optional.empty());
        when(legacy.recover(session)).thenReturn(legacyValue);
        assertThat(router.recover(session)).isSameAs(legacyValue);

        UUID noOrigin = UUID.randomUUID();
        when(flow.recover(noOrigin)).thenReturn(Optional.empty());
        when(legacy.recover(noOrigin)).thenThrow(new IllegalStateException("복구할 연습 원본이 없다"));
        assertThatThrownBy(() -> router.recover(noOrigin)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRouterIsThePrimaryRecoveryBeanSoTheSettlementServiceStillGetsOne() {
        assertThat(PracticeRecoveryRouter.class.isAnnotationPresent(Primary.class)).isTrue();
    }
}
