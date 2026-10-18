package kr.mujoong.core.result;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
@Tag("result-report")
class ResultEligibilityTest {
    @ParameterizedTest
    @CsvSource({"ENDED,true,ENDED,,ELIGIBLE","ENDED_UNCONFIRMED,true,ENDED_UNCONFIRMED,,ELIGIBLE","ABORTED,true,ABORTED,,ELIGIBLE","DECLINED,true,DECLINED,,NOT_ELIGIBLE","FAILED,true,FAILED,SERVER_RESTART,ELIGIBLE","FAILED,true,ACTIVE,,UNDECIDED","FAILED,true,FAILED,INTERNAL,NOT_ELIGIBLE","ENDED,false,ENDED,,NOT_ELIGIBLE","ENDED_UNCONFIRMED,false,ENDED_UNCONFIRMED,,NOT_ELIGIBLE","ABORTED,false,ABORTED,,NOT_ELIGIBLE","DECLINED,false,DECLINED,,NOT_ELIGIBLE","FAILED,false,FAILED,SERVER_RESTART,NOT_ELIGIBLE","ACTIVE,false,ACTIVE,,NOT_ELIGIBLE","ACTIVE,true,ACTIVE,,NOT_ELIGIBLE"})
    void eligibility(String status,boolean utterances,String copy,String code,ResultEligibility.Decision expected){assertThat(ResultEligibility.decide(status,utterances,copy,code)).isEqualTo(expected);}
}
