package kr.mujoong.core.device;

import static kr.mujoong.core.common.db.DbTestSupport.EPOCH;
import static kr.mujoong.core.common.db.DbTestSupport.await;
import static kr.mujoong.core.common.db.DbTestSupport.randomCode;
import static kr.mujoong.core.common.db.DbTestSupport.randomHash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.mujoong.contract.event.EventType;
import kr.mujoong.contract.event.payload.DeviceReleased;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.RegistrationStatus;
import kr.mujoong.core.common.db.ClockParams;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.common.db.SqlErrors;
import kr.mujoong.core.device.DeviceJdbcRepository.ActiveDeviceConflictException;
import kr.mujoong.core.device.DeviceJdbcRepository.ConnectionReport;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;
import kr.mujoong.core.device.DeviceJdbcRepository.PendingDevice;
import kr.mujoong.core.device.DeviceJdbcRepository.TokenOwner;
import kr.mujoong.core.messaging.CoreOutboxRepository;
import kr.mujoong.core.messaging.CoreOutboxRepository.NewOutboxEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link DeviceJdbcRepository} 통합 시험(U1 3.5 Step 17). 실제 init 스크립트 + V1로 만든 PostgreSQL 16에 core_app으로 붙는다.
 *
 * <p>증명: AC2.2.6 · BR2.3(두 계정 동시 등록 승자 1), BR2.5 · J46 · AC2.2.7(교체 순서와 (4) 0행이면 전체 롤백), BR2.4(활성 기기 둘
 * 거부), BR2.1 ②(같은 코드의 등록 행 읽기), BR2.2(보호자 FOR SHARE), BR4.1(두 경로 경합 · 늦은 보고 0행 · COALESCE), BR4.2(등록 아님
 * 무시), BR3.1 · BR3.2 · BR3.3(claim 회전 · 첫 연결로 닫힘), BR3.4(토큰 확인 한 질의), BR1.2(코드 충돌은 트랜잭션을 깨지 않음),
 * BR1.4(만료 경계 · 정리 배치 · 7일 지난 EXPIRED 삭제 · 직전 행 유지), BR2.10 · J58(해제 · 삭제 · 외래 키 RESTRICT), A4 대체 출처.
 */
@Tag("u1")
@Tag("db")
class DeviceJdbcRepositoryIT {

    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration RETENTION = Duration.ofDays(7);

    private DbTestSupport db;
    private DeviceJdbcRepository devices;
    private CoreOutboxRepository outbox;

