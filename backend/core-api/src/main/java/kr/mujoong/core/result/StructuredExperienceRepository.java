package kr.mujoong.core.result;
import static kr.mujoong.core.common.db.ClockParams.*;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
/** BR3.1: 구조화 경험은 결과 버전에 귀속된다. */
@Repository
public class StructuredExperienceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public StructuredExperienceRepository(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public void insert(UUID result,UUID session,String content,Instant now){jdbc.update("insert into core.core_structured_experience(id,result_id,session_id,content_json,created_at) values(:id,:result,:session,cast(:content as jsonb),:now)",at(now).addValue("id",UUID.randomUUID()).addValue("result",result).addValue("session",session).addValue("content",content));}
    public Optional<String> find(UUID result){return jdbc.query("select content_json from core.core_structured_experience where result_id=:id",Map.of("id",result),(r,n)->r.getString(1)).stream().findFirst();}
}
