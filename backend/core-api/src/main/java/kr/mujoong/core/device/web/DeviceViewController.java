package kr.mujoong.core.device.web;

import java.util.Objects;
import kr.mujoong.core.device.DeviceVerdict;
import kr.mujoong.core.device.DeviceViewAssembler;
import kr.mujoong.core.device.DeviceViewAssembler.DeviceView;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 05 두 줄 + 판정 카드 {@code GET /api/device} (U1 3.5 Step 32 · BR4.3 · BR4.6 · 계약 3절 DeviceView · AC2.1.1 ~ AC2.1.6 · AC2.1.14).
 *
 * <p>체인 C-GRD. 세션 보호자의 활성 기기만 본다(남의 기기를 고르는 매개변수가 없다, BR8.5). 응답에는 등록 줄 · 네트워크 줄 · 판정만 있고 판정
 * 내부값 · 비밀 · 해시가 없다(BR4.6). 계정 삭제가 접수됐으면 403 ACCOUNT_DELETION_IN_PROGRESS.
 */
@RestController
public class DeviceViewController {

    private final GuardianRepository guardians;
    private final DeviceViewAssembler assembler;

    public DeviceViewController(GuardianRepository guardians, DeviceViewAssembler assembler) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    @GetMapping("/api/device")
    ResponseEntity<DeviceViewBody> device(@AuthenticationPrincipal GuardianPrincipal guardian) {
        DeviceView view = assembler.view(GuardianPrincipal.requireActive(guardian, guardians));
        return ApiResponses.json(200, DeviceViewBody.of(view));
    }

    /** DeviceView 응답 모양(device-view.schema.json). 시각은 ISO 8601 UTC 밀리초. */
    public record DeviceViewBody(Registration registration, Network network, Verdict verdict) {

        /** 서비스 값을 응답 모양으로(04 홈도 같은 모양을 쓴다). */
        public static DeviceViewBody of(DeviceView view) {
            Objects.requireNonNull(view, "view");
            DeviceViewAssembler.Registration registration = view.registration();
            DeviceViewAssembler.Network network = view.network();
            return new DeviceViewBody(
                    new Registration(registration.status().name(), registration.codePrefix(),
                            ApiResponses.time(registration.registeredAt())),
                    new Network(network.state(), network.rttMs(), network.recentDisconnects(),
                            ApiResponses.time(network.since()), ApiResponses.time(network.lastSeenAt()),
                            ApiResponses.time(network.lastReportedAt())),
                    new Verdict(view.verdict().canStart(), view.verdict().reasonCode()));
        }

        /** 등록 줄. */
        public record Registration(String status, String codePrefix, String registeredAt) {
        }

        /** 네트워크 줄. */
        public record Network(
                DeviceVerdict.NetworkState state,
                Integer rttMs,
                Integer recentDisconnects,
                String since,
                String lastSeenAt,
                String lastReportedAt) {
        }

        /** 판정 카드. */
        public record Verdict(boolean canStart, DeviceVerdict.ReasonCode reasonCode) {
        }
    }
}
