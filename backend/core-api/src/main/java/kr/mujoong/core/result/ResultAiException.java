package kr.mujoong.core.result;
/** 공급자 본문이나 예외 원인을 담지 않는 작업 실패. */
public final class ResultAiException extends RuntimeException {
    private final String code;
    public ResultAiException(String code){super(code);this.code=code;}
    public String code(){return code;}
}
