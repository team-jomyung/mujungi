package kr.mujoong.core.practice.web;

import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practice.start.PracticeStartRequest;
import kr.mujoong.core.practice.start.PracticeStartService;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class PracticeStartController {
    private final PracticeStartService service;
    private final JsonCodec json;
    public PracticeStartController(PracticeStartService service, JsonCodec json) { this.service=service;this.json=json; }
    @PostMapping("/api/activities/practice")
    ResponseEntity<PracticeStartService.Started> start(@AuthenticationPrincipal GuardianPrincipal principal,
            @RequestBody(required=false) String body) {
        var guardian=GuardianPrincipal.require(principal);
        var request=ApiResponses.readJson(json,body,PracticeStartRequest.class,ErrorCode.VALIDATION_FAILED);
        return ApiResponses.json(202,service.start(guardian,request));
    }
}
