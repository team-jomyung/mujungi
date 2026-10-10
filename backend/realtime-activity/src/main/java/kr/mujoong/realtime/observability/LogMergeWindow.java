package kr.mujoong.realtime.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 로그 반복 병합 창 (U1 3.5 Step 26 · OD-3 4번 · SD-15 '로그 홍수 방지' ①).
 *
 * <p>같은 키(사건 · 컴포넌트 · 식별 attrs)가 {@code mujoong.observability.log-merge-window}(10초) 안에 반복되면 첫 줄만 바로 쓰고, 창이 끝날
 * 때 그 사이 반복 건수({@code repeat})를 붙인 한 줄을 더 쓴다. 병합 창 안의 줄 수는 {@code 1 + repeat}로 센다. 창의 끝은 같은 키의 다음 기록이나
 * 주기 비우기({@link #drainExpired})가 알아챈다. 키 수에는 상한이 있어 위조 · 다양한 attrs로 메모리가 차지 않는다(상한을 넘으면 병합하지
 * 않고 바로 쓴다). 시각은 주입한 Clock 값만 받는다. 스레드 안전하다.
 *
 * @param <T> 다시 그릴 첫 줄(예: 사건 작성기)
 */
public final class LogMergeWindow<T> {

    /** 동시에 들고 있는 병합 키 상한. */
    static final int MAX_KEYS = 1_000;

    private final Duration window;
    private final Map<Object, Slot<T>> slots = new HashMap<>();

    public LogMergeWindow(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("병합 창은 0보다 커야 한다");
        }
        this.window = window;
    }

    /**
     * 한 건을 넣는다.
     *
     * @return {@code emitNow}가 참이면 이 건을 바로 써야 한다(창의 첫 건). {@code expired}는 같은 키의 지난 창이 끝나 함께 써야 할 반복 줄
     */
    public synchronized Offer<T> offer(Object key, T item, Instant now) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(now, "now");
        List<Repeated<T>> expired = new ArrayList<>(1);
        Slot<T> slot = slots.get(key);
        if (slot != null && !now.isBefore(slot.windowEnd)) {
            slots.remove(key);
            if (slot.repeats > 0) {
                expired.add(new Repeated<>(slot.first, slot.repeats));
            }
            slot = null;
        }
        if (slot == null) {
            if (slots.size() >= MAX_KEYS) {
                expired.addAll(drainExpiredLocked(now));
            }
            if (slots.size() < MAX_KEYS) {
                slots.put(key, new Slot<>(item, now.plus(window)));
            }
            return new Offer<>(true, expired);
        }
        slot.repeats++;
        return new Offer<>(false, expired);
    }

    /** 창이 끝난 키를 비우고 반복 건이 있던 키의 반복 줄을 돌려준다(주기 비우기). */
    public synchronized List<Repeated<T>> drainExpired(Instant now) {
        return drainExpiredLocked(Objects.requireNonNull(now, "now"));
    }

    /** 들고 있는 키 수. */
    public synchronized int size() {
        return slots.size();
    }

    private List<Repeated<T>> drainExpiredLocked(Instant now) {
        List<Repeated<T>> expired = new ArrayList<>();
        Iterator<Slot<T>> iterator = slots.values().iterator();
        while (iterator.hasNext()) {
            Slot<T> slot = iterator.next();
            if (!now.isBefore(slot.windowEnd)) {
                iterator.remove();
                if (slot.repeats > 0) {
                    expired.add(new Repeated<>(slot.first, slot.repeats));
                }
            }
        }
        return expired;
    }

    /** 한 건을 넣은 결과. */
    public record Offer<T>(boolean emitNow, List<Repeated<T>> expired) {
        public Offer {
            expired = List.copyOf(expired);
        }
    }

    /** 창이 끝나 써야 할 반복 줄(첫 건과 그 뒤 반복 건수). */
    public record Repeated<T>(T first, long repeat) {
    }

    private static final class Slot<T> {
        private final T first;
        private final Instant windowEnd;
        private long repeats;

        Slot(T first, Instant windowEnd) {
            this.first = first;
            this.windowEnd = windowEnd;
        }
    }
}
