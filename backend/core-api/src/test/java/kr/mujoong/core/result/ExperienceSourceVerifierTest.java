package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class ExperienceSourceVerifierTest {
    private final List<Map<String,Object>> transcript=List.of(Map.of("seq",1,"speaker","AI","text","친구와 다퉜어?"),Map.of("seq",2,"speaker","CHILD","text","나는 블록을 쌓았어","uncertain",false),Map.of("seq",3,"speaker","CHILD","text","친구를 밀었어","uncertain",true));
    Map<String,Object> field(String text,String source,int seq){return Map.of("text",text,"source",source,"utteranceSeq",seq);}
    Map<String,Object> verify(Map<String,Object> field,Map<String,Object> background){return ExperienceSourceVerifier.verify(Map.of("characters",List.of(),"situation",field),transcript,background);}
    @Test void childEvidenceRequiresActualCertainChildWords() {
        assertThat(verify(field("블록을 쌓았어","CHILD_RECALL",2),Map.of()).get("situation")).isNotNull();
        assertThat(verify(field("친구와 다퉜어?","CHILD_RECALL",1),Map.of()).get("situation")).isNull();
        assertThat(verify(field("친구를 밀었어","CHILD_RECALL",3),Map.of()).get("situation")).isNull();
        assertThat(verify(field("친구를 때렸다","CHILD_RECALL",2),Map.of("knownContent","친구를 때렸다")).get("situation")).isNull();
    }
    @Test void guardianWordsKeepDeclaredSourceAndNeverGetAChildCitation() {
        for(String type:List.of("SEEN","HEARD_FROM_CHILD","UNSPECIFIED")){
            var value=ResultData.object(verify(field("친구와 다퉜다","GUARDIAN_INPUT",2),Map.of("knownContent","친구와 다퉜다","sourceType",type)).get("situation"));
            assertThat(value).containsEntry("source","GUARDIAN_INPUT").containsEntry("guardianSourceType",type).doesNotContainKey("utteranceSeq");
        }
    }
    @Test void curiosityIsNotEvidenceAndMissingGuardianMaterialCannotBecomeFact() {
        assertThat(verify(field("친구를 밀었어","GUARDIAN_INPUT",2),Map.of("question","친구를 밀었어")).get("situation")).isNull();
        assertThat(verify(field("친구를 밀었어","GUARDIAN_INPUT",2),Map.of("knownContent","블록 놀이")).get("situation")).isNull();
    }
    @Test void conflictingAccountsAreKeptAsDifferentSources() {
        var input=Map.<String,Object>of("characters",List.of(),"situation",field("친구와 다퉜다","GUARDIAN_INPUT",2),"childResponse",field("나는 블록을 쌓았어","CHILD_RECALL",2));
        var result=ExperienceSourceVerifier.verify(input,transcript,Map.of("knownContent","친구와 다퉜다","sourceType","HEARD_FROM_CHILD"));
        assertThat(ResultData.object(result.get("situation"))).containsEntry("source","GUARDIAN_INPUT");
        assertThat(ResultData.object(result.get("childResponse"))).containsEntry("source","CHILD_RECALL").containsEntry("utteranceSeq",2);
    }
}
