package kr.mujoong.core.device;

import java.time.Instant;
import java.util.UUID;
import kr.mujoong.contract.status.ConnectionState;
import kr.mujoong.contract.status.RegistrationStatus;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;

/** 단위 시험용 기기 행(시험 소스 전용). */
final class DeviceRecords {

    static final Instant ISSUED = Instant.parse("2001-03-01T00:00:00Z");

    private DeviceRecords() {
    }

    /** 보고 사본이 있는 REGISTERED 행. state가 null이면 사본 없음. */
    static DeviceRecord registered(UUID guardianId, ConnectionState state, Instant reportedAt) {
        return row(UUID.randomUUID(), guardianId, "7F3K92QD", RegistrationStatus.REGISTERED, state, reportedAt);
    }

    static DeviceRecord row(
            UUID id, UUID guardianId, String code, RegistrationStatus status, ConnectionState state, Instant reportedAt) {
        return new DeviceRecord(id, guardianId, code, ISSUED, ISSUED.plusSeconds(86_400), true, ISSUED, null, status,
                status == RegistrationStatus.REGISTERED ? ISSUED.plusSeconds(60) : null, null, null, state, reportedAt,
                reportedAt, reportedAt, state == null ? null : 120, 0, ISSUED, ISSUED, 1L);
    }

    static DeviceRecord pending(String code, Instant expiresAt) {
        return new DeviceRecord(UUID.randomUUID(), null, code, ISSUED, expiresAt, false, null, null,
                RegistrationStatus.UNREGISTERED, null, null, null, null, null, null, null, null, 0, ISSUED, ISSUED, 1L);
    }
}
