package kr.mujoong.core.report;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import kr.mujoong.core.common.db.DbTestSupport;
import kr.mujoong.core.result.ResultJobRepository;
import org.junit.jupiter.api.*;
@Tag("result-report") @Tag("db")
class ReportRepositoryIT {
    DbTestSupport db;ReportRepository reports;Instant now=Instant.parse("2026-10-14T00:00:00Z");UUID g=UUID.randomUUID(),c=UUID.randomUUID(),s=UUID.randomUUID();
    @BeforeEach void open(){db=DbTestSupport.coreApp();reports=new ReportRepository(db.jdbc());}
    @AfterEach void close(){db.close();}
    ReportRepository.Report create(){return reports.create(UUID.randomUUID(),g,c,UUID.randomUUID().toString(),"hash",LocalDate.of(2026,10,13),LocalDate.of(2026,10,14),"아이",8,List.of(new ReportRepository.Activity(s,LocalDate.of(2026,10,14),"FREE_TALK","DONE")),now);}
    UUID attach(ReportRepository.Report r){var j=new ResultJobRepository(db.jdbc()).submit("REPORT",r.id(),g,r.id().toString(),"REPORT_REQUEST",now.plusSeconds(60),now);reports.attachJob(r.id(),j.id());return j.id();}
    List<ReportRepository.Section> sections(){return List.of(new ReportRepository.Section(1,"활동","대화",false,true,List.of(new ReportRepository.Citation(s,1L,"안녕",now,"TRANSCRIPT"))));}
    @Test void discardLeavesOnlyTombstoneAndRejectsLateWrite(){db.inRolledBackTx(t->{var r=create();var j=attach(r);reports.writeSections(r.id(),j,"PARTIAL",sections());reports.discard(r.id(),now);reports.discard(r.id(),now.plusSeconds(1));assertThat(reports.activities(r.id())).isEmpty();assertThat(reports.sections(r.id())).isEmpty();assertThat(reports.writeSections(r.id(),j,"FULL",sections())).isFalse();assertThat(reports.find(r.id()).orElseThrow().discardedAt()).isEqualTo(now);return null;});}
    @Test void keyLookupIsGuardianScoped(){db.inRolledBackTx(t->{var r=create();assertThat(reports.byKey(g,r.idempotencyKey())).isPresent();assertThat(reports.byKey(UUID.randomUUID(),r.idempotencyKey())).isEmpty();return null;});}
    @Test void retentionExcludesSavedAndGenerating(){db.inRolledBackTx(t->{var r=create();assertThat(reports.expired(now.plusSeconds(86400))).isEmpty();db.jdbc().update("update core.core_report set status='READY',ready_at=:now where id=:id",kr.mujoong.core.common.db.ClockParams.at(now).addValue("id",r.id()));assertThat(reports.expired(now.minusSeconds(1))).isEmpty();assertThat(reports.expired(now)).hasSize(1);assertThat(reports.saved(r.id(),now)).isTrue();assertThat(reports.saved(r.id(),now.plusSeconds(1))).isFalse();assertThat(reports.expired(now.plusSeconds(86400))).isEmpty();return null;});}
    @Test void citationRoundTripAndDistinctCount(){db.inRolledBackTx(t->{var r=create();reports.writeSections(r.id(),attach(r),"PARTIAL",sections());assertThat(reports.sections(r.id())).isEqualTo(sections());assertThat(reports.find(r.id()).orElseThrow().citationCount()).isEqualTo(1);return null;});}
    @Test void activityDateIsKept(){db.inRolledBackTx(t->{var r=create();assertThat(reports.activities(r.id()).getFirst().activityDate()).isEqualTo(LocalDate.of(2026,10,14));return null;});}
}
