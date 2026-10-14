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
    public static boolean allVerified(String text,Collection<String> verified){return quoted(text).stream().allMatch(q->verified.stream().anyMatch(t->matches(q,t)));}
}
