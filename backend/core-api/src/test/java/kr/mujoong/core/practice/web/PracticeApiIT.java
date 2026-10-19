package kr.mujoong.core.practice.web;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.practice.CandidateRegistry;
import kr.mujoong.core.practice.PracticeDeletionParticipant;
import kr.mujoong.core.practice.PracticeGoalRepository;
import kr.mujoong.core.web.support.CoreWebApp;
import kr.mujoong.core.web.support.OpenApiContracts;
import kr.mujoong.core.web.support.WebFixtures;
import kr.mujoong.testsupport.http.ContractStubServer;
import kr.mujoong.testsupport.secret.TestSecrets;
import org.junit.jupiter.api.*;
@Tag("practice-core") @Tag("db")
class PracticeApiIT {
    private static DbTestSupport db;
    private static CoreWebApp app;
    private static ContractStubServer realtime;
    private static final List<UUID> owners=new ArrayList<>();
    @BeforeAll static void start(){
        db=DbTestSupport.coreApp();
        String token=TestSecrets.internalServiceToken();
        realtime=WebFixtures.realtime(token);
        app=CoreWebApp.builder(db.postgres()).internalToken(token).realtime(realtime.baseUrl()).start();
    }
    @AfterAll static void stop(){
        if(app!=null){
            for(UUID owner:owners)app.bean(PracticeDeletionParticipant.class).deleteByGuardian(owner,UUID.randomUUID());
            app.close();
        }
        if(realtime!=null)realtime.close();
        if(db!=null){WebFixtures.deleteSessions(db,owners.toArray(UUID[]::new));db.close();}
    }
    private static WebFixtures.Guardian guardian(){
        UUID owner=db.insertGuardian("ACTIVE");owners.add(owner);db.insertChild(owner);
        return WebFixtures.guardianFor(app,owner);
    }
    private static UUID candidate(WebFixtures.Guardian guardian){
        UUID child=db.jdbc().queryForObject("select id from core.core_child where guardian_id=:id",Map.of("id",guardian.id()),UUID.class);
        return db.inTx(status->app.bean(PracticeGoalRepository.class).register(child,UUID.randomUUID(),
            new CandidateRegistry.Candidate("ACCEPT_STOP",1,"친구의 말에 대답한 기록",
                List.of(new CandidateRegistry.Evidence(1,"CHILD_RECALL"))),"친구가 그만하자고 할 때 받아들이기",app.clock().now()));
    }
    private static String body(UUID id){return "{\"recommendationId\":\""+id+"\"}";}
    @Test void allC4MutationsRequireCsrfAndReadsRequireGuardian(){
        var guardian=guardian();UUID candidate=candidate(guardian);
        assertThat(app.get("/api/goal-selections").send().status()).isEqualTo(401);
        assertThat(app.post("/api/goal-selections").session(guardian.session()).json(body(candidate)).send().status()).isEqualTo(403);
        assertThat(app.call("PATCH","/api/goal-selections/"+candidate).session(guardian.session()).json("{\"isFavorite\":true}").send().status()).isEqualTo(403);
        assertThat(app.call("DELETE","/api/goal-selections/"+candidate).session(guardian.session()).send().status()).isEqualTo(403);
        assertThat(app.post("/api/goal-selections/"+candidate+"/restore").session(guardian.session()).send().status()).isEqualTo(403);
        var empty=app.get("/api/goal-selections").session(guardian.session()).send();
        assertThat(OpenApiContracts.load().violations("GET /api/goal-selections",empty)).isEmpty();
        assertThat(empty.status()).isEqualTo(200);assertThat(empty.json()).isEqualTo(Map.of("items",List.of()));
    }
    @Test void c4RoundTripReturnsOnlyPresentationFieldsAndOwnershipIsEnforced(){
        var guardian=guardian();var other=guardian();UUID recommendation=candidate(guardian);
        var added=app.post("/api/goal-selections").session(guardian.session()).csrf(guardian.csrf()).json(body(recommendation)).send();
        assertThat(OpenApiContracts.load().violations("POST /api/goal-selections",added)).isEmpty();
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.json()).containsOnlyKeys("goalSelectionId","displayName","isFavorite","sortOrder","selectedAt",
            "sourceActivityType","sourceActivityAt","practiceCount");
        assertThat(added.json().get("practiceCount")).isNull();
        assertThat(added.body()).doesNotContain("behaviorVersion","judgmentCriteria","scenarioText","reasonText","guardianId","childId");
        String id=(String)added.json().get("goalSelectionId");String path="/api/goal-selections/"+id;
        assertThat(app.post("/api/goal-selections").session(guardian.session()).csrf(guardian.csrf()).json(body(recommendation)).send().status()).isEqualTo(409);
        var favorite=app.call("PATCH",path).session(guardian.session()).csrf(guardian.csrf()).json("{\"isFavorite\":true}").send();
        assertThat(OpenApiContracts.load().violations("PATCH /api/goal-selections/{goalSelectionId}",favorite)).isEmpty();
        assertThat(favorite.status()).isEqualTo(200);assertThat(favorite.json()).containsEntry("isFavorite",true);
        assertThat(app.call("DELETE",path).session(other.session()).csrf(other.csrf()).send().status()).isEqualTo(404);
        var removed=app.call("DELETE",path).session(guardian.session()).csrf(guardian.csrf()).send();
        assertThat(OpenApiContracts.load().violations("DELETE /api/goal-selections/{goalSelectionId}",removed)).isEmpty();
        assertThat(removed.status()).isEqualTo(200);assertThat(removed.json()).containsOnlyKeys("restoreUntil");
        assertThat(app.get("/api/goal-selections").session(guardian.session()).send().json()).isEqualTo(Map.of("items",List.of()));
        var restored=app.post(path+"/restore").session(guardian.session()).csrf(guardian.csrf()).send();
        assertThat(OpenApiContracts.load().violations("POST /api/goal-selections/{goalSelectionId}/restore",restored)).isEmpty();
        assertThat(restored.status()).isEqualTo(200);assertThat(restored.json()).containsEntry("goalSelectionId",id).containsEntry("isFavorite",true);
        app.call("DELETE",path).session(guardian.session()).csrf(guardian.csrf()).send();
        app.clock().advance(Duration.ofSeconds(5));
        var expired=app.post(path+"/restore").session(guardian.session()).csrf(guardian.csrf()).send();
        assertThat(OpenApiContracts.load().violations("POST /api/goal-selections/{goalSelectionId}/restore",expired)).isEmpty();
        assertThat(expired.status()).isEqualTo(410);assertThat(expired.code()).isEqualTo("RESTORE_EXPIRED");
    }
    @Test void malformedBodiesFailWithoutCreatingRowsAndDeletingAccountCannotRead(){
        var guardian=guardian();
        for(String bad:List.of("{","[]","{}","{\"recommendationId\":\"invalid\"}")){
            var response=app.post("/api/goal-selections").session(guardian.session()).csrf(guardian.csrf()).json(bad).send();
            assertThat(response.status()).as(bad).isEqualTo(400);
        }
        UUID recommendation=candidate(guardian);
        var saved=app.post("/api/goal-selections").session(guardian.session()).csrf(guardian.csrf()).json(body(recommendation)).send();
        String path="/api/goal-selections/"+saved.json().get("goalSelectionId");
        assertThat(app.call("PATCH",path).session(guardian.session()).csrf(guardian.csrf()).json("{}").send().status()).isEqualTo(400);
        db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:id",Map.of("id",guardian.id()));
        var blocked=app.get("/api/goal-selections").session(guardian.session()).send();
        assertThat(blocked.status()).isEqualTo(403);assertThat(blocked.code()).isEqualTo("ACCOUNT_DELETION_IN_PROGRESS");
    }
    @Test void c10RequiresInternalAuthenticationAndDisconnectedSessionPortFailsClosed(){
        String body="{\"sessionId\":\""+UUID.randomUUID()+"\",\"goalSelectionId\":\""+UUID.randomUUID()+"\",\"kind\":\"ORIGINAL\"}";
        assertThat(app.post("/internal/practice/scenarios").json(body).send().status()).isEqualTo(401);
        var invalid=app.post("/internal/practice/scenarios").internalToken().json("{}").send();
        assertThat(invalid.status()).isEqualTo(400);
        var unavailable=app.post("/internal/practice/scenarios").internalToken().json(body).send();
        assertThat(OpenApiContracts.load().violations("POST /internal/practice/scenarios",unavailable)).isEmpty();
        assertThat(unavailable.status()).isEqualTo(503);assertThat(unavailable.code()).isEqualTo("SERVICE_NOT_READY");
        assertThat(unavailable.json()).containsOnlyKeys("code","message","traceId");
        assertThat(unavailable.body()).doesNotContain(app.internalToken());
    }
}
