package kr.mujoong.core.observability.snapshot;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.OptionalDouble;

/**
 * METRIC_SNAPSHOT의 CPU · 메모리 · 힙 표본 원천 (U1 3.6 수정 · OD-6 · NFR7.8 · J326). 값은 0 ~ 1 비율이고, JVM이 값을 주지 못하면 빈 값이다
 * (그 키는 줄에 쓰지 않는다 — OD-1 '값이 없는 키는 쓰지 않는다'). 시험은 고정 값 대역을 넣는다.
 */
public interface SystemLoad {

    /** 컨테이너가 보는 최근 CPU 사용 비율. */
    OptionalDouble cpu();

    /** 컨테이너가 보는 메모리 사용 비율. */
    OptionalDouble memory();

    /** JVM 힙 사용 비율(최대가 정해지지 않았으면 확보량 기준). */
    OptionalDouble heap();

    /** 운영: JDK 관리 빈(컨테이너 한도를 인지한다). */
    static SystemLoad platform() {
        return new Platform(ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class),
                ManagementFactory.getMemoryMXBean());
    }

    /** JDK 관리 빈 구현. */
    final class Platform implements SystemLoad {

        private final com.sun.management.OperatingSystemMXBean os;
        private final MemoryMXBean memory;

        Platform(com.sun.management.OperatingSystemMXBean os, MemoryMXBean memory) {
            this.os = os;
            this.memory = memory;
        }

        @Override
        public OptionalDouble cpu() {
            return os == null ? OptionalDouble.empty() : ratio(os.getCpuLoad());
        }

        @Override
        public OptionalDouble memory() {
            if (os == null) {
                return OptionalDouble.empty();
            }
            long total = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            return total <= 0 || free < 0 ? OptionalDouble.empty() : ratio((total - free) / (double) total);
        }

        @Override
        public OptionalDouble heap() {
            MemoryUsage heap = memory.getHeapMemoryUsage();
            long limit = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
            return limit <= 0 ? OptionalDouble.empty() : ratio(heap.getUsed() / (double) limit);
        }

        /** JVM이 '모름'으로 주는 음수 · NaN은 빈 값, 나머지는 0 ~ 1로 자른다. */
        static OptionalDouble ratio(double value) {
            if (Double.isNaN(value) || value < 0) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(Math.min(1.0, value));
        }
    }
}
