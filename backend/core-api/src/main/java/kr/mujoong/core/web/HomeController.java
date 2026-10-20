package kr.mujoong.core.web;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.activity.CurrentSessionService;
import kr.mujoong.core.activity.web.SessionSummaryBody;
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
 * <p>{@code child} = 세션 보호자의 아이(없으면 null), {@code device} = DeviceView, {@code currentSession} = 진행 중 세션 요약 또는 null(U4a
 * 지금 활동 구역), {@code lock} = {@code {locked: !canStart, reasonCode}}(탭 잠금, BR4.5). 계정 삭제가 접수됐으면 403. 아이 정보는 표시 이름 · 나이 ·
 * 관심사만이다.
 */
@RestController
public class HomeController {

    private final GuardianRepository guardians;
    private final ChildRepository children;
    private final DeviceViewAssembler deviceView;
    private final CurrentSessionService currentSessions;
    private final JsonCodec json;
    private final kr.mujoong.contract.time.MujoongClock clock;

    public HomeController(
            GuardianRepository guardians,
            ChildRepository children,
            DeviceViewAssembler deviceView,
            CurrentSessionService currentSessions,
            JsonCodec json, kr.mujoong.contract.time.MujoongClock clock) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.children = Objects.requireNonNull(children, "children");
        this.deviceView = Objects.requireNonNull(deviceView, "deviceView");
        this.currentSessions = Objects.requireNonNull(currentSessions, "currentSessions");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = clock;
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
        SessionSummaryBody current = currentSessions.current(guardianId).map(SessionSummaryBody::of).orElse(null);
        return ApiResponses.json(200, new HomeBody(child, DeviceViewBody.of(device), current, lock));
    }

    private ChildBody child(Child child) {
        String[] interests = child.getInterestsJson() == null ? new String[0] : json.read(child.getInterestsJson(), String[].class);
        return new ChildBody(child.getId(), child.getDisplayName(), child.getBirthDate() == null ? child.getAge() : kr.mujoong.core.child.BirthDateRules.ageOn(child.getBirthDate(), clock.now().atZone(kr.mujoong.core.child.BirthDateRules.ZONE).toLocalDate()), List.of(interests));
    }

    /** 200 본문. currentSession은 진행 중(REQUESTED ~ CLOSING) 세션 요약 또는 null(U4a · AC1.4.13). */
    public record HomeBody(ChildBody child, DeviceViewBody device, SessionSummaryBody currentSession, LockBody lock) {
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
