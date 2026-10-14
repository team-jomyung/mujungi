package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import java.time.ZoneId;
import java.util.UUID;
import kr.mujoong.core.web.support.WebFixtures;
import org.junit.jupiter.api.*;
@Tag("u6") @Tag("db")
class RecordFailedExposureU6IT extends U6WebTestSupport {
    @Test void restartedFailureWithUtterancesIsListedAndReadable(){var f=family();UUID s=copy(f,"FAILED","SERVER_RESTART");record(f,s,"FAILED",1);var user=WebFixtures.guardianFor(app,f.guardianId());String day=app.clock().now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();var list=checked("GET /api/records",app.get("/api/records?date="+day).session(user.session()));assertThat(list.body()).contains(s.toString(),"\"interrupted\":true");assertThat(app.get("/api/records/"+s).session(user.session()).send().status()).isEqualTo(200);}
    @Test void restartedFailureWithoutUtterancesIsHidden(){var f=family();UUID s=copy(f,"FAILED","SERVER_RESTART");record(f,s,"FAILED",0);var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(app.get("/api/records/"+s).session(user.session()).send().status()).isEqualTo(404);}
    @Test void otherFailureIsHidden(){var f=family();UUID s=copy(f,"FAILED","INTERNAL");record(f,s,"FAILED",1);var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(app.get("/api/records/"+s).session(user.session()).send().status()).isEqualTo(404);}
    @Test void undecidedFailureWaitsForCopy(){var f=family();UUID s=copy(f,"ENDED",null);record(f,s,"FAILED",1);var user=WebFixtures.guardianFor(app,f.guardianId());assertThat(app.get("/api/records/"+s).session(user.session()).send().status()).isEqualTo(404);assertThat(app.get("/api/sessions/"+s+"/result/status").session(user.session()).send().json()).containsEntry("jobStatus","REQUESTED");}
    @Test void calendarUsesListedDateWhenStartMissing(){var f=family();UUID s=copy(f,"FAILED","SERVER_RESTART");record(f,s,"FAILED",1);var user=WebFixtures.guardianFor(app,f.guardianId());String month=app.clock().now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString().substring(0,7);assertThat(checked("GET /api/records",app.get("/api/records?month="+month).session(user.session())).body()).contains("\"count\":1");}
}
