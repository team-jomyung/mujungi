package kr.mujoong.core.result.web;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.identity.GuardianRepository;
import kr.mujoong.core.result.*;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** C5: 보호자 인증·삭제 상태 확인 뒤 소유 세션의 결과만 낸다. CSRF는 공통 보안 체인에서 검사한다. */
@RestController
public class ResultController {
    private final ResultQueryService query;private final ResultRetryService retry;private final GuardianRepository guardians;
    public ResultController(ResultQueryService query,ResultRetryService retry,GuardianRepository guardians){this.query=query;this.retry=retry;this.guardians=guardians;}
    @GetMapping("/api/sessions/{sessionId}/result")
    public ResponseEntity<ResultQueryService.Result> result(@AuthenticationPrincipal GuardianPrincipal principal,@PathVariable String sessionId){return ApiResponses.json(200,query.result(GuardianPrincipal.requireActive(principal,guardians),id(sessionId)));}
    @GetMapping("/api/sessions/{sessionId}/result/status")
    public ResponseEntity<ResultQueryService.Status> status(@AuthenticationPrincipal GuardianPrincipal principal,@PathVariable String sessionId){return ApiResponses.json(200,query.status(GuardianPrincipal.requireActive(principal,guardians),id(sessionId)));}
    @PostMapping("/api/sessions/{sessionId}/result/retry")
    public ResponseEntity<Map<String,String>> retry(@AuthenticationPrincipal GuardianPrincipal principal,@PathVariable String sessionId){retry.retry(GuardianPrincipal.requireActive(principal,guardians),id(sessionId));return ApiResponses.json(202,Map.of("jobStatus","REQUESTED"));}
    private UUID id(String value){return ApiResponses.uuid(value).orElseThrow(()->CoreApiException.of(ErrorCode.NOT_FOUND));}
}
