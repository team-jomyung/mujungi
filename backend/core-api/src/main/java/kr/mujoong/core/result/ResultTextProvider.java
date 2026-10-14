package kr.mujoong.core.result;
import java.time.Duration;
import java.util.Map;
/** U6-J: 실공급자는 별도 어댑터가 구현한다. 운영 기본값은 UNAVAILABLE다. */
@FunctionalInterface
public interface ResultTextProvider {
    String generate(Request request,Duration timeout);
    record Request(String task,String instructions,Map<String,Object> input,double temperature,String promptVersion){
        @Override public String toString(){return "ResultRequest[task="+task+"]";}
    }
}
