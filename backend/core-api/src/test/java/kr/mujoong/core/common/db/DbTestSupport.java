package kr.mujoong.core.common.db;

import static org.assertj.core.api.Assertions.fail;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import javax.sql.DataSource;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.db.MujoongPostgres.Role;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Step 17 데이터 접근 시험의 공통 지원(시험 소스 전용).
 *
 * <ul>
 *   <li>앱 역할 core_app으로 공용 PostgreSQL(MujoongPostgres)에 붙는다. 저장소는 운영과 같은 NamedParameterJdbcTemplate을 쓰고 경계는
 *       시험이 TransactionTemplate으로 연다(저장소는 트랜잭션을 열지 않는다, RD-22).
 *   <li>행은 시험마다 새 UUID로 만든다. 시각은 {@link #EPOCH} 근처의 고정 값이다 — 다른 시험 클래스의 행(2026년대)이 시각 조건
 *       질의(만료 · 도래 · 정리)에 걸리지 않게 일부러 먼 과거를 쓴다.
 *   <li>커밋한 행은 {@link #cleanUp()}이 외래 키 순서(활동 → 기기 → 동의 · 아이 → 보호자)로 지운다.
 *   <li>동시성 시험은 {@link #startTx}로 다른 스레드에서 트랜잭션을 열고 {@link #awaitLockWait}로 그 연결이 실제로 락을 기다리는지
 *       pg_stat_activity로 확인한다(실제 시간을 짐작으로 기다리지 않는다).
 * </ul>
 */
public final class DbTestSupport implements AutoCloseable {

    /** 시험 시각의 기준(먼 과거, 위 설명). */
    public static final Instant EPOCH = Instant.parse("2001-03-01T00:00:00Z");

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(2);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private final MujoongPostgres postgres;
    private final DataSource dataSource;
    private final NamedParameterJdbcTemplate jdbc;
    private final DataSourceTransactionManager transactionManager;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final List<UUID> guardians = new ArrayList<>();
    private final List<UUID> devices = new ArrayList<>();
    private final List<UUID> outboxEvents = new ArrayList<>();
    private final List<UUID> inboxEvents = new ArrayList<>();

    private DbTestSupport(MujoongPostgres postgres) {
        this.postgres = postgres;
        this.dataSource = postgres.dataSource(Role.CORE_APP);
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transactionManager = new DataSourceTransactionManager(dataSource);
    }

    /** 공용 DB(없으면 시험 건너뜀)에 core_app으로 붙는다. */
    public static DbTestSupport coreApp() {
        return new DbTestSupport(MujoongPostgres.sharedOrAbort());
    }

    /** 시험이 따로 띄운 DB에 core_app으로 붙는다(공용 DB에 커밋하면 안 되는 참조 시드 시험, U4a). */
    public static DbTestSupport coreApp(MujoongPostgres postgres) {
        return new DbTestSupport(postgres);
    }

    public MujoongPostgres postgres() {
        return postgres;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** 저장소에 넘길 운영과 같은 형식의 템플릿. */
    public NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    /** 새 트랜잭션 템플릿(READ COMMITTED 기본). */
    public TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** 한 트랜잭션에서 실행하고 끝에 되돌린다(행을 남기지 않는 시험). */
    public <T> T inRolledBackTx(Function<TransactionStatus, T> body) {
        return tx().execute(status -> {
            status.setRollbackOnly();
            return body.apply(status);
        });
    }

    /** 커밋하는 트랜잭션. */
    public <T> T inTx(Function<TransactionStatus, T> body) {
        return tx().execute(body::apply);
    }

    // ------------------------------------------------------------------ 동시성

    /**
     * 다른 스레드에서 트랜잭션을 열어 {@code body}를 실행한다. 트랜잭션 안에서 맨 먼저 그 연결의 백엔드 pid를 {@code pid}에 적는다.
     */
    public <T> Future<T> startTx(AtomicInteger pid, Function<TransactionStatus, T> body) {
        return executor.submit(() -> tx().execute(status -> {
            pid.set(backendPid());
            return body.apply(status);
        }));
    }

    /** 다른 스레드에서 트랜잭션 없이 실행한다. */
    public <T> Future<T> start(Callable<T> body) {
        return executor.submit(body);
    }

    /** 지금 연결(트랜잭션에 묶인 연결)의 백엔드 pid. */
    public int backendPid() {
        Integer pid = jdbc.getJdbcTemplate().queryForObject("select pg_backend_pid()", Integer.class);
        return pid == null ? -1 : pid;
    }

    /** {@code pid}가 정해지고 그 백엔드가 락을 기다리기 시작할 때까지 기다린다(상한 15초). */
    public void awaitLockWait(AtomicInteger pid) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            int current = pid.get();
            if (current > 0) {
                List<String> waits = jdbc.queryForList(
                        "select coalesce(wait_event_type, '') from pg_stat_activity where pid = :pid",
                        new MapSqlParameterSource("pid", current), String.class);
                if (!waits.isEmpty() && "Lock".equals(waits.get(0))) {
                    return;
                }
            }
            LockSupport.parkNanos(POLL_INTERVAL.toNanos());
        }
        fail("백엔드 pid " + pid.get() + "가 15초 안에 락 대기에 들어가지 않았다");
    }

    /** 다른 스레드 결과(상한 15초). */
    public static <T> T await(Future<T> future) {
        try {
            return future.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("다른 스레드의 트랜잭션이 끝나지 않았거나 실패했다", e);
        }
    }

    // ------------------------------------------------------------------ 행 만들기(커밋하면 cleanUp 대상)

    public UUID insertGuardian(String status) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = ClockParams.at(EPOCH).addValue("id", id).addValue("status", status);
        jdbc.update("insert into core.core_guardian (id, status, created_at, updated_at) values (:id, :status, :now, :now)",
                params);
        guardians.add(id);
        return id;
    }

    public UUID insertChild(UUID guardianId) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource params = ClockParams.at(EPOCH).addValue("id", id).addValue("guardianId", guardianId);
        jdbc.update("""
                insert into core.core_child (id, guardian_id, display_name, age, interests_json, status, created_at, updated_at)
                values (:id, :guardianId, '시험아이', 8, '["공룡"]'::jsonb, 'ACTIVE', :now, :now)
                """, params);
        return id;
    }

    /** 커밋 뒤 지울 기기 id를 기록한다(저장소가 넣은 행). */
    public UUID trackDevice(UUID deviceId) {
        devices.add(deviceId);
        return deviceId;
    }

    public UUID trackGuardian(UUID guardianId) {
        guardians.add(guardianId);
        return guardianId;
    }

    public UUID trackOutbox(UUID eventId) {
        outboxEvents.add(eventId);
        return eventId;
    }

    public UUID trackInbox(UUID eventId) {
        inboxEvents.add(eventId);
        return eventId;
    }

    /** BR1.1 알파벳의 무작위 8자 코드. */
    public static String randomCode() {
        char[] code = new char[8];
        for (int i = 0; i < code.length; i++) {
            code[i] = CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)];
        }
        return new String(code);
    }

    /** SHA-256 16진수 모양의 무작위 64자(시험 값, 실행마다 생성). */
    public static String randomHash() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 32자 소문자 16진수 traceId. */
    public static String randomTraceId() {
        return randomHash().substring(0, 32);
    }

    // ------------------------------------------------------------------ 정리

    /** 커밋한 행을 외래 키 순서로 지운다(멱등). */
    public void cleanUp() {
        if (!outboxEvents.isEmpty()) {
            jdbc.update("delete from core.core_outbox_event where event_id = any(:ids)",
                    new MapSqlParameterSource("ids", outboxEvents.toArray(UUID[]::new)));
        }
        if (!inboxEvents.isEmpty()) {
            jdbc.update("delete from core.core_inbox_event where event_id = any(:ids)",
                    new MapSqlParameterSource("ids", inboxEvents.toArray(UUID[]::new)));
        }
        if (!guardians.isEmpty()) {
            MapSqlParameterSource params = new MapSqlParameterSource("ids", guardians.toArray(UUID[]::new));
            jdbc.update("delete from core.core_push_dispatch where guardian_id = any(:ids)", params);
            jdbc.update("delete from core.core_push_subscription where guardian_id = any(:ids)", params);
            jdbc.update("delete from core.core_outbox_event where guardian_id = any(:ids)", params);
            jdbc.update("delete from core.core_inbox_event where guardian_id = any(:ids)", params);
            // U4a(V3) 표: 08 배경 · 활동 기록(→ 세션 사본)
            jdbc.update("delete from core.core_activity_context where guardian_id = any(:ids)", params);
            jdbc.update("delete from core.core_activity_record where guardian_id = any(:ids)", params);
            jdbc.update("delete from core.core_activity where guardian_id = any(:ids)", params);
            jdbc.update("delete from core.core_device where guardian_id = any(:ids)", params);
        }
        if (!devices.isEmpty()) {
            MapSqlParameterSource params = new MapSqlParameterSource("ids", devices.toArray(UUID[]::new));
            jdbc.update("""
                    delete from core.core_activity_context
                     where session_id in (select session_id from core.core_activity where device_id = any(:ids))
                    """, params);
            jdbc.update("""
                    delete from core.core_activity_record
                     where session_id in (select session_id from core.core_activity where device_id = any(:ids))
                    """, params);
            jdbc.update("delete from core.core_activity where device_id = any(:ids)", params);
            jdbc.update("delete from core.core_device where id = any(:ids)", params);
        }
        if (!guardians.isEmpty()) {
            MapSqlParameterSource params = new MapSqlParameterSource("ids", guardians.toArray(UUID[]::new));
            // U2(V2) 표: 종료 표현(→ 아이) · 소셜 계정(→ 보호자) · 삭제 요청(논리 참조)
            jdbc.update("""
                    delete from core.core_stop_expression
                     where child_id in (select id from core.core_child where guardian_id = any(:ids))
                    """, params);
            jdbc.update("delete from core.core_social_account where guardian_id = any(:ids)", params);
            jdbc.update("delete from core.core_data_deletion_request where guardian_id = any(:ids)", params);
            jdbc.update("delete from core.core_consent where actor_guardian_id = any(:ids)", params);
            jdbc.update("delete from core.core_child where guardian_id = any(:ids)", params);
            jdbc.update("delete from core.core_guardian where id = any(:ids)", params);
        }
        outboxEvents.clear();
        inboxEvents.clear();
        devices.clear();
        guardians.clear();
    }

    @Override
    public void close() {
        try {
            cleanUp();
        } finally {
            executor.shutdownNow();
        }
    }
}