    @BeforeEach
    void setUp() {
        db = DbTestSupport.coreApp();
        devices = new DeviceJdbcRepository(db.jdbc());
        outbox = new CoreOutboxRepository(db.jdbc());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    // ------------------------------------------------------------------ 발급 · 만료 (BR1.2 · BR1.4)

    @Test
    void pendingCodeCollisionReturnsFalseAndKeepsTheTransactionUsable() {
        db.inRolledBackTx(status -> {
            String code = randomCode();
            PendingDevice first = pending(code, EPOCH);
            assertThat(devices.insertPending(first)).isTrue();

            // 같은 발급 중 코드는 넣지 않고 false다(BR1.2 재추첨). 트랜잭션은 깨지지 않아 같은 트랜잭션에서 새 코드로 다시 넣는다
            assertThat(devices.insertPending(pending(code, EPOCH))).isFalse();
            PendingDevice redraw = pending(randomCode(), EPOCH);
            assertThat(devices.insertPending(redraw)).isTrue();

            DeviceRecord stored = devices.findByBootstrapHash(first.bootstrapSecretHash()).orElseThrow();
            assertThat(stored.id()).isEqualTo(first.id());
            assertThat(stored.registrationStatus()).isEqualTo(RegistrationStatus.UNREGISTERED);
            assertThat(stored.registrationCode()).isEqualTo(code);
            assertThat(stored.codeIssuedAt()).isEqualTo(EPOCH);
            assertThat(stored.codeExpiresAt()).isEqualTo(EPOCH.plus(TTL));
            assertThat(stored.guardianId()).isNull();
            assertThat(stored.tokenIssued()).isFalse();
            assertThat(stored.hasConnectionCopy()).isFalse();
            assertThat(stored.recentDisconnectCount()).isZero();
            assertThat(stored.rowVersion()).isZero();
            assertThat(devices.findByBootstrapHash(randomHash())).isEmpty();
            return null;
        });
    }

    @Test
    void markExpiredIfDueUsesTheExpiryInstantAsTheBoundaryAndFreesTheCode() {
        db.inRolledBackTx(status -> {
            PendingDevice device = pending(randomCode(), EPOCH);
            devices.insertPending(device);
            Instant expiresAt = device.codeExpiresAt();

            assertThat(devices.markExpiredIfDue(device.id(), expiresAt.minusMillis(1))).isZero();
            assertThat(devices.findById(device.id()).orElseThrow().isCodeValidAt(expiresAt.minusMillis(1))).isTrue();

            assertThat(devices.markExpiredIfDue(device.id(), expiresAt)).isEqualTo(1);
            DeviceRecord expired = devices.findById(device.id()).orElseThrow();
            assertThat(expired.registrationStatus()).isEqualTo(RegistrationStatus.EXPIRED);
            assertThat(expired.updatedAt()).isEqualTo(expiresAt);
            assertThat(expired.rowVersion()).isEqualTo(1);
            assertThat(expired.isCodeValidAt(expiresAt)).isFalse();
            // 두 번째는 무동작, 만료 행의 코드는 새 발급에서 다시 쓸 수 있다(부분 유일 색인에서 빠짐)
            assertThat(devices.markExpiredIfDue(device.id(), expiresAt.plusSeconds(1))).isZero();
            assertThat(devices.insertPending(pending(device.registrationCode(), expiresAt))).isTrue();
            return null;
        });
    }

    @Test
    void cleanupExpiresInBatchesAndDeletesOnlyExpiredRowsPastRetention() {
        db.inRolledBackTx(status -> {
            PendingDevice early = pending(randomCode(), EPOCH);
            PendingDevice middle = pending(randomCode(), EPOCH.plusSeconds(60));
            PendingDevice late = pending(randomCode(), EPOCH.plusSeconds(3_600));
            devices.insertPending(early);
            devices.insertPending(middle);
            devices.insertPending(late);
            Instant now = middle.codeExpiresAt();

            // 배치 1이면 가장 먼저 만료된 행만, 다음 배치가 나머지 도래 행을 바꾼다. 아직 유효한 행은 그대로다
            assertThat(devices.expireDueCodes(now, 1)).isEqualTo(1);
            assertThat(status(early.id())).isEqualTo(RegistrationStatus.EXPIRED);
            assertThat(status(middle.id())).isEqualTo(RegistrationStatus.UNREGISTERED);
            assertThat(devices.expireDueCodes(now, 10)).isEqualTo(1);
            assertThat(status(middle.id())).isEqualTo(RegistrationStatus.EXPIRED);
            assertThat(status(late.id())).isEqualTo(RegistrationStatus.UNREGISTERED);

            // 만료 뒤 7일: 직전에는 지우지 않고, 7일이 지난 행만 지운다(가운데 행은 아직 7일 직전)
            Instant sevenDaysAfterEarly = early.codeExpiresAt().plus(RETENTION);
            assertThat(devices.deleteExpired(sevenDaysAfterEarly.minusMillis(1), RETENTION, 10)).isZero();
            assertThat(devices.deleteExpired(sevenDaysAfterEarly, RETENTION, 10)).isEqualTo(1);
            assertThat(devices.findById(early.id())).isEmpty();
            assertThat(devices.findById(middle.id())).isPresent();
            assertThat(devices.findById(late.id())).isPresent();
            return null;
        });
    }

    // ------------------------------------------------------------------ 등록 (BR2.1 ~ BR2.5)

    @Test
    void twoAccountsRegisteringTheSameCodeConcurrentlyHaveExactlyOneWinner() throws Exception {
        UUID winner = db.insertGuardian("ACTIVE");
        UUID loser = db.insertGuardian("ACTIVE");
        String code = randomCode();
        PendingDevice device = pending(code, EPOCH);
        db.inTx(status -> devices.insertPending(device));
        db.trackDevice(device.id());
        Instant now = EPOCH.plusSeconds(30);

        CountDownLatch winnerHoldsCode = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        AtomicInteger winnerPid = new AtomicInteger();
        AtomicInteger loserPid = new AtomicInteger();

        Future<Integer> first = db.startTx(winnerPid, status -> {
            assertThat(devices.lockGuardianStatusForShare(winner)).contains("ACTIVE");
            assertThat(devices.lockPendingByCode(code, now)).isPresent();
            int rows = devices.register(device.id(), winner, now);
            winnerHoldsCode.countDown();
            awaitLatch(releaseWinner);
            return rows;
        });
        awaitLatch(winnerHoldsCode);
        Future<Optional<DeviceRecord>> second = db.startTx(loserPid, status -> {
            assertThat(devices.lockGuardianStatusForShare(loser)).contains("ACTIVE");
            // 이긴 쪽이 잡은 코드 행을 기다린다. 커밋 뒤 다시 평가하면 UNREGISTERED가 아니라 비어 있다
            Optional<DeviceRecord> locked = devices.lockPendingByCode(code, now);
            assertThat(devices.register(device.id(), loser, now)).isZero();
            return locked;
        });
        db.awaitLockWait(loserPid);
        releaseWinner.countDown();

        assertThat(DbTestSupport.await(first)).isEqualTo(1);
        assertThat(DbTestSupport.await(second)).isEmpty();
        DeviceRecord stored = devices.findById(device.id()).orElseThrow();
        assertThat(stored.registrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(stored.guardianId()).isEqualTo(winner);
        assertThat(stored.registeredAt()).isEqualTo(now);
        // 진 쪽은 같은 코드의 등록 행을 읽어 409 CODE_ALREADY_REGISTERED로 답한다(BR2.1 ②)
        assertThat(devices.findBoundByCode(code)).extracting(DeviceRecord::guardianId).containsExactly(winner);
        assertThat(devices.findActiveByGuardian(loser)).isEmpty();
    }

    @Test
    void conditionalRegisterUpdateRacedWithoutLocksStillHasOneWinner() {
        UUID first = db.insertGuardian("ACTIVE");
        UUID second = db.insertGuardian("ACTIVE");
        PendingDevice device = pending(randomCode(), EPOCH);
        db.inTx(status -> devices.insertPending(device));
        db.trackDevice(device.id());
        Instant now = EPOCH.plusSeconds(5);

        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        Future<Integer> a = db.startTx(firstPid, status -> {
            int rows = devices.register(device.id(), first, now);
            updated.countDown();
            awaitLatch(release);
            return rows;
        });
        awaitLatch(updated);
        Future<Integer> b = db.startTx(secondPid, status -> devices.register(device.id(), second, now));
        db.awaitLockWait(secondPid);
        release.countDown();

        assertThat(await(a) + await(b)).as("승자 수").isEqualTo(1);
        assertThat(await(a)).isEqualTo(1);
        assertThat(devices.findById(device.id()).orElseThrow().guardianId()).isEqualTo(first);
    }

    @Test
    void replacementReleasesTheOldDeviceFirstThenRegistersTheNewOneWithDeviceReleasedInTheSameTransaction() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        UUID oldDevice = registeredDevice(guardianId, EPOCH);
        db.inTx(status -> devices.rotateToken(oldDevice, randomHash(), EPOCH.plusSeconds(1)));
        PendingDevice newDevice = pending(randomCode(), EPOCH.plusSeconds(10));
        db.inTx(status -> devices.insertPending(newDevice));
        db.trackDevice(newDevice.id());
        Instant now = EPOCH.plusSeconds(20);
        UUID eventId = db.trackOutbox(UUID.randomUUID());

        db.inTx(status -> {
            // 락 순서: 보호자 → 코드 → 옛 기기(BR2.2 · BR2.5)
            assertThat(devices.lockGuardianStatusForShare(guardianId)).contains("ACTIVE");
            assertThat(devices.lockPendingByCode(newDevice.registrationCode(), now)).isPresent();
            DeviceRecord old = devices.lockActiveByGuardian(guardianId).orElseThrow();
            assertThat(old.id()).isEqualTo(oldDevice);
            assertThat(devices.releaseRegistered(old.id(), DeviceReleased.Reason.REREGISTERED, now)).isEqualTo(1);
            outbox.insert(deviceReleased(eventId, old.id(), guardianId, now), now);
            assertThat(devices.register(newDevice.id(), guardianId, now)).isEqualTo(1);
            return null;
        });

        DeviceRecord released = devices.findById(oldDevice).orElseThrow();
        assertThat(released.registrationStatus()).isEqualTo(RegistrationStatus.RELEASED);
        assertThat(released.releaseReason()).isEqualTo("REREGISTERED");
        assertThat(released.releasedAt()).isEqualTo(now);
        assertThat(released.tokenIssued()).as("해제하면 토큰 해시를 비운다(BR3.3)").isFalse();
        assertThat(released.guardianId()).as("RELEASED는 보호자 값을 그대로 둔다(BR2.10)").isEqualTo(guardianId);
        assertThat(devices.findActiveByGuardian(guardianId).orElseThrow().id()).isEqualTo(newDevice.id());
        assertThat(outbox.findById(eventId).orElseThrow().guardianId()).isEqualTo(guardianId);
    }

    @Test
    void aZeroRowRegistrationAtStepFourRollsBackTheOldDeviceReleaseAndTheEvent() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        UUID oldDevice = registeredDevice(guardianId, EPOCH);
        String oldToken = randomHash();
        db.inTx(status -> devices.rotateToken(oldDevice, oldToken, EPOCH.plusSeconds(1)));
        PendingDevice newDevice = pending(randomCode(), EPOCH.plusSeconds(10));
        db.inTx(status -> devices.insertPending(newDevice));
        db.trackDevice(newDevice.id());
        UUID eventId = db.trackOutbox(UUID.randomUUID());
        Instant now = EPOCH.plusSeconds(20);
        // (4)에서 0행을 주입한다: 코드가 그 사이 만료됐다고 보고 만료 뒤 시각으로 등록한다
        Instant afterExpiry = newDevice.codeExpiresAt();

        assertThatThrownBy(() -> db.inTx(status -> {
            assertThat(devices.releaseRegistered(oldDevice, DeviceReleased.Reason.REREGISTERED, now)).isEqualTo(1);
            outbox.insert(deviceReleased(eventId, oldDevice, guardianId, now), now);
            if (devices.register(newDevice.id(), guardianId, afterExpiry) != 1) {
                throw new IllegalStateException("교체 (4)가 0행이다");
            }
            return null;
        })).isInstanceOf(IllegalStateException.class);

        DeviceRecord old = devices.findById(oldDevice).orElseThrow();
        assertThat(old.registrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(old.tokenIssued()).isTrue();
        assertThat(devices.findTokenOwner(oldToken)).isPresent();
        assertThat(status(newDevice.id())).isEqualTo(RegistrationStatus.UNREGISTERED);
        assertThat(outbox.findById(eventId)).isEmpty();
    }

    @Test
    void registeringASecondActiveDeviceForOneGuardianIsAnActiveDeviceConflict() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            PendingDevice first = pending(randomCode(), EPOCH);
            PendingDevice second = pending(randomCode(), EPOCH);
            devices.insertPending(first);
            devices.insertPending(second);
            assertThat(devices.register(first.id(), guardianId, EPOCH.plusSeconds(1))).isEqualTo(1);

            // 옛 기기를 풀지 않고 새 기기를 등록하면 부분 유일 색인이 막는다(BR2.4 · 23505 → 409 DEVICE_ALREADY_ACTIVE)
            assertThatThrownBy(() -> devices.register(second.id(), guardianId, EPOCH.plusSeconds(2)))
                    .isInstanceOf(ActiveDeviceConflictException.class)
                    .hasMessageContaining(DeviceJdbcRepository.ACTIVE_GUARDIAN_INDEX)
                    .satisfies(error -> assertThat(SqlErrors.isUniqueViolation(error)).isTrue());
            return null;
        });
    }

    @Test
    void registeringForAnUnknownGuardianFailsWithTheForeignKeyNotAnActiveDeviceConflict() {
        db.inRolledBackTx(status -> {
            PendingDevice device = pending(randomCode(), EPOCH);
            devices.insertPending(device);
            assertThatThrownBy(() -> devices.register(device.id(), UUID.randomUUID(), EPOCH.plusSeconds(1)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isNotInstanceOf(ActiveDeviceConflictException.class)
                    .satisfies(error -> assertThat(SqlErrors.isForeignKeyViolation(error)).isTrue());
            return null;
        });
    }

    @Test
    void boundRowsOfACodeListRegisteredFirstAndSkipPendingOrExpiredRows() {
        db.inRolledBackTx(status -> {
            String code = randomCode();
            UUID previousOwner = db.insertGuardian("ACTIVE");
            UUID currentOwner = db.insertGuardian("ACTIVE");
            // 같은 코드가 만료 뒤 다시 발급돼 RELEASED · REGISTERED 행이 함께 있는 경우
            UUID releasedId = registeredDeviceWithCode(previousOwner, code, EPOCH);
            devices.releaseRegistered(releasedId, DeviceReleased.Reason.REREGISTERED, EPOCH.plusSeconds(10));
            UUID registeredId = registeredDeviceWithCode(currentOwner, code, EPOCH.plus(TTL).plusSeconds(10));
            PendingDevice expired = pending(code, EPOCH.plus(Duration.ofDays(3)));
            devices.insertPending(expired);
            devices.markExpiredIfDue(expired.id(), expired.codeExpiresAt());

            List<DeviceRecord> bound = devices.findBoundByCode(code);
            assertThat(bound).extracting(DeviceRecord::id).containsExactly(registeredId, releasedId);
            assertThat(bound.get(0).guardianId()).isEqualTo(currentOwner);
            assertThat(devices.findBoundByCode(randomCode())).isEmpty();
            return null;
        });
    }

    @Test
    void guardianShareLockMakesADeletionReceiptWaitForTheRegistration() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        CountDownLatch shareHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger registrationPid = new AtomicInteger();
        AtomicInteger deletionPid = new AtomicInteger();

        Future<Optional<String>> registration = db.startTx(registrationPid, status -> {
            Optional<String> guardianStatus = devices.lockGuardianStatusForShare(guardianId);
            shareHeld.countDown();
            awaitLatch(release);
            return guardianStatus;
        });
        awaitLatch(shareHeld);
        Future<Integer> deletion = db.startTx(deletionPid, status -> db.jdbc().update(
                "update core.core_guardian set status = 'DELETION_REQUESTED', updated_at = :now where id = :id",
                ClockParams.at(EPOCH).addValue("id", guardianId)));
        db.awaitLockWait(deletionPid);
        assertThat(deletion.isDone()).as("삭제 접수는 등록이 끝날 때까지 기다린다(BR2.2)").isFalse();
        release.countDown();

        assertThat(await(registration)).contains("ACTIVE");
        assertThat(await(deletion)).isEqualTo(1);
        Optional<String> afterDeletion = db.inTx(status -> devices.lockGuardianStatusForShare(guardianId));
        Optional<String> unknown = db.inTx(status -> devices.lockGuardianStatusForShare(UUID.randomUUID()));
        assertThat(afterDeletion).contains("DELETION_REQUESTED");
        assertThat(unknown).isEmpty();
    }

    // ------------------------------------------------------------------ 토큰 (BR3.1 ~ BR3.5)

    @Test
    void claimRotatesTheTokenUntilTheFirstConnectionClosesIt() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID childId = db.insertChild(guardianId);
            PendingDevice pending = pending(randomCode(), EPOCH);
            devices.insertPending(pending);
            assertThat(devices.rotateToken(pending.id(), randomHash(), EPOCH)).as("등록 전에는 claim 불가").isZero();
            devices.register(pending.id(), guardianId, EPOCH.plusSeconds(1));

            String first = randomHash();
            String second = randomHash();
            assertThat(devices.rotateToken(pending.id(), first, EPOCH.plusSeconds(2))).isEqualTo(1);
            TokenOwner owner = devices.findTokenOwner(first).orElseThrow();
            assertThat(owner.deviceId()).isEqualTo(pending.id());
            assertThat(owner.guardianId()).isEqualTo(guardianId);
            assertThat(owner.childId()).isEqualTo(childId);
            assertThat(owner.registeredAt()).isEqualTo(EPOCH.plusSeconds(1));
            assertThat(owner.isBeforeFirstConnection()).isTrue();

            // 첫 연결 전 재호출은 회전: 앞 토큰은 무효(BR3.3)
            assertThat(devices.rotateToken(pending.id(), second, EPOCH.plusSeconds(3))).isEqualTo(1);
            assertThat(devices.findTokenOwner(first)).isEmpty();
            DeviceRecord locked = devices.lockByBootstrapHash(pending.bootstrapSecretHash()).orElseThrow();
            assertThat(locked.tokenIssued()).isTrue();
            assertThat(locked.tokenIssuedAt()).isEqualTo(EPOCH.plusSeconds(3));

            // 첫 WSS 인증이 claim을 닫는다(BR3.2). 두 번째 표시는 무동작이고 첫 시각을 지킨다
            assertThat(devices.markFirstConnected(pending.id(), EPOCH.plusSeconds(4))).isEqualTo(1);
            assertThat(devices.markFirstConnected(pending.id(), EPOCH.plusSeconds(5))).isZero();
            assertThat(devices.rotateToken(pending.id(), randomHash(), EPOCH.plusSeconds(6))).isZero();
            TokenOwner connected = devices.findTokenOwner(second).orElseThrow();
            assertThat(connected.firstConnectedAt()).isEqualTo(EPOCH.plusSeconds(4));
            assertThat(connected.isBeforeFirstConnection()).isFalse();

            // 해제되면 토큰은 맞지 않는다(BR3.4)
            devices.releaseRegistered(pending.id(), DeviceReleased.Reason.ACCOUNT_DELETED, EPOCH.plusSeconds(7));
            assertThat(devices.findTokenOwner(second)).isEmpty();
            return null;
        });
    }

    // ------------------------------------------------------------------ 연결 상태 사본 (BR4.1 · BR4.2)

    @Test
    void connectionReportsApplyOnlyStrictlyNewerReportsAndKeepMissingFields() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID deviceId = registeredDevice(guardianId, EPOCH);
            Instant t1 = EPOCH.plusSeconds(100);
            Instant since = EPOCH.plusSeconds(90);
            ConnectionReport full = new ConnectionReport(ConnectionState.CONNECTED, t1, 42, 2, since, t1.minusSeconds(1));

            assertThat(devices.applyConnectionReport(deviceId, full, t1)).isEqualTo(1);
            assertThat(devices.applyConnectionReport(deviceId, full, t1)).as("같은 시각 보고는 버린다").isZero();
            ConnectionReport older = new ConnectionReport(ConnectionState.OFFLINE, t1.minusMillis(1), 1, 0, null, null);
            assertThat(devices.applyConnectionReport(deviceId, older, t1)).as("늦게 온 옛 보고는 버린다").isZero();

            Instant t2 = t1.plusSeconds(10);
            ConnectionReport sparse = new ConnectionReport(ConnectionState.DISCONNECTED, t2, null, null, null, null);
            assertThat(devices.applyConnectionReport(deviceId, sparse, t2.plusSeconds(1))).isEqualTo(1);

            DeviceRecord copy = devices.findById(deviceId).orElseThrow();
            assertThat(copy.reportedConnectionState()).isEqualTo(ConnectionState.DISCONNECTED);
            assertThat(copy.reportedAt()).isEqualTo(t2);
            assertThat(copy.rttMs()).as("없으면 기존 값(COALESCE)").isEqualTo(42);
            assertThat(copy.recentDisconnectCount()).isEqualTo(2);
            assertThat(copy.lastSeenAt()).isEqualTo(t1.minusSeconds(1));
            assertThat(copy.connectionSince()).isNull();
            assertThat(copy.updatedAt()).isEqualTo(t2.plusSeconds(1));
            assertThat(copy.hasConnectionCopy()).isTrue();
            return null;
        });
    }

    @Test
    void racingReportPathsNeverMoveReportedAtBackwards() {
        UUID guardianId = db.insertGuardian("ACTIVE");
        UUID deviceId = registeredDevice(guardianId, EPOCH);
        Instant newer = EPOCH.plusSeconds(200);
        Instant older = EPOCH.plusSeconds(150);
        CountDownLatch newerWritten = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger httpPid = new AtomicInteger();
        AtomicInteger eventPid = new AtomicInteger();

        // HTTP 보고 경로가 새 값을 쓰고 커밋 전에 멈춘 사이 이벤트 경로가 옛 값을 쓰려 한다
        Future<Integer> http = db.startTx(httpPid, status -> {
            int rows = devices.applyConnectionReport(deviceId,
                    new ConnectionReport(ConnectionState.CONNECTED, newer, 10, 0, newer, newer), newer);
            newerWritten.countDown();
            awaitLatch(release);
            return rows;
        });
        awaitLatch(newerWritten);
        Future<Integer> event = db.startTx(eventPid, status -> devices.applyConnectionReport(deviceId,
                new ConnectionReport(ConnectionState.DISCONNECTED, older, null, 1, older, null), newer));
        db.awaitLockWait(eventPid);
        release.countDown();

        assertThat(await(http)).isEqualTo(1);
        assertThat(await(event)).isZero();
        DeviceRecord copy = devices.findById(deviceId).orElseThrow();
        assertThat(copy.reportedAt()).isEqualTo(newer);
        assertThat(copy.reportedConnectionState()).isEqualTo(ConnectionState.CONNECTED);
    }

    @Test
    void reportsForUnregisteredReleasedOrUnknownDevicesAreIgnored() {
        db.inRolledBackTx(status -> {
            ConnectionReport report = new ConnectionReport(ConnectionState.CONNECTED, EPOCH.plusSeconds(5), 1, 0, null, null);
            PendingDevice pending = pending(randomCode(), EPOCH);
            devices.insertPending(pending);
            assertThat(devices.applyConnectionReport(pending.id(), report, EPOCH)).isZero();

            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID released = registeredDevice(guardianId, EPOCH);
            devices.releaseRegistered(released, DeviceReleased.Reason.REREGISTERED, EPOCH.plusSeconds(1));
            assertThat(devices.applyConnectionReport(released, report, EPOCH)).as("늦은 보고가 해제 기기를 되살리지 않는다")
                    .isZero();
            assertThat(devices.findById(released).orElseThrow().reportedConnectionState()).isNull();
            assertThat(devices.applyConnectionReport(UUID.randomUUID(), report, EPOCH)).isZero();
            return null;
        });
    }

    // ------------------------------------------------------------------ 계정 삭제 참가자 (BR2.10 · J58)

    @Test
    void accountDeletionReleasesThenDeletesEveryRowOfTheGuardianOnly() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID otherGuardian = db.insertGuardian("ACTIVE");
            UUID oldDevice = registeredDevice(guardianId, EPOCH);
            devices.releaseRegistered(oldDevice, DeviceReleased.Reason.REREGISTERED, EPOCH.plusSeconds(1));
            UUID activeDevice = registeredDevice(guardianId, EPOCH.plusSeconds(2));
            UUID othersDevice = registeredDevice(otherGuardian, EPOCH);

            assertThat(devices.findGuardianId(activeDevice)).contains(guardianId);
            DeviceRecord active = devices.lockActiveByGuardian(guardianId).orElseThrow();
            assertThat(devices.releaseRegistered(active.id(), DeviceReleased.Reason.ACCOUNT_DELETED, EPOCH.plusSeconds(3)))
                    .isEqualTo(1);
            assertThat(devices.findById(activeDevice).orElseThrow().releaseReason()).isEqualTo("ACCOUNT_DELETED");
            assertThat(devices.releaseRegistered(active.id(), DeviceReleased.Reason.ACCOUNT_DELETED, EPOCH.plusSeconds(4)))
                    .as("두 번째 해제는 무동작(멱등)").isZero();

            assertThat(devices.deleteAllByGuardian(guardianId)).isEqualTo(2);
            assertThat(devices.deleteAllByGuardian(guardianId)).isZero();
            assertThat(devices.findById(othersDevice)).isPresent();
            assertThat(devices.findGuardianId(activeDevice)).isEmpty();
            return null;
        });
    }

    @Test
    void deletingDevicesStillReferencedByActivitiesFailsWithForeignKeyRestrict() {
        db.inRolledBackTx(status -> {
            UUID guardianId = db.insertGuardian("ACTIVE");
            UUID childId = db.insertChild(guardianId);
            UUID deviceId = registeredDevice(guardianId, EPOCH);
            db.jdbc().update("""
                    insert into core.core_activity (session_id, guardian_id, child_id, device_id, activity_type, status,
                        status_version, start_idempotency_key, request_hash, requested_at, created_at, updated_at)
                    values (:sessionId, :guardianId, :childId, :deviceId, 'FREE_TALK', 'ENDED', 3, :key, :hash, :now, :now, :now)
                    """, ClockParams.at(EPOCH).addValue("sessionId", UUID.randomUUID()).addValue("guardianId", guardianId)
                    .addValue("childId", childId).addValue("deviceId", deviceId)
                    .addValue("key", UUID.randomUUID().toString()).addValue("hash", randomHash()));

            // 활동(U4 참가자)이 먼저 지워져야 한다(BR2.10 순서, 23503)
            assertThatThrownBy(() -> devices.deleteAllByGuardian(guardianId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(SqlErrors.isForeignKeyViolation(error)).isTrue());
            return null;
        });
    }

    // ------------------------------------------------------------------ 값 형식 검증

    @Test
    void valueTypesRejectInvalidInputAndNeverPrintCodesOrHashes() {
        assertThatThrownBy(() -> new ConnectionReport(ConnectionState.UNRECOGNIZED, EPOCH, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionReport(null, EPOCH, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionReport(ConnectionState.CONNECTED, EPOCH, -1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionReport(ConnectionState.CONNECTED, EPOCH, null, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingDevice(UUID.randomUUID(), "7F3K92QD", randomHash(), EPOCH, EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingDevice(UUID.randomUUID(), " ", randomHash(), EPOCH, EPOCH.plus(TTL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> devices.releaseRegistered(UUID.randomUUID(), DeviceReleased.Reason.UNRECOGNIZED, EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> devices.expireDueCodes(EPOCH, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> devices.deleteExpired(EPOCH, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> devices.findByBootstrapHash("")).isInstanceOf(IllegalArgumentException.class);

        String code = "7F3K92QD";
        String hash = randomHash();
        PendingDevice pending = new PendingDevice(UUID.randomUUID(), code, hash, EPOCH, EPOCH.plus(TTL));
        assertThat(pending.toString()).doesNotContain(code).doesNotContain(hash);
        DeviceRecord record = new DeviceRecord(UUID.randomUUID(), null, code, EPOCH, EPOCH.plus(TTL), false, null, null,
                RegistrationStatus.UNREGISTERED, null, null, null, null, null, null, null, null, 0, EPOCH, EPOCH, 0);
        assertThat(record.codePrefix()).isEqualTo("7F3K");
        assertThat(record.toString()).doesNotContain(code).doesNotContain("7F3K");
        assertThat(record.isCodeValidAt(EPOCH.plus(TTL).minusMillis(1))).isTrue();
        assertThat(record.isCodeValidAt(EPOCH.plus(TTL))).isFalse();
        assertThat(record.hasConnectionCopy()).isFalse();
    }

    // ------------------------------------------------------------------ 도우미

    private static PendingDevice pending(String code, Instant issuedAt) {
        return new PendingDevice(UUID.randomUUID(), code, randomHash(), issuedAt, issuedAt.plus(TTL));
    }

    /** 커밋 여부는 호출자의 트랜잭션을 따른다. 트랜잭션 밖이면 바로 커밋되고 정리 대상이 된다. */
    private UUID registeredDevice(UUID guardianId, Instant at) {
        return registeredDeviceWithCode(guardianId, randomCode(), at);
    }

    private UUID registeredDeviceWithCode(UUID guardianId, String code, Instant at) {
        PendingDevice device = pending(code, at);
        assertThat(devices.insertPending(device)).isTrue();
        assertThat(devices.register(device.id(), guardianId, at.plusMillis(1))).isEqualTo(1);
        db.trackDevice(device.id());
        return device.id();
    }

    private RegistrationStatus status(UUID deviceId) {
        return devices.findById(deviceId).orElseThrow().registrationStatus();
    }

    private static NewOutboxEvent deviceReleased(UUID eventId, UUID deviceId, UUID guardianId, Instant now) {
        String payload = "{\"deviceId\":\"" + deviceId + "\",\"guardianId\":\"" + guardianId + "\",\"reason\":\"REREGISTERED\"}";
        return new NewOutboxEvent(eventId, EventType.DEVICE_RELEASED.wire(), 1, now, "Device", deviceId, guardianId, payload,
                randomHash(), DbTestSupport.randomTraceId());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("15초 안에 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("기다리는 중 인터럽트", e);
        }
    }
}
