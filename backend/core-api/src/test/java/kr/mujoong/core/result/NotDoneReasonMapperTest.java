package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.*;
@Tag("u6")
class NotDoneReasonMapperTest {
    kr.mujoong.core.observability.EventLogger events=mock(kr.mujoong.core.observability.EventLogger.class);NotDoneReasonMapper mapper=new NotDoneReasonMapper(events);
    @Test void notReached(){assertThat(mapper.map(Map.of("status","NOT_REACHED"),Map.of())).isEqualTo("ACTIVITY_ENDED");}
    @Test void scenarioFailed(){assertThat(mapper.map(Map.of("status","SKIPPED","skipReason","SCENARIO_GEN_FAILED"),Map.of())).isEqualTo("SCENARIO_FAILED");}
    @Test void timeOver(){assertThat(mapper.map(Map.of(),Map.of("notPerformedReason","TIME_OVER"))).isEqualTo("TIME_OVER");}
    @Test void silence(){assertThat(mapper.map(Map.of(),Map.of("notPerformedReason","NO_RESPONSE"))).isEqualTo("NO_RESPONSE");}
    @Test void absentScene(){assertThat(mapper.map(Map.of(),Map.of())).isEqualTo("ACTIVITY_ENDED");}
    @Test void unknownLogsCodeOnly(){assertThat(mapper.map(Map.of(),Map.of("notPerformedReason","UNKNOWN"))).isEqualTo("ACTIVITY_ENDED");verify(events).appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,"UnknownNotPerformedReason");}
}
