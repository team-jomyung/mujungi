package kr.mujoong.core.result;
import java.util.*;
/** BR4.6: 실제 제공한 도움 수와 기록 순서상 첫 동의 응답을 집계한다. */
public final class HelpSummarizer {
    private HelpSummarizer(){}
    public static Map<String,Object> summarize(Map<String,Object> goal){
        var cues=ResultData.objects(goal.get("cues"));
        // U5b는 거절 · 무응답 · 미재생 동의도 보관하며, 실제 도움에만 givenAt을 남긴다.
        int count=(int)cues.stream().filter(c->!ResultData.text(c,"givenAt").isBlank()).count();
        // 생산자가 장면 순서대로 정렬한 동의를 보존한다. 미제공 행에는 정렬할 givenAt이 없다.
        String consent=cues.stream().findFirst().map(c->ResultData.text(c,"consentResponse")).filter(c->Set.of("ACCEPTED","DECLINED","NO_RESPONSE").contains(c)).orElse("NONE");
        return Map.of("count",count,"consent",consent);
    }
}
