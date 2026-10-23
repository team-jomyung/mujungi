package kr.mujoong.core.result;
/** BR1.6: 처리기는 결과 또는 정해진 오류 코드만 반환한다. */
public record Outcome(boolean successful,String code,boolean retryable) {
    public static Outcome done(){return new Outcome(true,null,false);}
    public static Outcome failed(String code,boolean retryable){return new Outcome(false,code,retryable);}
    /**
     * 실패가 아니라 다음 시도로 미룬다(오류 코드 없음). 처리기는 {@link JobContext#retryAvailable()}가 참일 때만 쓴다.
     * 리포트가 정리 중 활동을 기다릴 때 쓴다(contracts/deviations.md FT-1023-c).
     */
    public static Outcome deferred(){return new Outcome(false,null,true);}
    public boolean isDeferred(){return !successful&&code==null&&retryable;}
}
