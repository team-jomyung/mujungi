package kr.mujoong.core.result;
import java.time.Instant;
/** BR1.6: 처리기는 결과 또는 정해진 오류 코드만 반환한다. */
public record Outcome(boolean successful,String code,boolean retryable,Instant deferUntil) {
    public static Outcome done(){return new Outcome(true,null,false,null);}
    public static Outcome failed(String code,boolean retryable){return new Outcome(false,code,retryable,null);}
    /**
     * 실패가 아니라 {@code until}에 다시 본다(오류 코드 없음, 시도 횟수를 쓰지 않는다). 리포트가 결과 정리를 기다릴 때 쓴다
     * (contracts/deviations.md FT-1023-c). 절대 마감 이후로는 미룰 수 없다.
     */
    public static Outcome deferredUntil(Instant until){return new Outcome(false,null,true,java.util.Objects.requireNonNull(until,"until"));}
    public boolean isDeferred(){return deferUntil!=null;}
}
