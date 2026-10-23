package kr.mujoong.core.result;
import java.util.*;
/** U6-J: AI 응답의 필드·자료형·열거값을 허용 목록으로 검증한다. 모르는 필드도 거절한다. */
final class ResultAiSchema {
    private ResultAiSchema(){}
    static void validate(String task,Map<String,Object> root){
        switch(task){
            case "structure"->{keys(root,"characters","situation","partnerUtterance","childResponse","explanation");
                for(var c:objects(root,"characters")){keys(c,"alias","source","utteranceSeq");text(c,"alias");source(c);}
                for(String key:List.of("situation","partnerUtterance","childResponse","explanation"))if(root.get(key)!=null){var field=object(root.get(key));keys(field,"text","source","utteranceSeq");text(field,"text");source(field);}}
            case "sentences"->{keys(root,"sentences");for(var s:objects(root,"sentences")){keys(s,"goalSelectionId","text","citations");text(s,"text");for(var c:objects(s,"citations")){keys(c,"utteranceSeq","quote");positive(c,"utteranceSeq");if(c.get("quote")!=null)text(c,"quote");}}}
            case "candidates"->{keys(root,"candidates");for(var c:objects(root,"candidates")){keys(c,"behaviorKey","behaviorVersion","reasonText","evidence");text(c,"behaviorKey");positive(c,"behaviorVersion");text(c,"reasonText");for(var e:objects(c,"evidence")){keys(e,"utteranceSeq","quote","kind");positive(e,"utteranceSeq");text(e,"quote");enumeration(e,"kind",Set.of("CHILD_RECALL","GUARDIAN_INPUT"),false);}}}
            case "judgment"->{keys(root,"goals");for(var g:objects(root,"goals")){keys(g,"goalSelectionId","scenes");text(g,"goalSelectionId");for(var s:objects(g,"scenes")){keys(s,"sceneNo","firstResponse","afterHelpResponse","value","timing","rationale","notDoneReason");int number=positive(s,"sceneNo");if(number>2)invalid();enumeration(s,"value",Set.of("functional","partial","none","uncertain"),true);enumeration(s,"timing",Set.of("IMMEDIATE","AFTER_HELP"),true);enumeration(s,"notDoneReason",Set.of("TIME_OVER","SCENARIO_FAILED","ACTIVITY_ENDED","NO_RESPONSE"),true);if(s.get("value")==null&&s.get("notDoneReason")==null)invalid();for(String response:List.of("firstResponse","afterHelpResponse"))if(s.get(response)!=null){var r=object(s.get(response));keys(r,"utteranceSeq","quote");positive(r,"utteranceSeq");text(r,"quote");}}}}
            case "classify"->{keys(root,"results");for(var r:objects(root,"results")){keys(r,"id","violation","category","span");text(r,"id");bool(r,"violation");enumeration(r,"category",Set.of("DIAGNOSIS","TREATMENT","SCORE","CAUSE","GENERALIZATION","EMOTION_CLAIM","PRESSURE"),true);if(Boolean.TRUE.equals(r.get("violation"))){text(r,"category");text(r,"span");}}}
            default->invalid();
        }
    }
    static void keys(Map<String,Object> m,String... keys){if(!Set.of(keys).containsAll(m.keySet()))invalid();}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object o){if(!(o instanceof Map<?,?>))invalid();return (Map<String,Object>)o;}
    static List<Map<String,Object>> objects(Map<String,Object> m,String key){if(!(m.get(key) instanceof List<?>))invalid();return ((List<?>)m.get(key)).stream().map(ResultAiSchema::object).toList();}
    static String text(Map<String,Object> m,String key){if(!(m.get(key) instanceof String s)||s.isBlank()||s.length()>16000)invalid();return (String)m.get(key);}
    static int positive(Map<String,Object> m,String key){if(!(m.get(key) instanceof Number n)||n.doubleValue()!=n.longValue()||n.longValue()<1||n.longValue()>Integer.MAX_VALUE)invalid();return ((Number)m.get(key)).intValue();}
    static void bool(Map<String,Object> m,String key){if(!(m.get(key) instanceof Boolean))invalid();}
    static void enumeration(Map<String,Object> m,String key,Set<String> values,boolean nullable){Object v=m.get(key);if(v==null&&nullable)return;if(!(v instanceof String s)||!values.contains(s))invalid();}
    static void source(Map<String,Object> m){enumeration(m,"source",Set.of("GUARDIAN_INPUT","CHILD_RECALL"),false);if("CHILD_RECALL".equals(m.get("source")))positive(m,"utteranceSeq");}
    static void invalid(){throw new ResultAiException("SCHEMA_INVALID");}
}
