package kr.mujoong.core.report;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.result.WebTestSupport;
import kr.mujoong.core.web.support.WebFixtures;
import org.junit.jupiter.api.*;
@Tag("result-report") @Tag("db") class ReportApiIT extends WebTestSupport {
    @Test void creatingWithElevenActivitiesReturnsValidationFailure(){
        var f=family();var user=WebFixtures.guardianFor(app,f.guardianId());
        String date=app.clock().now().atZone(ReportService.ZONE).toLocalDate().toString();
        var sessions=java.util.stream.IntStream.range(0,11).mapToObj(i->UUID.randomUUID().toString()).toList();
        var response=app.post("/api/reports").session(user.session()).csrf(user.csrf())
            .json(JsonCodec.shared().write(Map.of("from",date,"to",date,"sessionIds",sessions,"idempotencyKey",UUID.randomUUID().toString()))).send();
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json()).containsEntry("code","VALIDATION_FAILED");
        assertThat(app.bean(ReportRepository.class).unsaved(f.guardianId())).isEmpty();
    }
    UUID create(UUID guardian,UUID session){var today=app.clock().now().atZone(ReportService.ZONE).toLocalDate();return app.bean(ReportService.class).create(guardian,today,today,List.of(session),UUID.randomUUID().toString()).reportId();}
    @Test void previewAndCreateConformToSchemas(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var user=WebFixtures.guardianFor(app,f.guardianId());String date=app.clock().now().atZone(ReportService.ZONE).toLocalDate().toString();assertThat(checked("GET /api/reports/preview",app.get("/api/reports/preview?from="+date+"&to="+date).session(user.session())).status()).isEqualTo(200);var body=Map.of("from",date,"to",date,"sessionIds",List.of(session),"idempotencyKey",UUID.randomUUID().toString());assertThat(checked("POST /api/reports",app.post("/api/reports").session(user.session()).csrf(user.csrf()).json(JsonCodec.shared().write(body))).status()).isEqualTo(202);}
    @Test void generatingSectionsAreEmptyAndReadySectionsHavePublicShape(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(checked("GET /api/reports/{reportId}",app.get("/api/reports/"+id).session(user.session())).json().get("sections")).isEqualTo(List.of());db.jdbc().update("update core.core_report set status='READY' where id=:id",Map.of("id",id));var response=checked("GET /api/reports/{reportId}",app.get("/api/reports/"+id).session(user.session()));assertThat(response.status()).isEqualTo(200);assertThat(response.body()).doesNotContain("requestHash","guardianId","jobId","templated","interests");}
    /** JIRA-267(FT-1023-c): 공급자 키가 없는 설정(결과 공급자가 부르면 PROVIDER_UNAVAILABLE)에서도 리포트가 READY가 된다 — AI 호출 0회. */
    @Test void reportBecomesReadyWithoutProviderKey(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var runner=app.bean(kr.mujoong.core.result.JobRunner.class);var jobs=app.bean(kr.mujoong.core.result.ResultJobRepository.class);
        // 결과 접수 전 활동이라 리포트는 정리를 10초마다 다시 보며 미루다가, 정리 대기 상한(5분)이 지나면 있는 자료로 만든다.
        for(int attempt=0;attempt<40&&app.bean(ReportRepository.class).find(id).orElseThrow().status().equals("GENERATING");attempt++){
            var job=runner.claim("REPORT").stream().filter(j->j.subjectId().equals(id)).findFirst().orElseThrow();runner.execute(job);
            var after=jobs.find(job.id()).orElseThrow();if(after.status().equals("REQUESTED"))app.clock().advance(java.time.Duration.between(app.clock().now(),after.nextAttemptAt()));
        }
        var report=app.bean(ReportRepository.class).find(id).orElseThrow();assertThat(report.status()).isEqualTo("READY");var sections=app.bean(ReportRepository.class).sections(id);assertThat(sections).hasSize(10);
        // 자유대화만 고른 리포트: 2항목은 연습 장면 인용이 없어 자료 부족, 10항목에 연습 고정 문장이 없다.
        assertThat(sections.get(1).missing()).isTrue();assertThat(sections.get(1).citations()).isEmpty();
        assertThat(sections.get(9).text()).doesNotContain(ReportAssembler.LIMITATION).contains("정리 중인 활동 1개");var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(checked("GET /api/reports/{reportId}",app.get("/api/reports/"+id).session(user.session())).status()).isEqualTo(200);}
    @Test void pdfReadinessFilenameAndCacheHeaders(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var user=WebFixtures.guardianFor(app,f.guardianId());String path="/api/reports/"+id+"/pdf";assertThat(app.get(path).session(user.session()).send().status()).isEqualTo(409);db.jdbc().update("update core.core_report set status='READY' where id=:id",Map.of("id",id));var response=app.get(path).session(user.session()).send();assertThat(response.status()).isEqualTo(200);assertThat(response.headers().get("content-type")).contains("application/pdf");assertThat(response.headers().get("cache-control")).contains("no-store");assertThat(response.headers().get("content-disposition").getFirst()).contains("filename*=UTF-8''",".pdf");}
    @Test void savedRequiresReadyAndIsIdempotent(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var user=WebFixtures.guardianFor(app,f.guardianId());String path="/api/reports/"+id+"/saved";assertThat(app.post(path).session(user.session()).csrf(user.csrf()).json("{}").send().status()).isEqualTo(409);db.jdbc().update("update core.core_report set status='READY' where id=:id",Map.of("id",id));for(int i=0;i<2;i++)assertThat(checked("POST /api/reports/{reportId}/saved",app.post(path).session(user.session()).csrf(user.csrf()).json("{}")).status()).isEqualTo(204);}
    @Test void discardReturns204WithTombstone(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(checked("POST /api/reports/{reportId}/discard",app.post("/api/reports/"+id+"/discard").session(user.session()).csrf(user.csrf()).json("{}")).status()).isEqualTo(204);assertThat(checked("GET /api/reports/{reportId}",app.get("/api/reports/"+id).session(user.session())).json()).containsEntry("status","DISCARDED");}
    @Test void ownerAndDeletionGuardsApply(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var other=WebFixtures.guardianFor(app,family().guardianId());assertThat(app.get("/api/reports/"+id).session(other.session()).send().status()).isEqualTo(404);var user=WebFixtures.guardianFor(app,f.guardianId());db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:id",Map.of("id",f.guardianId()));assertThat(app.get("/api/reports/"+id).session(user.session()).send().status()).isEqualTo(403);}
    @Test void writesRequireCsrfAndDatesAreValidated(){var f=family();var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(app.post("/api/reports").session(user.session()).json("{}").send().status()).isEqualTo(403);assertThat(app.get("/api/reports/preview?from=no&to=no").session(user.session()).send().status()).isEqualTo(400);}
    /** FT-1029-a: 기록 목록 · 리포트 미리보기 줄의 summary는 정리 끝 현재 결과의 결과 문장이고, 정리 전 · 동의 철회는 null이다. */
    @SuppressWarnings("unchecked")
    @Test void listAndPreviewCarryDoneResultSentencesAsSummary(){
        var f=family();var done=copy(f,"ENDED",null);record(f,done,"ENDED",3);var pending=copy(f,"ENDED",null);record(f,pending,"ENDED",3);
        var withdrawn=copy(f,"ENDED",null);record(f,withdrawn,"ENDED",3);
        db.jdbc().update("update core.core_activity_record set end_reason='CONSENT_WITHDRAWN' where session_id=:id",Map.of("id",withdrawn));
        var now=app.clock().now();var states=app.bean(kr.mujoong.core.result.SessionResultStateRepository.class);
        var jobs=app.bean(kr.mujoong.core.result.ResultJobRepository.class);var results=app.bean(kr.mujoong.core.result.ActivityResultRepository.class);
        for(UUID s:List.of(done,withdrawn)){
            states.insert(s,f.guardianId(),f.childId(),"FREE_TALK",true,now);
            var job=jobs.submit("SESSION_RESULT",s,f.guardianId(),"SR:"+s,"ACTIVITY_RECORDED",now.plusSeconds(60),now);states.attach(s,job.id(),1,now);
            var result=results.insert(s,job.id(),"FULL","[{\"goalSelectionId\":null,\"text\":\"블록을 같이 쌓자고 먼저 말했어요.\",\"citations\":[]},{\"goalSelectionId\":null,\"text\":\" 차례도 지켰어요. \",\"citations\":[]}]","[]","[]",null,"[]","[]",now);
            db.jdbc().update("update core.core_session_result_state set job_status='DONE',current_result_id=:r where session_id=:s",Map.of("r",result.id(),"s",s));
        }
        states.insert(pending,f.guardianId(),f.childId(),"FREE_TALK",true,now);
        var user=WebFixtures.guardianFor(app,f.guardianId());String date=now.atZone(ReportService.ZONE).toLocalDate().toString();
        var list=(List<Map<String,Object>>)checked("GET /api/records",app.get("/api/records?date="+date).session(user.session())).json().get("items");
        var preview=(List<Map<String,Object>>)checked("GET /api/reports/preview",app.get("/api/reports/preview?from="+date+"&to="+date).session(user.session())).json().get("activities");
        for(var rows:List.of(list,preview)){
            Map<String,Object> bySession=new HashMap<>();rows.forEach(r->bySession.put((String)r.get("sessionId"),r.get("summary")));
            assertThat(bySession).containsEntry(done.toString(),"블록을 같이 쌓자고 먼저 말했어요. 차례도 지켰어요.")
                    .containsEntry(pending.toString(),null).containsEntry(withdrawn.toString(),null);
        }
    }
    @SuppressWarnings("unchecked")
    @Test void savedListIsOwnedPagedAndDoesNotExposeContent(){
        var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);
        var user=WebFixtures.guardianFor(app,f.guardianId());var other=WebFixtures.guardianFor(app,family().guardianId());
        UUID newest=null;
        for(int i=0;i<22;i++){
            var id=create(f.guardianId(),session);
            if(i<21){db.jdbc().update("update core.core_report set status='SAVED',saved_at=created_at + (:seconds * interval '1 second') where id=:id",Map.of("id",id,"seconds",i));newest=id;}
        }
        var first=checked("GET /api/reports",app.get("/api/reports?page=0").session(user.session()));
        assertThat(first.status()).isEqualTo(200);
        var rows=(List<Map<String,Object>>)first.json().get("items");
        assertThat(rows).hasSize(20);assertThat(rows.getFirst()).containsEntry("reportId",newest.toString());
        assertThat(first.json()).containsEntry("hasMore",true);
        var pdf=app.get("/api/reports/"+newest+"/pdf").session(user.session()).send();
        assertThat(pdf.status()).isEqualTo(200);assertThat(pdf.headers().get("content-type")).contains("application/pdf");
        assertThat(app.get("/api/reports/"+newest+"/pdf").session(other.session()).send().status()).isEqualTo(404);
        assertThat(app.bean(ReportRepository.class).find(newest).orElseThrow().status()).isEqualTo("SAVED");
        assertThat(first.body()).doesNotContain("guardianId","childId","childDisplayName","text","quote","requestHash");
        var second=app.get("/api/reports?page=1").session(user.session()).send();
        assertThat((List<?>)second.json().get("items")).hasSize(1);assertThat(second.json()).containsEntry("hasMore",false);
        assertThat((List<?>)app.get("/api/reports").session(other.session()).send().json().get("items")).isEmpty();
        assertThat(app.get("/api/reports").send().status()).isEqualTo(401);
        for(String page:List.of("-1","100001","no"))assertThat(app.get("/api/reports?page="+page).session(user.session()).send().status()).isEqualTo(400);
        db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:id",Map.of("id",f.guardianId()));
        assertThat(app.get("/api/reports").session(user.session()).send().status()).isEqualTo(403);
    }
}
