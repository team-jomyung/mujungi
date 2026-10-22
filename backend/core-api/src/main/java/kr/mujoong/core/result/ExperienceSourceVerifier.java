package kr.mujoong.core.result;

import static kr.mujoong.core.result.ResultData.*;
import java.text.Normalizer;
import java.util.*;

/** 사실 인정이 아닌 출처 검증: 보호자 설명과 아이의 실제 발언 근거를 섞지 않는다. */
public final class ExperienceSourceVerifier {
    private ExperienceSourceVerifier() {}
    public static Map<String,Object> verify(Map<String,Object> output, List<Map<String,Object>> transcript,
            Map<String,Object> background) {
        Map<Long,String> child = new LinkedHashMap<>();
        for (var row : transcript) if ("CHILD".equals(row.get("speaker")) && !Boolean.TRUE.equals(row.get("uncertain")) && number(row,"seq") > 0)
            child.put(number(row,"seq"), text(row,"text"));
        String known = text(background,"knownContent");
        String sourceType = Set.of("SEEN","HEARD_FROM_CHILD").contains(text(background,"sourceType")) ? text(background,"sourceType") : "UNSPECIFIED";
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("characters", objects(output.get("characters")).stream()
                .map(c -> character(c,child,known,sourceType)).filter(Objects::nonNull).toList());
        for (String key : List.of("situation","partnerUtterance","childResponse","explanation")) {
            var field = object(output.get(key));
            result.put(key, field(field,child,known,sourceType));
        }
        return result;
    }
    private static Map<String,Object> character(Map<String,Object> value,Map<Long,String> child,String known,String type) {
        if ("CHILD_RECALL".equals(value.get("source")) && child.containsKey(number(value,"utteranceSeq"))) return Map.copyOf(value);
        if ("GUARDIAN_INPUT".equals(value.get("source")) && !known.isBlank()) return guardian(value,type);
        return null;
    }
    private static Map<String,Object> field(Map<String,Object> value,Map<Long,String> child,String known,String type) {
        String body=text(value,"text");
        if(body.isBlank())return null;
        if("CHILD_RECALL".equals(value.get("source"))) {
            String quote=child.get(number(value,"utteranceSeq"));
            return quote!=null && normalize(quote).contains(normalize(body)) ? Map.copyOf(value) : null;
        }
        if("GUARDIAN_INPUT".equals(value.get("source")) && !known.isBlank()
                && normalize(known).contains(normalize(body))) return guardian(value,type);
        return null;
    }
    private static Map<String,Object> guardian(Map<String,Object> value,String type) {
        var result=new LinkedHashMap<>(value);result.remove("utteranceSeq");result.put("guardianSourceType",type);return result;
    }
    private static String normalize(String value) {return Normalizer.normalize(value,Normalizer.Form.NFC).strip().replaceAll("\\s+"," ");}
}
