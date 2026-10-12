package kr.mujoong.realtime.common.collect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 크기 상한이 있는 집합 · 지도 (2차 리뷰 8 — 실행기 · 전사 기록기 · 원음 기록기가 같은 모양을 셋 따로 두던 것을 모았다).
 *
 * <p>넣은 순서를 기억하고 상한을 넘으면 가장 먼저 넣은 것부터 잊는다. "최근 몇 건만 기억하면 되는" 표시(취소한 응답 · 기록을 막은 세션 등)에
 * 쓴다. 기본은 한 스레드용이고, 여러 스레드가 함께 쓰면 {@link #synchronizedSet}을 쓴다.
 */
public final class BoundedCollections {

    private BoundedCollections() {
    }

    /** 상한 {@code max}의 지도(한 스레드용). */
    public static <K, V> Map<K, V> map(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("상한은 0보다 커야 한다");
        }
        return new LinkedHashMap<>() {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    /** 상한 {@code max}의 집합(한 스레드용). */
    public static <T> Set<T> set(int max) {
        return Collections.newSetFromMap(map(max));
    }

    /** 상한 {@code max}의 집합(여러 스레드용, 메서드마다 잠금). */
    public static <T> Set<T> synchronizedSet(int max) {
        return Collections.synchronizedSet(set(max));
    }
}
