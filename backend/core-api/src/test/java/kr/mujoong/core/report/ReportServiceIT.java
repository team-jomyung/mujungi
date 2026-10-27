package kr.mujoong.core.report;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import kr.mujoong.core.result.WebTestSupport;
import kr.mujoong.core.common.error.CoreApiException;
import org.junit.jupiter.api.*;
@Tag("result-report") @Tag("db") class ReportServiceIT extends WebTestSupport {
    ReportService service(){return app.bean(ReportService.class);}LocalDate today(){return app.clock().now().atZone(ReportService.ZONE).toLocalDate();}
    @Test void atMostTenActivitiesAreAcceptedAndRejectedRequestsLeaveExistingReportUntouched(){
        var f=family();var sessions=new ArrayList<UUID>();
        for(int i=0;i<11;i++){var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);sessions.add(session);}
        var accepted=service().create(f.guardianId(),today(),today(),sessions.subList(0,10),UUID.randomUUID().toString());
        assertThat(app.bean(ReportRepository.class).activities(accepted.reportId())).hasSize(10);
        assertThatThrownBy(()->service().create(f.guardianId(),today(),today(),sessions,UUID.randomUUID().toString()))
            .isInstanceOf(CoreApiException.class);
        assertThat(service().owned(f.guardianId(),accepted.reportId()).status()).isEqualTo("GENERATING");
        assertThat(app.bean(ReportRepository.class).unsaved(f.guardianId())).hasSize(1);
    }
    @Test void previewUsesListedDateAndIncludesRestartFailure(){var f=family();var session=copy(f,"FAILED","SERVER_RESTART");record(f,session,"FAILED",1);var declined=copy(f,"DECLINED",null);record(f,declined,"DECLINED",0);var preview=service().preview(f.guardianId(),today(),today());assertThat(preview.firstRecordDate()).isEqualTo(today());assertThat(preview.activities()).hasSize(1);assertThat(preview.activities().getFirst().sessionId()).isEqualTo(session);assertThat(preview.activities().getFirst().startedAt()).isNull();}
    @Test void sameKeyReturnsFirstReportAndDifferentBodyConflicts(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);String key=UUID.randomUUID().toString();var first=service().create(f.guardianId(),today(),today(),List.of(session),key);assertThat(service().create(f.guardianId(),today(),today(),List.of(session),key)).isEqualTo(first);assertThatThrownBy(()->service().create(f.guardianId(),today().minusDays(1),today(),List.of(session),key)).isInstanceOf(CoreApiException.class);}
    @Test void foreignSessionRejectsWholeRequest(){var f=family();var other=family();var session=copy(other,"ENDED",null);record(other,session,"ENDED",3);assertThatThrownBy(()->service().create(f.guardianId(),today(),today(),List.of(session),UUID.randomUUID().toString())).isInstanceOf(CoreApiException.class);assertThat(app.bean(ReportRepository.class).unsaved(f.guardianId())).isEmpty();}
    @Test void newRequestDiscardsPreviousUnsavedChildren(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var first=service().create(f.guardianId(),today(),today(),List.of(session),UUID.randomUUID().toString());service().create(f.guardianId(),today(),today(),List.of(session),UUID.randomUUID().toString());assertThat(service().owned(f.guardianId(),first.reportId()).status()).isEqualTo("DISCARDED");assertThat(app.bean(ReportRepository.class).activities(first.reportId())).isEmpty();}
    @Test void saveIsIdempotentAndDiscardLeavesSavedReport(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var created=service().create(f.guardianId(),today(),today(),List.of(session),UUID.randomUUID().toString());db.jdbc().update("update core.core_report set status='READY' where id=:id",Map.of("id",created.reportId()));service().saved(f.guardianId(),created.reportId());var saved=service().owned(f.guardianId(),created.reportId()).savedAt();service().saved(f.guardianId(),created.reportId());service().discard(f.guardianId(),created.reportId());assertThat(service().owned(f.guardianId(),created.reportId()).status()).isEqualTo("SAVED");assertThat(service().owned(f.guardianId(),created.reportId()).savedAt()).isEqualTo(saved);}
    @Test void futureReversedDuplicateAndEmptyRequestsAreRejected(){var f=family();assertThatThrownBy(()->service().preview(f.guardianId(),today(),today().plusDays(1))).isInstanceOf(CoreApiException.class);assertThatThrownBy(()->service().preview(f.guardianId(),today(),today().minusDays(1))).isInstanceOf(CoreApiException.class);assertThatThrownBy(()->service().create(f.guardianId(),today(),today(),List.of(),UUID.randomUUID().toString())).isInstanceOf(CoreApiException.class);UUID id=UUID.randomUUID();assertThatThrownBy(()->service().create(f.guardianId(),today(),today(),List.of(id,id),UUID.randomUUID().toString())).isInstanceOf(CoreApiException.class);}
    @Test void retentionUsesFailureTransitionAndExcludesSaved(){var f=family();var session=copy(f,"ENDED",null);record(f,session,"ENDED",3);var created=service().create(f.guardianId(),today(),today(),List.of(session),UUID.randomUUID().toString());db.jdbc().update("update core.core_report set status='FAILED',created_at=created_at-interval '25 hours' where id=:id",Map.of("id",created.reportId()));app.bean(ReportRetentionAdapter.class).discardExpired();assertThat(service().owned(f.guardianId(),created.reportId()).status()).isEqualTo("DISCARDED");}
}
