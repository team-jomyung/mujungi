package kr.mujoong.core.security.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.common.scaling.SingleInstanceAssumption;

/**
 * 앱 메모리 슬라이딩 창 한도기 (U1 3.5 Step 19 · SD-10 · BR1.6 · BR2.6 · NFR7.4 · NFR9.9).
 *
 * <p>키마다 창({@code window}) 안의 시각 기록을 들고 {@code max}회까지 허락한다. 시각은 주입한 Clock이 정한다(NFR6.4 · R5). 키 수는
 * {@code mujoong.limits.rate-counter-max-keys}(10,000)를 넘지 않는다: 가득 차면 창이 끝난 키부터 지우고 그래도 모자라면 가장 오래전에 쓴
 * 키를 밀어낸다(LRU). 카운터는 메모리에만 있어 재시작하면 0에서 시작한다(SI-5 표식, 3.6 수정 J335).
 *
 * <p>한도기는 남용 억제 장치이지 인증 판정의 일부가 아니다. 내부 오류(예외)가 나면 요청을 통과시키고 오류 처리기(WARN 사건 ·
 * {@code mujoong_rate_limiter_errors_total})에 알린다(fail-open, SD-10). 출력 게이트의 fail-closed와 반대다.
 *
 * <p>쓰임: bootstrap 발급 IP 한도(BR1.6, 20회 · 1시간, 키 {@link #ipKey}) — {@link #tryAcquire}. 등록 실패 한도(BR2.6, 10회 · 10분,
 * 키 보호자 id) — 요청 처음에 {@link #isLimited}, 400 · 404 · 409면 {@link #record}, 성공하면 {@link #clear}.
 */
@SingleInstanceAssumption(si = "SI-5", reason = "카운터가 인스턴스마다 따로라 한도가 인스턴스 수만큼 늘고 재시작하면 초기화된다")
public final class SlidingWindowRateLimiter {

    /** 한도기 이름(로그 limiter · 지표 라벨). */
    public enum Limiter {
        BOOTSTRAP_IP,
        REGISTER_FAILURE
    }

    /** 한도기에서 일어난 일을 바깥(지표 · 로그)에 알린다. */
    public interface Listener {
        /** 키 상한 때문에 키를 밀어냈다. */
        default void evicted(Limiter limiter) {
        }

        /** 내부 오류로 요청을 통과시켰다. */
        default void failedOpen(Limiter limiter, RuntimeException error) {
        }
    }

    private static final Pattern IPV4 = Pattern.compile("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final Limiter limiter;
    private final int max;
    private final Duration window;
    private final int maxKeys;
    private final MujoongClock clock;
    private final Listener listener;
    private final LinkedHashMap<String, Deque<Instant>> hits = new LinkedHashMap<>(16, 0.75f, true);

    public SlidingWindowRateLimiter(
            Limiter limiter, int max, Duration window, int maxKeys, MujoongClock clock, Listener listener) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        if (max <= 0) {
            throw new IllegalArgumentException("한도 횟수는 0보다 커야 한다");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("한도 창은 0보다 커야 한다");
        }
        if (maxKeys <= 0) {
            throw new IllegalArgumentException("키 상한은 0보다 커야 한다");
        }
        this.max = max;
        this.window = window;
        this.maxKeys = maxKeys;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = listener == null ? new Listener() { } : listener;
    }

    public Limiter limiter() {
        return limiter;
    }

    public int max() {
        return max;
    }

    public Duration window() {
        return window;
    }

    /** 창 안 횟수가 한도 미만이면 한 번 기록하고 참, 한도에 닿았으면 기록하지 않고 거짓. 내부 오류면 참(fail-open). */
    public boolean tryAcquire(String key) {
        return guarded(() -> {
            synchronized (this) {
                Instant now = clock.now();
                Deque<Instant> log = prunedLog(key, now, true);
                if (log.size() >= max) {
                    return false;
                }
                log.addLast(now);
                return true;
            }
        }, true);
    }

