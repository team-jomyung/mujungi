package kr.mujoong.core.common.scaling;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 단일 인스턴스 전제 표식 (U1 3.6 수정 · NFR7.4 · SCD-3 · SCD-4 · J335).
 *
 * <p>Core가 인스턴스 하나로만 돈다는 전제(수직 확장만, Compose 서비스당 컨테이너 1개)에 기대는 메모리 상태 · 주기 작업을 가진 클래스에 단다.
 * {@link #si}는 scalability-design.md 2.2 표 2.2-A의 번호({@code SI-1} ~ {@code SI-12})이고 {@link #reason}은 수평 확장을 시도하면 깨지는
 * 모습이다. 재설계 방향은 표에만 두고 여기서는 구현하지 않는다. 정의는 Core 공용 패키지에 두고 공통 계약 라이브러리(contract-model)에는 넣지
 * 않는다(SCD-4: 그 라이브러리는 계약 · 오류 · 시간 · 타입만 공유). 표와 표식의 대조는 구조 시험 {@code SingleInstanceMarkerTest}가 한다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SingleInstanceAssumption {

    /** 표 2.2-A의 번호({@code SI-n}). 한 클래스가 여러 지점의 상태를 가지면 모두 적는다. */
    String[] si();

    /** 수평 확장 때 깨지는 모습(한 문장). */
    String reason();
}
