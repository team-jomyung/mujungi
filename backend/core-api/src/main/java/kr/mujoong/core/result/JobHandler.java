package kr.mujoong.core.result;
/** BR1.1: DB 트랜잭션 밖에서 읽기·생성을 수행하고 context.commit으로만 결과를 확정한다. */
public interface JobHandler {
    String jobType();
    Outcome handle(JobContext context);
}
