package kr.mujoong.core.knowledge;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
/** BR6.4: 승인된 생활 팁 원문만 읽는다. 모델이 문구를 고치지 않는다. */
@Service
public class SupportLibrary {
    private final NamedParameterJdbcTemplate jdbc;
    public SupportLibrary(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Tip> approvedByBehaviorKeys(Collection<String> keys){
        if(keys.isEmpty())return List.of();
        return jdbc.query("select id,title,condition_text,content_text,limitation_text from core.core_support_item where behavior_key in (:keys) and status='APPROVED' order by created_at,id",Map.of("keys",keys),(r,n)->new Tip(r.getObject("id",UUID.class),r.getString("title"),r.getString("condition_text"),r.getString("content_text"),r.getString("limitation_text")));
    }
    public record Tip(UUID supportItemId,String title,String condition,String content,String limitation){@Override public String toString(){return "Tip[id="+supportItemId+"]";}}
}
