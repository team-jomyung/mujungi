package kr.mujoong.core.result.check;
import java.util.*;
import java.util.regex.Pattern;
/** BR5.2: 앞뒤 공백 이외에는 인용을 고치지 않는다. */
public final class QuoteMatcher {
    private static final Pattern QUOTES=Pattern.compile("\"([^\"]*)\"|“([^”]*)”|‘([^’]*)’");
    private QuoteMatcher(){}
    public static boolean matches(String quote,String transcript){return quote!=null&&transcript!=null&&quote.strip().equals(transcript.strip());}
    public static List<String> quoted(String text){var found=new ArrayList<String>();var m=QUOTES.matcher(text);while(m.find())found.add(m.group(1)!=null?m.group(1):m.group(2)!=null?m.group(2):m.group(3));return found;}
    public static String outsideQuotes(String text){return QUOTES.matcher(text).replaceAll(" ");}
    /**
     * 결과 문장 안의 직접 인용 대조. 인용이 그 발화 전체와 같거나, 발화 안에서 낱말 경계로 끊긴 연속 일부면 통과한다(편차 FT-1102-d).
     * 글자는 고치지 않으며(앞뒤 공백만 무시), 낱말 중간에서 끊은 인용(「싫어하지」에서 「싫어」)은 뜻이 바뀔 수 있어 막는다.
     */
    public static boolean allVerified(String text,Collection<String> verified){return quoted(text).stream().allMatch(q->verified.stream().anyMatch(t->appearsIn(q,t)));}
    static boolean appearsIn(String quote,String transcript){
        if(quote==null||transcript==null)return false;
        String q=quote.strip(),t=transcript.strip();
        if(q.isEmpty())return false;
        if(q.equals(t))return true;
        for(int at=t.indexOf(q);at>=0;at=t.indexOf(q,at+1)){
            int end=at+q.length();
            boolean before=at==0||!Character.isLetterOrDigit(t.codePointBefore(at));
            boolean after=end==t.length()||!Character.isLetterOrDigit(t.codePointAt(end));
            if(before&&after)return true;
        }
        return false;
    }
}
