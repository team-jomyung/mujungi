package kr.mujoong.core.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.device.BootstrapService.BootstrapResult;
import kr.mujoong.core.device.BootstrapService.BootstrapStatus;
import kr.mujoong.core.device.DeviceClaimService.ClaimResult;
import kr.mujoong.core.device.RegistrationService.RegistrationResult;
import kr.mujoong.core.support.BusinessFixtures;
import kr.mujoong.core.support.BusinessFixtures.Family;
import kr.mujoong.core.support.CoreBusinessContext;
import kr.mujoong.testsupport.db.MujoongPostgres;
import kr.mujoong.testsupport.secret.TestSecrets;
import kr.mujoong.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 기기 등록 흐름 (U1 3.5 Step 28 · AC2.2.1 ~ AC2.2.8 · BR1.x · BR2.1 ~ BR2.7 · BR3.1 ~ BR3.5 · J46 · A4). D0 코드 → 05a 등록 → claim →
 * 토큰 확인, 코드 오류 분기, 교체(옛 기기 RELEASED + DeviceReleased REREGISTERED), 연결 중 기기 409, 삭제 접수와 동시 등록, 만료 정리.
 */
@Tag("u1")
@Tag("db")
class DeviceRegistrationFlowIT {

    private static MujoongPostgres postgres;

    private final MutableClock clock = new MutableClock(Instant.parse("2001-07-01T00:00:00Z"));
    private DbTestSupport db;
    private BusinessFixtures fixtures;
    private ApplicationContextRunner runner;

    @BeforeAll
    static void database() {
        postgres = MujoongPostgres.sharedOrAbort();
    }

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        fixtures = new BusinessFixtures(db);
        runner = CoreBusinessContext.runner(postgres, clock, java.net.URI.create("http://127.0.0.1:9"),
                TestSecrets.internalServiceToken());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void codeToRegistrationToClaimToVerifiedConnection() {
        Family family = fixtures.family(clock.now());
        runner.run(context -> {
            BootstrapService bootstrap = context.getBean(BootstrapService.class);
            BootstrapResult issued = bootstrap.bootstrap(null, "198.51.100.10");
            String cookie = issued.newCookieSecret().orElseThrow().value();
            UUID pendingId = track(cookie);

            assertThat(bootstrap.status(cookie).status()).isEqualTo(BootstrapStatus.Status.PENDING);
            assertThat(bootstrap.bootstrap(cookie, "198.51.100.10").code()).isEqualTo(issued.code());
            expect(() -> context.getBean(DeviceClaimService.class).claim(cookie), ErrorCode.NOT_REGISTERED_YET);

            RegistrationResult registered = context.getBean(RegistrationService.class)
                    .register(family.guardianId(), issued.code().display().toLowerCase());
            assertThat(registered.deviceId()).isEqualTo(pendingId);
            assertThat(registered.codePrefix()).isEqualTo(issued.code().prefix());
            assertThat(bootstrap.status(cookie).status()).isEqualTo(BootstrapStatus.Status.REGISTERED);
            expect(() -> bootstrap.bootstrap(cookie, "198.51.100.10"), ErrorCode.ALREADY_REGISTERED);

            ClaimResult first = context.getBean(DeviceClaimService.class).claim(cookie);
            ClaimResult rotated = context.getBean(DeviceClaimService.class).claim(cookie);
            DeviceTokenVerifier verifier = context.getBean(DeviceTokenVerifier.class);
            expect(() -> verifier.verify(first.deviceToken().value()), ErrorCode.INVALID_DEVICE_TOKEN);
            DeviceTokenVerifier.VerifiedDevice verified = verifier.verify(rotated.deviceToken().value());
            assertThat(verified.deviceId()).isEqualTo(pendingId);
            assertThat(verified.guardianId()).isEqualTo(family.guardianId());
            assertThat(verified.childId()).isEqualTo(family.childId());
            expect(() -> context.getBean(DeviceClaimService.class).claim(cookie), ErrorCode.BOOTSTRAP_CLOSED);
            assertThat(verifier.me(rotated.deviceToken().value()).deviceId()).isEqualTo(pendingId);
            assertThat(db.jdbc().queryForObject("select device_token_hash from core.core_device where id = :id",
                    new MapSqlParameterSource("id", pendingId), String.class))
                    .isEqualTo(DeviceSecrets.hash(rotated.deviceToken().value()))
                    .isNotEqualTo(rotated.deviceToken().value());
        });
    }

