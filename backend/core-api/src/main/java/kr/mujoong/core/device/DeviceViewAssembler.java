package kr.mujoong.core.device;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.time.MujoongClock;
import kr.mujoong.core.activity.StaleEvaluator;
import kr.mujoong.core.device.DeviceJdbcRepository.DeviceRecord;
import org.springframework.stereotype.Component;

/**
 * 04 · 05의 기기 구역 값과 시작 가능 판정 (U1 3.5 Step 19 · BR4.3 · BR4.4 · BR4.6 · 계약 3절 DeviceView · 계약 8절 DeviceReadiness).
 *
 * <p>DeviceView = {registration, network, verdict}. 응답에는 판정 내부값 · 비밀 · 해시 · 전사가 없다(BR4.6 · 계약 2절). 이 읽기는 부수 효과가
 * 없다(예약을 만들지 않는다). 시작 검사는 같은 계산을 다시 하며 화면 값을 믿지 않는다(W3). '확인 불가' 첫 판정은 {@link StaleEvaluator}가
 * 센다(RD-6).
 */
@Component
public class DeviceViewAssembler {

    private final DeviceJdbcRepository devices;
    private final StaleEvaluator staleEvaluator;
    private final MujoongClock clock;

    public DeviceViewAssembler(DeviceJdbcRepository devices, StaleEvaluator staleEvaluator, MujoongClock clock) {
        this.devices = Objects.requireNonNull(devices, "devices");
        this.staleEvaluator = Objects.requireNonNull(staleEvaluator, "staleEvaluator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 보호자의 DeviceView(GET /api/device · /api/home의 device). */
    public DeviceView view(UUID guardianId) {
        Instant now = clock.now();
        DeviceRecord active = devices.findActiveByGuardian(Objects.requireNonNull(guardianId, "guardianId")).orElse(null);
        DeviceVerdict verdict = verdictOf(active, now);
        return DeviceView.of(active, verdict);
    }

    /** 시작 가능 판정(DeviceReadiness 포트, BR4.4 · BR6.1 ③). */
    public Readiness readiness(UUID guardianId) {
        Instant now = clock.now();
        DeviceRecord active = devices.findActiveByGuardian(Objects.requireNonNull(guardianId, "guardianId")).orElse(null);
        DeviceVerdict verdict = verdictOf(active, now);
        return new Readiness(active == null ? null : active.id(), verdict.canStart(), verdict.reasonCode());
    }

    /** 사본 판정(교체 등록의 재판정도 같은 계산, BR2.4). 확인 불가 첫 판정을 센다. */
    DeviceVerdict verdictOf(DeviceRecord active, Instant now) {
        Duration stale = staleEvaluator.threshold();
        DeviceVerdict verdict = DeviceVerdict.evaluate(active, now, stale);
        if (active != null) {
            staleEvaluator.observeDevice(active.id(), verdict, active.reportedAt(), now);
        }
        return verdict;
    }

    /** 시작 가능 판정(계약 8절 DeviceReadiness.get). */
    public record Readiness(UUID deviceId, boolean canStart, DeviceVerdict.ReasonCode reasonCode) {
    }

    /** 계약 3절 DeviceView(device-view.schema.json). */
    public record DeviceView(Registration registration, Network network, Verdict verdict) {

        static DeviceView of(DeviceRecord active, DeviceVerdict verdict) {
            if (active == null) {
                return new DeviceView(
                        new Registration(RegistrationLine.NONE, null, null),
                        new Network(verdict.network(), null, null, null, null, null),
                        new Verdict(verdict.canStart(), verdict.reasonCode()));
            }
            return new DeviceView(
                    new Registration(RegistrationLine.REGISTERED, active.codePrefix(), active.registeredAt()),
                    new Network(verdict.network(), active.rttMs(),
                            active.hasConnectionCopy() ? active.recentDisconnectCount() : null,
                            active.connectionSince(), active.lastSeenAt(), active.reportedAt()),
                    new Verdict(verdict.canStart(), verdict.reasonCode()));
        }

        /** 시작 가능한가(탭 잠금 lock.locked = !canStart, J119). */
        public boolean canStart() {
            return verdict.canStart();
        }
    }

    /** 등록 줄 상태. */
    public enum RegistrationLine {
        NONE,
        REGISTERED
    }

    /** 등록 줄(코드 앞 4자리 · 등록 시각). */
    public record Registration(RegistrationLine status, String codePrefix, Instant registeredAt) {
    }

    /** 네트워크 줄(lastReportedAt = 사본의 마지막 보고 시각). */
    public record Network(
            DeviceVerdict.NetworkState state,
            Integer rttMs,
            Integer recentDisconnects,
            Instant since,
            Instant lastSeenAt,
            Instant lastReportedAt) {
    }

    /** 판정 카드. */
    public record Verdict(boolean canStart, DeviceVerdict.ReasonCode reasonCode) {
    }
}
