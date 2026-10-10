package kr.mujoong.realtime.common.db;

import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션 전파 MANDATORY 확인 (U1 3.5 Step 16 · RD-22 · BR5.1 · BR3.7 · BR6.8 · BR7.5).
 *
 * <p>트랜잭션 경계는 서비스 계층 한 곳이고 저장소는 새로 시작하지 않는다(RD-22). 그래서 같은 트랜잭션 안에서만 뜻이 있는 쓰기
 * (Outbox 기록, {@code FOR UPDATE} 잠금, 기록기 배치)는 활성 트랜잭션이 없으면 실패한다. 애노테이션 프록시에 기대지 않고
 * 직접 확인하므로 Spring 문맥 밖(시험의 {@code TransactionTemplate})에서도 같은 규칙이다.
 */
public final class Transactions {

    private Transactions() {
    }

    /**
     * 활성 트랜잭션이 없으면 {@link IllegalTransactionStateException}.
     *
     * @param operation 오류 메시지에 쓸 연산 이름(값 없음)
     */
    public static void requireActive(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    operation + "는 업무 트랜잭션 안에서만 부를 수 있다(전파 MANDATORY, RD-22)");
        }
    }
}
