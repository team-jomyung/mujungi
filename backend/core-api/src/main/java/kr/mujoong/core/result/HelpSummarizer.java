package kr.mujoong.core.result;
import java.util.*;
/** BR4.6: 도움 수와 시간상 첫 동의 응답을 집계한다. */
public final class HelpSummarizer {
    private HelpSummarizer(){}
    public static Map<String,Object> summarize(Map<String,Object> goal){var cues=ResultData.objects(goal.get("cues"));String consent=cues.stream().min(Comparator.comparing(c->ResultData.text(c,"givenAt"))).map(c->ResultData.text(c,"consentResponse")).filter(c->Set.of("ACCEPTED","DECLINED","NO_RESPONSE").contains(c)).orElse("NONE");return Map.of("count",cues.size(),"consent",consent);}
}
