package kr.mujoong.core.web;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.device.DeviceVerdict;
import kr.mujoong.core.device.DeviceViewAssembler;
import kr.mujoong.core.device.DeviceViewAssembler.DeviceView;
import kr.mujoong.core.device.web.DeviceViewController.DeviceViewBody;
import kr.mujoong.core.identity.Child;
import kr.mujoong.core.identity.ChildRepository;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.security.GuardianPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 04 홈 {@code GET /api/home} (U1 3.5 Step 32 · J119 · BR4.3 ~ BR4.6 · AC1.4.2 ~ AC1.4.5 · AC1.4.9 · AC1.4.11 · AC1.4.15 · home-response.schema.json).
 *
 * <p>U1 모양: {@code child} = 세션 보호자의 아이(없으면 null), {@code device} = DeviceView, {@code currentSession} = null(지금 활동 구역은
 * U4), {@code lock} = {@code {locked: !canStart, reasonCode}}(탭 잠금, BR4.5). 계정 삭제가 접수됐으면 403. 아이 정보는 표시 이름 · 나이 ·
 * 관심사만이다.
 */
@RestController
public class HomeController {

    private final GuardianRepository guardians;
    private final ChildRepository children;
    private final DeviceViewAssembler deviceView;
    private final JsonCodec json;

    public HomeController(
            GuardianRepository guardians, ChildRepository children, DeviceViewAssembler deviceView, JsonCodec json) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.children = Objects.requireNonNull(children, "children");
        this.deviceView = Objects.requireNonNull(deviceView, "deviceView");
        this.json = Objects.requireNonNull(json, "json");
    }

    @GetMapping("/api/home")
    ResponseEntity<HomeBody> home(@AuthenticationPrincipal GuardianPrincipal guardian) {
        UUID guardianId = GuardianPrincipal.requireActive(guardian, guardians);
        ChildBody child = children.findByGuardianId(guardianId)
                .filter(found -> Child.STATUS_ACTIVE.equals(found.getStatus()))
                .map(this::child)
                .orElse(null);
        DeviceView device = deviceView.view(guardianId);
        LockBody lock = new LockBody(!device.canStart(), device.verdict().reasonCode());
        return ApiResponses.json(200, new HomeBody(child, DeviceViewBody.of(device), null, lock));
    }

    private ChildBody child(Child child) {
        String[] interests = child.getInterestsJson() == null ? new String[0] : json.read(child.getInterestsJson(), String[].class);
        return new ChildBody(child.getId(), child.getDisplayName(), child.getAge(), List.of(interests));
    }

    /** 200 본문. currentSession은 U1에서 늘 null이다(U4가 채운다). */
    public record HomeBody(ChildBody child, DeviceViewBody device, Object currentSession, LockBody lock) {
    }

    /** 아이(표시 이름 · 나이 · 관심사). 문자열 표현에 값을 내지 않는다. */
    public record ChildBody(UUID childId, String displayName, Integer age, List<String> interests) {

        @Override
        public String toString() {
            return "ChildBody[childId=" + childId + "]";
        }
    }

    /** 탭 잠금(J119). */
    public record LockBody(boolean locked, DeviceVerdict.ReasonCode reasonCode) {
    }
}
