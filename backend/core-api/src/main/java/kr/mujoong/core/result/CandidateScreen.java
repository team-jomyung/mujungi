package kr.mujoong.core.result;
import kr.mujoong.core.result.check.ForbiddenDictionary;
import kr.mujoong.core.observability.EventLogger;
import org.springframework.stereotype.Component;
/** Compatibility entry point; candidate screening is owned by PracticeGoal. */
@Component
public class CandidateScreen extends kr.mujoong.core.practicegoal.CandidateScreen {
    public CandidateScreen(ForbiddenDictionary dictionary,EventLogger events){super(dictionary,events);}
}
