package kr.mujoong.core.result;
/** BR1.6: 처리기는 결과 또는 정해진 오류 코드만 반환한다. */
public record Outcome(boolean successful,String code,boolean retryable) {
    public static Outcome done(){return new Outcome(true,null,false);}
    public static Outcome failed(String code,boolean retryable){return new Outcome(false,code,retryable);}
}
