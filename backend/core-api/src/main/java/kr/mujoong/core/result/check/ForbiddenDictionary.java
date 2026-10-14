package kr.mujoong.core.result.check;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
/** BR5.2 · U6-J: 리터럴 사전. 생성 글의 직접 인용 밖을 검사한다. */
@Component
public class ForbiddenDictionary {
    private static final Set<String> CATEGORIES=Set.of("DIAGNOSIS","TREATMENT","SCORE","CAUSE","GENERALIZATION","EMOTION_CLAIM","PRESSURE");
    private final List<Entry> entries;
    public record Entry(String id,String pattern,String category,List<String> appliesTo){}
    public record Dictionary(String version,String level,List<Entry> entries){}
    public ForbiddenDictionary(){try{entries=load(new ClassPathResource("forbidden/result-v1.json").getContentAsString(StandardCharsets.UTF_8));}catch(java.io.IOException e){throw new IllegalStateException("RESULT_DICTIONARY_MISSING");}}
    public ForbiddenDictionary(String source){entries=load(source);}
    private static List<Entry> load(String source){
        Dictionary d=JsonCodec.shared().read(source,Dictionary.class);Set<String> ids=new HashSet<>(),categories=new HashSet<>();
        if(d.entries()==null||d.entries().isEmpty())throw new IllegalStateException("RESULT_DICTIONARY_EMPTY");
        for(Entry e:d.entries()){
            if(e.id()==null||!ids.add(e.id())||e.pattern()==null||e.pattern().isBlank()||!normalize(e.pattern()).equals(e.pattern())
                    ||e.appliesTo()==null||e.appliesTo().stream().noneMatch(v->v.equals("RESULT_TEXT")||v.equals("PDF"))||!CATEGORIES.contains(e.category()))throw new IllegalStateException("RESULT_DICTIONARY_INVALID");
            categories.add(e.category());
        }
        if(!categories.containsAll(CATEGORIES))throw new IllegalStateException("RESULT_DICTIONARY_CATEGORY_MISSING");
        return List.copyOf(d.entries());
    }
    public boolean allowed(String text){String value=normalize(QuoteMatcher.outsideQuotes(withoutFixedNotice(text)));return entries.stream().noneMatch(e->matches(value,e.pattern()));}
    /** 승인 팁은 인용도 포함한 모든 문구를 검사한다. */
    public boolean allowedLiteral(String text){String value=normalize(withoutFixedNotice(text==null?"":text));return entries.stream().noneMatch(e->matches(value,e.pattern()));}
    private static String withoutFixedNotice(String text){return text.replace("이 리포트는 진단 결과가 아니에요. 전문가에게 보여 줄 참고 자료예요. 제한사항은 마지막 항목에 있어요.","");}
    private static boolean matches(String value,String pattern){
        if(!pattern.contains("#"))return value.contains(pattern);
        // 사전에 허용한 숫자 자리표시만 정규식으로 바꾼다. 리터럴 조각은 모두 인용한다.
        return java.util.regex.Pattern.compile(Arrays.stream(pattern.split("#",-1)).map(java.util.regex.Pattern::quote).collect(java.util.stream.Collectors.joining("[0-9]+"))).matcher(value).find();
    }
    private static String normalize(String text){var out=new StringBuilder();Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).codePoints().filter(c->Character.isLetterOrDigit(c)||c=='%'||c=='#').forEach(out::appendCodePoint);return out.toString();}
}
