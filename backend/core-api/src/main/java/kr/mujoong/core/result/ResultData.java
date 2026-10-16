package kr.mujoong.core.result;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
/** 저장된 스냅숏을 읽는 작은 형식 어댑터. 누락된 연습 칸은 빈 목록으로 읽는다. */
public final class ResultData {
    private ResultData(){}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value){return value instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
    public static List<Map<String,Object>> objects(Object value){return value instanceof Collection<?> c?c.stream().filter(Map.class::isInstance).map(ResultData::object).toList():List.of();}
    public static List<Map<String,Object>> transcript(String json){return json==null?List.of():objects(JsonCodec.shared().read(json,List.class));}
    public static Map<String,Object> practice(String json){return json==null?Map.of():object(JsonCodec.shared().read(json,Map.class));}
    public static String text(Map<String,Object> map,String key){return map.get(key) instanceof String s?s:"";}
    public static long number(Map<String,Object> map,String key){return map.get(key) instanceof Number n?n.longValue():0;}
    public static UUID uuid(Object value){try{return value==null?null:UUID.fromString(value.toString());}catch(IllegalArgumentException e){return null;}}
    public static List<Map<String,Object>> goals(Map<String,Object> practice){return PracticeRecordV2.present(practice)?PracticeRecordV2.goals(practice):objects(practice.get("goals"));}
    public static boolean meaningful(Map<String,Object> utterance){return "CHILD".equals(utterance.get("speaker"))&&!Boolean.TRUE.equals(utterance.get("uncertain"))&&text(utterance,"text").strip().split("\\s+").length>=3;}
    public static Map<Long,String> childQuotes(List<Map<String,Object>> transcript){Map<Long,String> result=new LinkedHashMap<>();for(var u:transcript)if("CHILD".equals(u.get("speaker"))&&number(u,"seq")>0)result.put(number(u,"seq"),text(u,"text"));return result;}
}
