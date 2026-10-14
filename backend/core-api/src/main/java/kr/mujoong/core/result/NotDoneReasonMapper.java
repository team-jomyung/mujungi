package kr.mujoong.core.result;
import java.util.*;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.stereotype.Component;
/** FD-Q3 초안 사상표. 모르는 값은 코드만 남기고 활동 끝으로 처리한다. */
@Component
public class NotDoneReasonMapper {
    private final EventLogger events;
    public NotDoneReasonMapper(EventLogger events){this.events=events;}
    public String map(Map<String,Object> goal,Map<String,Object> response){
        if("NOT_REACHED".equals(goal.get("status")))return "ACTIVITY_ENDED";
        if("SKIPPED".equals(goal.get("status"))&&Set.of("TIME_OVER","TIMEOUT").contains(ResultData.text(goal,"skipReason")))return "TIME_OVER";
        if("SKIPPED".equals(goal.get("status"))&&Set.of("SCENARIO_FAILED","SCENARIO_GEN_FAILED","GENERATION_FAILED","VALIDATION_FAILED","UNAVAILABLE").contains(ResultData.text(goal,"skipReason")))return "SCENARIO_FAILED";
        String reason=ResultData.text(response,"notPerformedReason");
        if(Set.of("TIME_OVER","TIME_LIMIT","TIMEOUT").contains(reason))return "TIME_OVER";
        if(Set.of("NO_RESPONSE","SILENCE").contains(reason))return "NO_RESPONSE";
        if(reason.equals("SCENARIO_FAILED"))return reason;
        if(!reason.isBlank()&&!reason.equals("ACTIVITY_ENDED"))events.appError(kr.mujoong.contract.log.EventLog.Component.CORE_MAINTENANCE,"UnknownNotPerformedReason");
        return "ACTIVITY_ENDED";
    }
}
