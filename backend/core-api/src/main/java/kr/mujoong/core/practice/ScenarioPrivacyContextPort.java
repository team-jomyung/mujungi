package kr.mujoong.core.practice;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** R-01: 이름 원본은 내부 일시 치환 정보이며 생성 데이터 필드가 아니다. */
public interface ScenarioPrivacyContextPort {
    Context require(UUID guardianId,UUID childId);
    final class Context {
        private final Map<String,String> aliases;
        public Context(String name){
            if(name==null||name.isBlank()||name.length()>100)throw ScenarioDeadline.failed();
            aliases=Map.of(name.strip(),"아이");
        }
        @JsonIgnore public Map<String,String> aliases(){return aliases;}
        @JsonIgnore public Set<String> originalNames(){return aliases.keySet();}
        @Override public String toString(){return "ScenarioPrivacyContext[redacted]";}
    }
}
