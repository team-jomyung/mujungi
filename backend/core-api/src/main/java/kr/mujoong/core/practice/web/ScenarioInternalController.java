package kr.mujoong.core.practice.web;

import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.practice.Scenario;
import kr.mujoong.core.practice.ScenarioService;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Internal token chain protects this entire path; no guardian endpoint exposes scenario text. */
@RestController
public class ScenarioInternalController {
    private final ScenarioService service;
    private final JsonCodec json;
    public ScenarioInternalController(ScenarioService service, JsonCodec json) { this.service=service; this.json=json; }
    @PostMapping("/internal/practice/scenarios")
    public ResponseEntity<Scenario> generate(@RequestBody(required=false) String body) {
        var request=ApiResponses.readJson(json,body,Body.class,ErrorCode.VALIDATION_FAILED);
        return ApiResponses.json(200,service.generate(new ScenarioService.Request(uuid(request.sessionId()),
                uuid(request.goalSelectionId()),request.kind(),request.previousScenarioId()==null ? null : uuid(request.previousScenarioId()))));
    }
    private UUID uuid(String value) { return ApiResponses.uuid(value).orElseThrow(() -> CoreApiException.of(ErrorCode.VALIDATION_FAILED)); }
    public record Body(String sessionId,String goalSelectionId,String kind,String previousScenarioId) {}
}
