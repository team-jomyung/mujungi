package kr.mujoong.core.result;
import java.util.UUID;
/** BR1.8: 결과 실행기가 리포트 구현에 의존하지 않고 확정 가능 여부만 확인한다. */
public interface ReportJobState { boolean lockGenerating(UUID reportId); }
