package kr.mujoong.core.practicegoal;

import java.util.Optional;
import java.util.UUID;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.consent.ConsentPolicyService;
import kr.mujoong.core.practice.PracticeAccountPort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PinnedSavedGoalReader implements SavedGoalReader {
    private final GoalCandidateRepository goals;
    private final PracticeAccountPort accounts;
    private final ConsentPolicyService consent;
    private final NamedParameterJdbcTemplate jdbc;
    public PinnedSavedGoalReader(GoalCandidateRepository goals,PracticeAccountPort accounts,ConsentPolicyService consent,NamedParameterJdbcTemplate jdbc){this.goals=goals;this.accounts=accounts;this.consent=consent;this.jdbc=jdbc;}
    @Override public Optional<SavedGoalView> find(UUID child,UUID selection){
        return read(child,selection,true);
    }
    /** Result/report-owned historical reads retain deleted selection snapshots; C-DS10 stays active-only. */
    public Optional<SavedGoalView> historical(UUID child,UUID selection){return read(child,selection,false);}
    private Optional<SavedGoalView> read(UUID child,UUID selection,boolean active){
        if(child==null||selection==null)return Optional.empty();
        try{var owner=accounts.requireChild(child,false);if(!consent.check(owner.guardianId()).ok())return Optional.empty();}
        catch(CoreApiException unavailable){return Optional.empty();}
        var generated=active?goals.saved(child,selection):goals.historical(child,selection);if(generated.isPresent())return generated;
        return jdbc.query("select behavior_key,behavior_version from core.core_practice_goal_selection where id=:id and child_id=:child"+(active?" and status='ACTIVE'":""),java.util.Map.of("id",selection,"child",child),(r,n)->new SavedGoalView(selection,child,SavedGoalView.Kind.LEGACY_BEHAVIOR,null,null,null,null,null,r.getString(1),r.getInt(2))).stream().findFirst();
    }
}
