package kr.mujoong.core.admin.web;

import java.util.Objects;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.admin.AdminAccess;
import kr.mujoong.core.admin.AdminCostService;
import kr.mujoong.core.admin.AdminFilter;
import kr.mujoong.core.admin.AdminQueryService;
import kr.mujoong.core.admin.AdminViews;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 조회 API (JIRA-280 · contracts/parent-api.yaml {@code /admin/**}). 모두 읽기 전용 GET이다.
 *
 * <p>보호자 세션 체인 뒤에서 닿고(세션 없음 → 401), 연산마다 {@link AdminAccess}가 활성 관리자인지 확인한다(아니면 403 ADMIN_FORBIDDEN).
 * 화면 가드는 이 검사를 대신하지 않는다. 검색어 등 질의 값은 로그에 싣지 않는다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminAccess access;
    private final AdminQueryService queries;
    private final AdminCostService costs;

    public AdminController(AdminAccess access, AdminQueryService queries, AdminCostService costs) {
        this.access = Objects.requireNonNull(access, "access");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.costs = Objects.requireNonNull(costs, "costs");
    }

    @GetMapping("/me")
    ResponseEntity<AdminViews.Identity> me(@AuthenticationPrincipal GuardianPrincipal guardian) {
        return ApiResponses.json(200, queries.me(access.requireAdmin(guardian)));
    }

    @GetMapping("/overview")
    ResponseEntity<AdminViews.Overview> overview(@AuthenticationPrincipal GuardianPrincipal guardian,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "timezone", required = false) String timezone,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "guardianId", required = false) String guardianId,
            @RequestParam(name = "childId", required = false) String childId,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status) {
        access.requireAdmin(guardian);
        return ApiResponses.json(200,
                queries.overview(AdminFilter.parse(from, to, timezone, type, query, guardianId, childId, deviceId, status)));
    }

    @GetMapping("/activities")
    ResponseEntity<AdminViews.Page<AdminViews.ActivitySummary>> activities(
            @AuthenticationPrincipal GuardianPrincipal guardian,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "timezone", required = false) String timezone,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "guardianId", required = false) String guardianId,
            @RequestParam(name = "childId", required = false) String childId,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "pageSize", required = false) String pageSize) {
        access.requireAdmin(guardian);
        AdminFilter filter = AdminFilter.parse(from, to, timezone, type, query, guardianId, childId, deviceId, status);
        return ApiResponses.json(200, queries.activities(filter, AdminFilter.page(page), AdminFilter.pageSize(pageSize)));
    }

    @GetMapping("/activities/{sessionId}")
    ResponseEntity<AdminViews.ActivityDetail> activity(@AuthenticationPrincipal GuardianPrincipal guardian,
            @PathVariable("sessionId") String sessionId) {
        UUID adminId = access.requireAdmin(guardian);
        return ApiResponses.json(200, queries.activity(adminId, id(sessionId)));
    }

    @GetMapping("/guardians")
    ResponseEntity<AdminViews.Page<AdminViews.GuardianSummary>> guardians(
            @AuthenticationPrincipal GuardianPrincipal guardian,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "timezone", required = false) String timezone,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "guardianId", required = false) String guardianId,
            @RequestParam(name = "childId", required = false) String childId,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "pageSize", required = false) String pageSize) {
        access.requireAdmin(guardian);
        AdminFilter filter = AdminFilter.parse(from, to, timezone, type, query, guardianId, childId, deviceId, status);
        return ApiResponses.json(200, queries.guardians(filter, AdminFilter.page(page), AdminFilter.pageSize(pageSize)));
    }

    @GetMapping("/quality")
    ResponseEntity<AdminViews.Quality> quality(@AuthenticationPrincipal GuardianPrincipal guardian,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "timezone", required = false) String timezone,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "guardianId", required = false) String guardianId,
            @RequestParam(name = "childId", required = false) String childId,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status) {
        access.requireAdmin(guardian);
        return ApiResponses.json(200,
                queries.quality(AdminFilter.parse(from, to, timezone, type, query, guardianId, childId, deviceId, status)));
    }

    @GetMapping("/costs")
    ResponseEntity<AdminViews.Costs> costList(@AuthenticationPrincipal GuardianPrincipal guardian,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "timezone", required = false) String timezone,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "guardianId", required = false) String guardianId,
            @RequestParam(name = "childId", required = false) String childId,
            @RequestParam(name = "deviceId", required = false) String deviceId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "pageSize", required = false) String pageSize) {
        access.requireAdmin(guardian);
        AdminFilter filter = AdminFilter.parse(from, to, timezone, type, query, guardianId, childId, deviceId, status);
        return ApiResponses.json(200, costs.costs(filter, AdminFilter.page(page), AdminFilter.pageSize(pageSize)));
    }

    @GetMapping("/costs/{costId}")
    ResponseEntity<AdminViews.CostDetail> cost(@AuthenticationPrincipal GuardianPrincipal guardian,
            @PathVariable("costId") String costId) {
        access.requireAdmin(guardian);
        return ApiResponses.json(200, costs.detail(id(costId)));
    }

    private static UUID id(String value) {
        return ApiResponses.uuid(value).orElseThrow(() -> CoreApiException.of(ErrorCode.NOT_FOUND));
    }
}
