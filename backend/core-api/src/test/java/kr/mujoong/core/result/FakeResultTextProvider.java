package kr.mujoong.core.result;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
/** 시험 전용 가짜 공급자. 운영 소스에는 성공 응답 대역을 두지 않는다. */
public final class FakeResultTextProvider implements ResultTextProvider {
    public final List<Request> requests=new CopyOnWriteArrayList<>();
    public Function<Request,String> response=r->"{}";
    public String generate(Request request,Duration timeout){requests.add(request);return response.apply(request);}
}
