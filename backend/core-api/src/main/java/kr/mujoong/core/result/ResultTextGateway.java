package kr.mujoong.core.result;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.textai.TextAiProperties;
import kr.mujoong.core.usage.AiUsageScope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
/** BR3.2 · BR5.2: 최소 입력·한 번 호출·작업 잔여 시한·엄격 출력 검사를 묶는다. */
@Service
public class ResultTextGateway {
    private static final Set<String> PRIVATE_KEYS=Set.of("guardianId","childId","sessionId","deviceId","email","audio","audioRefs","audioRefsJson","interests");
    private static final Set<String> CONTEXT_KEYS=Set.of("knownContent","situationType","sourceType","question");
    private final ResultTextProvider provider;private final TextAiProperties.Result properties;private final MujoongClock clock;
    private final ExecutorService executor;private final JsonCodec json;
    public ResultTextGateway(ResultTextProvider provider,TextAiProperties properties,MujoongClock clock,@Qualifier("textAiExecutor") ExecutorService executor,JsonCodec json){this.provider=provider;this.properties=Objects.requireNonNull(properties.result());this.clock=clock;this.executor=executor;this.json=json;}
    public String promptVersion(){return properties.promptVersion();}
    public Map<String,Object> call(String task,Map<String,Object> data,Map<String,String> aliases,Instant deadline){
        return call(task,data,aliases,deadline,()->{});
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> call(String task,Map<String,Object> data,Map<String,String> aliases,Instant deadline,Runnable accessCheck){
        accessCheck.run();
        Duration remaining=Duration.between(clock.now(),deadline);if(remaining.isNegative()||remaining.isZero()||Thread.currentThread().isInterrupted())throw new ResultAiException("TIMEOUT");
        Duration limit=remaining.compareTo(properties.requestTimeout())<0?remaining:properties.requestTimeout();
        var references=new LinkedHashMap<String,String>();
        Map<String,Object> input=(Map<String,Object>)sanitize(json.read(json.write(data),Map.class),task,aliases,references);
        String instructions;
        try{instructions=new ClassPathResource("prompts/result/"+task+"."+properties.promptVersion()+".txt").getContentAsString(StandardCharsets.UTF_8);}catch(java.io.IOException e){throw new ResultAiException("INTERNAL");}
        var request=new ResultTextProvider.Request(task,instructions,input,task.equals("judgment")||task.equals("classify")?properties.temperatureJudgment():properties.temperatureGeneration(),properties.promptVersion());
        Future<String> future;
        accessCheck.run(); // 입력 조립 중 철회·삭제가 접수됐으면 공급자에게 넘기지 않는다.
        // 사용량 연결은 실행기 스레드로 옮겨야 Luna 호출부가 읽는다(JIRA-277)
        try{future=executor.submit(AiUsageScope.propagate(()->provider.generate(request,limit)));}catch(RejectedExecutionException e){throw new ResultAiException("PROVIDER_UNAVAILABLE");}
        String output;
        try{output=future.get(limit.toNanos(),TimeUnit.NANOSECONDS);}
        catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw new ResultAiException("TIMEOUT");}
        catch(TimeoutException e){future.cancel(true);throw new ResultAiException("TIMEOUT");}
        catch(ExecutionException e){if(e.getCause() instanceof ResultAiException failure)throw failure;throw new ResultAiException("PROVIDER_ERROR");}
        if(!clock.now().isBefore(deadline))throw new ResultAiException("TIMEOUT");
        try{
            if(output==null||output.length()>1_000_000)throw new ResultAiException("SCHEMA_INVALID");
            Map<String,Object> value=json.read(output,Map.class);ResultAiSchema.validate(task,value);
            return (Map<String,Object>)restore(value,references);
        }catch(ResultAiException e){throw e;}catch(RuntimeException e){throw new ResultAiException("SCHEMA_INVALID");}
    }
    private Object sanitize(Object value,String task,Map<String,String> aliases,Map<String,String> references){
        if(value instanceof Map<?,?> map){Map<String,Object> cleaned=new LinkedHashMap<>();map.forEach((k,v)->{String key=k.toString();if(!PRIVATE_KEYS.contains(key)&&(task.equals("structure")||!CONTEXT_KEYS.contains(key)))cleaned.put(key,sanitize(v,task,aliases,references));});return cleaned;}
        if(value instanceof Collection<?> list)return list.stream().map(v->sanitize(v,task,aliases,references)).toList();
        if(value instanceof UUID)value=value.toString();
        if(value instanceof String text){
            if(text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))return references.computeIfAbsent(text,k->"참조"+(references.size()+1));
            for(var alias:aliases.entrySet())if(!alias.getKey().isBlank())text=text.replace(alias.getKey(),alias.getValue());
            return text.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","연락처")
                    .replaceAll("(?<![0-9])01[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}(?![0-9])","연락처");
        }
        return value;
    }
    private Object restore(Object value,Map<String,String> references){
        if(value instanceof Map<?,?> map){Map<String,Object> restored=new LinkedHashMap<>();map.forEach((k,v)->{Object restoredValue=v;if(k.equals("goalSelectionId")&&v instanceof String alias)restoredValue=references.entrySet().stream().filter(e->e.getValue().equals(alias)).map(Map.Entry::getKey).findFirst().orElse(alias);restored.put(k.toString(),restore(restoredValue,references));});return restored;}
        if(value instanceof Collection<?> list)return list.stream().map(v->restore(v,references)).toList();return value;
    }
}