    /** 창 안 횟수가 한도에 닿았는가(기록하지 않는다). 내부 오류면 거짓(fail-open). */
    public boolean isLimited(String key) {
        return guarded(() -> {
            synchronized (this) {
                Deque<Instant> log = prunedLog(key, clock.now(), false);
                return log != null && log.size() >= max;
            }
        }, false);
    }

    /** 한 번 기록한다(등록 실패 가산). 내부 오류는 알리고 넘어간다. */
    public void record(String key) {
        guarded(() -> {
            synchronized (this) {
                Instant now = clock.now();
                prunedLog(key, now, true).addLast(now);
                return Boolean.TRUE;
            }
        }, true);
    }

    /** 키의 기록을 지운다(등록 성공). */
    public synchronized void clear(String key) {
        hits.remove(Objects.requireNonNull(key, "key"));
    }

    /** 창 안 횟수(진단 · 시험). */
    public synchronized int count(String key) {
        Deque<Instant> log = prunedLog(key, clock.now(), false);
        return log == null ? 0 : log.size();
    }

    /** 들고 있는 키 수(진단 · 시험). */
    public synchronized int keyCount() {
        return hits.size();
    }

    /**
     * 클라이언트 주소를 한도 키로 바꾼다(BR1.6). IPv4는 그대로, IPv6은 앞 64비트로 묶는다(IPv4 대응 IPv6은 IPv4로). 주소 형식이 아니면
     * 모두 같은 키 {@code invalid}로 센다. 이름 조회(DNS)는 하지 않는다.
     */
    public static String ipKey(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "invalid";
        }
        String value = remoteAddress.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        int scope = value.indexOf('%');
        if (scope >= 0) {
            value = value.substring(0, scope);
        }
        if (value.indexOf(':') < 0) {
            return IPV4.matcher(value).matches() ? value : "invalid";
        }
        if (!IPV6_CHARS.matcher(value).matches()) {
            return "invalid";
        }
        try {
            byte[] address = InetAddress.getByName(value).getAddress();
            if (address.length == 4) {
                return InetAddress.getByAddress(address).getHostAddress();
            }
            byte[] prefix = new byte[8];
            System.arraycopy(address, 0, prefix, 0, 8);
            return HexFormat.of().formatHex(prefix) + "::/64";
        } catch (UnknownHostException e) {
            return "invalid";
        }
    }

    // ------------------------------------------------------------------ 내부

    private boolean guarded(Supplier<Boolean> body, boolean failOpenValue) {
        try {
            return body.get();
        } catch (RuntimeException error) {
            listener.failedOpen(limiter, error);
            return failOpenValue;
        }
    }

    /** 창이 지난 기록을 버린 키의 기록. {@code create}가 거짓이고 키가 없으면 null. */
    private Deque<Instant> prunedLog(String key, Instant now, boolean create) {
        Objects.requireNonNull(key, "key");
        Instant windowStart = now.minus(window);
        Deque<Instant> log = hits.get(key);
        if (log == null) {
            if (!create) {
                return null;
            }
            makeRoom(windowStart);
            log = new ArrayDeque<>(Math.min(max, 32));
            hits.put(key, log);
            return log;
        }
        dropOld(log, windowStart);
        return log;
    }

    private void makeRoom(Instant windowStart) {
        if (hits.size() < maxKeys) {
            return;
        }
        Iterator<Map.Entry<String, Deque<Instant>>> iterator = hits.entrySet().iterator();
        while (iterator.hasNext()) {
            Deque<Instant> log = iterator.next().getValue();
            dropOld(log, windowStart);
            if (log.isEmpty()) {
                iterator.remove();
            }
        }
        if (hits.size() >= maxKeys) {
            Iterator<String> eldest = hits.keySet().iterator();
            eldest.next();
            eldest.remove();
            listener.evicted(limiter);
        }
    }

    private static void dropOld(Deque<Instant> log, Instant windowStart) {
        while (!log.isEmpty() && !log.peekFirst().isAfter(windowStart)) {
            log.pollFirst();
        }
    }
}