    @Test
    void codeErrorsAreDistinguishedAndSameGuardianRepeatReplays() {
        Family mine = fixtures.family(clock.now());
        Family theirs = fixtures.family(clock.now());
        runner.run(context -> {
            BootstrapResult issued = context.getBean(BootstrapService.class).bootstrap(null, "198.51.100.11");
            track(issued.newCookieSecret().orElseThrow().value());
            RegistrationService registration = context.getBean(RegistrationService.class);

            RegistrationResult first = registration.register(mine.guardianId(), issued.code().value());
            assertThat(registration.register(mine.guardianId(), issued.code().value())).isEqualTo(first);
            expect(() -> registration.register(theirs.guardianId(), issued.code().value()), ErrorCode.CODE_ALREADY_REGISTERED);
            expect(() -> registration.register(theirs.guardianId(), "7F3K-92Q"), ErrorCode.VALIDATION_FAILED);
            expect(() -> registration.register(theirs.guardianId(), DbTestSupport.randomCode()), ErrorCode.CODE_NOT_FOUND);
            expect(() -> registration.register(theirs.guardianId(), "0OIL0OIL"), ErrorCode.CODE_NOT_FOUND);
        });
    }

    @Test
    void replacingADisconnectedDeviceReleasesItFirstWithReregisteredEvent() {
        Family family = fixtures.family(clock.now());
        UUID old = fixtures.registeredDevice(family.guardianId(), ConnectionState.DISCONNECTED, clock.now(), clock.now());
        runner.run(context -> {
            BootstrapResult issued = context.getBean(BootstrapService.class).bootstrap(null, "198.51.100.12");
            UUID fresh = track(issued.newCookieSecret().orElseThrow().value());

            assertThat(context.getBean(RegistrationService.class).register(family.guardianId(), issued.code().value())
                    .deviceId()).isEqualTo(fresh);

            Map<String, Object> oldRow = db.jdbc().queryForMap(
                    "select registration_status, release_reason, device_token_hash from core.core_device where id = :id",
                    new MapSqlParameterSource("id", old));
            assertThat(oldRow).containsEntry("registration_status", "RELEASED").containsEntry("release_reason", "REREGISTERED");
            assertThat(oldRow.get("device_token_hash")).isNull();
            List<Map<String, Object>> events = db.jdbc().queryForList("""
                    select event_type, guardian_id, aggregate_id, payload_json::text as payload, status
                      from core.core_outbox_event where guardian_id = :g
                    """, new MapSqlParameterSource("g", family.guardianId()));
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).containsEntry("event_type", "DeviceReleased").containsEntry("aggregate_id", old)
                    .containsEntry("status", "PENDING");
            assertThat((String) events.get(0).get("payload")).contains("REREGISTERED")
                    .contains(family.guardianId().toString());
        });
    }

    @Test
    void connectedDeviceBlocksReplacementAndNothingChanges() {
        Family family = fixtures.family(clock.now());
        UUID old = fixtures.registeredDevice(family.guardianId(), ConnectionState.CONNECTED, clock.now(), clock.now());
        runner.run(context -> {
            BootstrapResult issued = context.getBean(BootstrapService.class).bootstrap(null, "198.51.100.13");
            UUID pending = track(issued.newCookieSecret().orElseThrow().value());

            expect(() -> context.getBean(RegistrationService.class).register(family.guardianId(), issued.code().value()),
                    ErrorCode.DEVICE_ALREADY_ACTIVE);

            assertThat(status(old)).isEqualTo("REGISTERED");
            assertThat(status(pending)).isEqualTo("UNREGISTERED");
            assertThat(db.jdbc().queryForObject("select count(*) from core.core_outbox_event where guardian_id = :g",
                    new MapSqlParameterSource("g", family.guardianId()), Integer.class)).isZero();
        });
    }

    @Test
    void deletionAcceptedWhileRegisteringWins() {
        Family family = fixtures.family(clock.now());
        runner.run(context -> {
            BootstrapResult issued = context.getBean(BootstrapService.class).bootstrap(null, "198.51.100.14");
            UUID pending = track(issued.newCookieSecret().orElseThrow().value());
            AtomicInteger deletionPid = new AtomicInteger();
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            Future<Integer> deletion = db.startTx(deletionPid, status -> {
                db.jdbc().queryForList("select id from core.core_guardian where id = :g for update",
                        new MapSqlParameterSource("g", family.guardianId()), UUID.class);
                int updated = db.jdbc().update("update core.core_guardian set status = 'DELETION_REQUESTED' where id = :g",
                        new MapSqlParameterSource("g", family.guardianId()));
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return updated;
            });
            while (deletionPid.get() == 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
            Future<Object> registering = db.start(() -> {
                try {
                    return context.getBean(RegistrationService.class).register(family.guardianId(), issued.code().value());
                } catch (CoreApiException e) {
                    return e.code();
                }
            });
            awaitRegistrationBlockedOnGuardianLock();
            release.countDown();

            assertThat(DbTestSupport.await(deletion)).isEqualTo(1);
            assertThat(DbTestSupport.await(registering)).isEqualTo(ErrorCode.ACCOUNT_DELETION_IN_PROGRESS);
            assertThat(status(pending)).isEqualTo("UNREGISTERED");
        });
    }

    @Test
    void expiredCodeIsNotFoundAndCleanupExpiresThenDeletesIt() {
        Family family = fixtures.family(clock.now());
        runner.run(context -> {
            BootstrapResult issued = context.getBean(BootstrapService.class).bootstrap(null, "198.51.100.15");
            String cookie = issued.newCookieSecret().orElseThrow().value();
            UUID pending = track(cookie);
            clock.advance(Duration.ofHours(24).plusMillis(1));

            expect(() -> context.getBean(RegistrationService.class).register(family.guardianId(), issued.code().value()),
                    ErrorCode.CODE_NOT_FOUND);
            DeviceExpiryCleanupJob cleanup = context.getBean(DeviceExpiryCleanupJob.class);
            assertThat(cleanup.runOnce().expired()).isGreaterThanOrEqualTo(1);
            assertThat(status(pending)).isEqualTo("EXPIRED");
            assertThat(context.getBean(BootstrapService.class).status(cookie).status())
                    .isEqualTo(BootstrapStatus.Status.EXPIRED);

            clock.advance(Duration.ofDays(7).plusMillis(1));
            assertThat(cleanup.runOnce().deleted()).isGreaterThanOrEqualTo(1);
            assertThat(status(pending)).isNull();
        });
    }

    // ------------------------------------------------------------------ 도움

    private UUID track(String cookie) {
        UUID id = db.jdbc().queryForObject("select id from core.core_device where bootstrap_secret_hash = :h",
                new MapSqlParameterSource("h", DeviceSecrets.hash(cookie)), UUID.class);
        return db.trackDevice(id);
    }

    private String status(UUID deviceId) {
        List<String> rows = db.jdbc().queryForList("select registration_status from core.core_device where id = :id",
                new MapSqlParameterSource("id", deviceId), String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 등록 스레드가 보호자 행 FOR SHARE에서 락을 기다릴 때까지(상한 15초). */
    private void awaitRegistrationBlockedOnGuardianLock() {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = db.jdbc().queryForObject("""
                    select count(*) from pg_stat_activity
                     where wait_event_type = 'Lock' and query like '%core_guardian%for share%'
                    """, new MapSqlParameterSource(), Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
        }
        throw new AssertionError("등록이 보호자 행 락을 기다리지 않았다");
    }

    private static void expect(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CoreApiException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
