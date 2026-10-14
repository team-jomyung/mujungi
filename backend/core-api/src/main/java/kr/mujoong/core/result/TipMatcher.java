package kr.mujoong.core.result;
import java.util.*;
import kr.mujoong.core.knowledge.SupportLibrary;
import kr.mujoong.core.result.check.ForbiddenDictionary;
import org.springframework.stereotype.Component;
/** BR7: 승인 원문을 그대로 유지하며 사전에 걸린 항목은 감춘다. */
@Component
public class TipMatcher {
    private final SupportLibrary support;private final ForbiddenDictionary dictionary;
    public TipMatcher(SupportLibrary support,ForbiddenDictionary dictionary){this.support=support;this.dictionary=dictionary;}
    public record Match(List<SupportLibrary.Tip> tips,String noTipReason,int hidden){}
    public Match match(Collection<String> keys){var all=support.approvedByBehaviorKeys(keys);var tips=all.stream().filter(t->dictionary.allowedLiteral(t.title())&&dictionary.allowedLiteral(t.condition())&&dictionary.allowedLiteral(t.content())&&dictionary.allowedLiteral(t.limitation())).toList();return new Match(tips,tips.isEmpty()?"NEED_MORE_OBSERVATION":null,all.size()-tips.size());}
}
