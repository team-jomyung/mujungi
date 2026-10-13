package kr.mujoong.core.practice.web;
import java.util.List;
import java.util.UUID;
import kr.mujoong.contract.error.ErrorCode;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.practice.GoalSelectionService;
import kr.mujoong.core.security.GuardianPrincipal;
import kr.mujoong.core.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** C4 guardian goal selections; the security chain requires session and CSRF for mutations. */
@RestController
public class GoalSelectionController {
    private final GoalSelectionService service;
    private final JsonCodec json;
    public GoalSelectionController(GoalSelectionService service,JsonCodec json){this.service=service;this.json=json;}
    @GetMapping("/api/goal-selections")
    public ResponseEntity<Items> list(@AuthenticationPrincipal GuardianPrincipal guardian){
        return ApiResponses.json(200,new Items(service.list(GuardianPrincipal.require(guardian)).stream().map(GoalBody::of).toList()));
    }
    @PostMapping("/api/goal-selections")
    public ResponseEntity<GoalBody> add(@AuthenticationPrincipal GuardianPrincipal guardian,@RequestBody(required=false) String body){
        UUID owner=GuardianPrincipal.require(guardian);
        AddRequest request=ApiResponses.readJson(json,body,AddRequest.class,ErrorCode.VALIDATION_FAILED);
        return ApiResponses.json(201,GoalBody.of(service.add(owner,request.recommendationId())));
    }
    @PatchMapping("/api/goal-selections/{goalSelectionId}")
    public ResponseEntity<GoalBody> favorite(@AuthenticationPrincipal GuardianPrincipal guardian,
            @PathVariable UUID goalSelectionId,@RequestBody(required=false) String body){
        UUID owner=GuardianPrincipal.require(guardian);
        FavoriteRequest request=ApiResponses.readJson(json,body,FavoriteRequest.class,ErrorCode.VALIDATION_FAILED);
        return ApiResponses.json(200,GoalBody.of(service.favorite(owner,goalSelectionId,request.isFavorite())));
    }
    @DeleteMapping("/api/goal-selections/{goalSelectionId}")
    public ResponseEntity<Deleted> delete(@AuthenticationPrincipal GuardianPrincipal guardian,@PathVariable UUID goalSelectionId){
        return ApiResponses.json(200,new Deleted(ApiResponses.time(service.delete(GuardianPrincipal.require(guardian),goalSelectionId))));
    }
    @PostMapping("/api/goal-selections/{goalSelectionId}/restore")
    public ResponseEntity<GoalBody> restore(@AuthenticationPrincipal GuardianPrincipal guardian,@PathVariable UUID goalSelectionId){
        return ApiResponses.json(200,GoalBody.of(service.restore(GuardianPrincipal.require(guardian),goalSelectionId)));
    }
    public record AddRequest(UUID recommendationId){}
    public record FavoriteRequest(Boolean isFavorite){}
    public record Deleted(String restoreUntil){}
    public record Items(List<GoalBody> items){}
    public record GoalBody(UUID goalSelectionId,String displayName,boolean isFavorite,long sortOrder,String selectedAt,
            String sourceActivityType,String sourceActivityAt,Integer practiceCount){
        public static GoalBody of(GoalSelectionService.View value){
            return new GoalBody(value.goalSelectionId(),value.displayName(),value.isFavorite(),value.sortOrder(),
                ApiResponses.time(value.selectedAt()),value.sourceActivityType(),ApiResponses.time(value.sourceActivityAt()),value.practiceCount());
        }
    }
}
