package kr.mujoong.core.practice;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
/** Legacy entry point; candidate/selection SQL is owned by PracticeGoal. */
@Repository
public class PracticeGoalRepository extends kr.mujoong.core.practicegoal.PracticeGoalRepository {
    public PracticeGoalRepository(NamedParameterJdbcTemplate jdbc){super(jdbc);}
}
