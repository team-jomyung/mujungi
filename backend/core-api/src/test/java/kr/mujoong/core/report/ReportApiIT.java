package kr.mujoong.core.report;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.result.WebTestSupport;
import kr.mujoong.core.web.support.WebFixtures;
import org.junit.jupiter.api.*;
@Tag("result-report") @Tag("db") class ReportApiIT extends WebTestSupport {
    UUID create(UUID guardian,UUID session){var today=app.clock().now().atZone(ReportService.ZONE).toLocalDate();return app.bean(ReportService.class).create(guardian,today,today,List.of(session),UUID.randomUUID().toString()).reportId();}
    @Test void previewAndCreateConformToSchemas(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var user=WebFixtures.guardianFor(app,f.guardianId());String date=app.clock().now().atZone(ReportService.ZONE).toLocalDate().toString();assertThat(checked("GET /api/reports/preview",app.get("/api/reports/preview?from="+date+"&to="+date).session(user.session())).status()).isEqualTo(200);var body=Map.of("from",date,"to",date,"sessionIds",List.of(session),"idempotencyKey",UUID.randomUUID().toString());assertThat(checked("POST /api/reports",app.post("/api/reports").session(user.session()).csrf(user.csrf()).json(JsonCodec.shared().write(body))).status()).isEqualTo(202);}
    @Test void generatingSectionsAreEmptyAndReadySectionsHavePublicShape(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(checked("GET /api/reports/{reportId}",app.get("/api/reports/"+id).session(user.session())).json().get("sections")).isEqualTo(List.of());db.jdbc().update("update core.core_report set status='READY' where id=:id",Map.of("id",id));var response=checked("GET /api/reports/{reportId}",app.get("/api/reports/"+id).session(user.session()));assertThat(response.status()).isEqualTo(200);assertThat(response.body()).doesNotContain("requestHash","guardianId","jobId","templated","interests");}
    /** JIRA-267(FT-1023-c): 공급자 키가 없는 설정(결과 공급자가 부르면 PROVIDER_UNAVAILABLE)에서도 리포트가 READY가 된다 — AI 호출 0회. */
    @Test void reportBecomesReadyWithoutProviderKey(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var runner=app.bean(kr.mujoong.core.result.JobRunner.class);var job=runner.claim("REPORT").stream().filter(j->j.subjectId().equals(id)).findFirst().orElseThrow();runner.execute(job);var report=app.bean(ReportRepository.class).find(id).orElseThrow();assertThat(report.status()).isEqualTo("READY");var sections=app.bean(ReportRepository.class).sections(id);assertThat(sections).hasSize(10);assertThat(sections.get(1).citations()).isNotEmpty();assertThat(sections.get(9).text()).startsWith(ReportAssembler.LIMITATION);var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(checked("GET /api/reports/{reportId}",app.get("/api/reports/"+id).session(user.session())).status()).isEqualTo(200);}
    @Test void pdfReadinessFilenameAndCacheHeaders(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var user=WebFixtures.guardianFor(app,f.guardianId());String path="/api/reports/"+id+"/pdf";assertThat(app.get(path).session(user.session()).send().status()).isEqualTo(409);db.jdbc().update("update core.core_report set status='READY' where id=:id",Map.of("id",id));var response=app.get(path).session(user.session()).send();assertThat(response.status()).isEqualTo(200);assertThat(response.headers().get("content-type")).contains("application/pdf");assertThat(response.headers().get("cache-control")).contains("no-store");assertThat(response.headers().get("content-disposition").getFirst()).contains("filename*=UTF-8''",".pdf");}
    @Test void savedRequiresReadyAndIsIdempotent(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var user=WebFixtures.guardianFor(app,f.guardianId());String path="/api/reports/"+id+"/saved";assertThat(app.post(path).session(user.session()).csrf(user.csrf()).json("{}").send().status()).isEqualTo(409);db.jdbc().update("update core.core_report set status='READY' where id=:id",Map.of("id",id));for(int i=0;i<2;i++)assertThat(checked("POST /api/reports/{reportId}/saved",app.post(path).session(user.session()).csrf(user.csrf()).json("{}")).status()).isEqualTo(204);}
    @Test void discardReturns204WithTombstone(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(checked("POST /api/reports/{reportId}/discard",app.post("/api/reports/"+id+"/discard").session(user.session()).csrf(user.csrf()).json("{}")).status()).isEqualTo(204);assertThat(checked("GET /api/reports/{reportId}",app.get("/api/reports/"+id).session(user.session())).json()).containsEntry("status","DISCARDED");}
    @Test void ownerAndDeletionGuardsApply(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var id=create(f.guardianId(),session);var other=WebFixtures.guardianFor(app,family().guardianId());assertThat(app.get("/api/reports/"+id).session(other.session()).send().status()).isEqualTo(404);var user=WebFixtures.guardianFor(app,f.guardianId());db.jdbc().update("update core.core_guardian set status='DELETION_REQUESTED' where id=:id",Map.of("id",f.guardianId()));assertThat(app.get("/api/reports/"+id).session(user.session()).send().status()).isEqualTo(403);}
    @Test void writesRequireCsrfAndDatesAreValidated(){var f=family();var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(app.post("/api/reports").session(user.session()).json("{}").send().status()).isEqualTo(403);assertThat(app.get("/api/reports/preview?from=no&to=no").session(user.session()).send().status()).isEqualTo(400);}
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
