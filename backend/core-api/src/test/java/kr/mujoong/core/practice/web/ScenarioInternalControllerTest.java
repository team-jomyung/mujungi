package kr.mujoong.core.practice.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.common.error.CoreApiException;
import kr.mujoong.core.practice.Scenario;
import kr.mujoong.core.practice.ScenarioService;
import kr.mujoong.core.web.support.WebFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("practice-core")
class ScenarioInternalControllerTest {
    final ScenarioService service=mock(ScenarioService.class);
    final ScenarioInternalController controller=new ScenarioInternalController(service,JsonCodec.shared());
    @Test void successUsesExactC10SchemaAndRequest() {
        var session=UUID.randomUUID();var goal=UUID.randomUUID();var previous=UUID.randomUUID();
        var request=new ScenarioService.Request(session,goal,"NEW_SCENARIO",previous);
        var expected=new Scenario(UUID.randomUUID(),"NEW_SCENARIO","PEER","기회","상상 속 상황","또래","대사",new Scenario.Validation(true,true,false));
        when(service.generate(request)).thenReturn(expected);
        String body=JsonCodec.shared().write(request);
        assertThat(WebFixtures.schemaErrors("internal-practice-scenario-request.schema.json",body)).isEmpty();
        var result=controller.generate(body);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(expected);
        assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json",JsonCodec.shared().write(result.getBody()))).isEmpty();
        verify(service).generate(request);
    }
    @Test void malformedUuidAndUnsupportedKindsNeverReachService() {
        for(String bad:new String[]{null,"[]","{}","{\"sessionId\":\"1-1-1-1-1\"}",
                "{\"sessionId\":\""+UUID.randomUUID()+"\",\"goalSelectionId\":\""+UUID.randomUUID()+"\",\"kind\":\"NEW_SCENARIO\"}"}) {
            assertThatThrownBy(() -> controller.generate(bad)).isInstanceOf(CoreApiException.class);
        }
        verifyNoInteractions(service);
    }
    @Test void schemaRejectsMissingPredecessorAndUnvalidatedScenario() {
        String ids="\"sessionId\":\""+UUID.randomUUID()+"\",\"goalSelectionId\":\""+UUID.randomUUID()+"\"";
        assertThat(WebFixtures.schemaErrors("internal-practice-scenario-request.schema.json","{"+ids+",\"kind\":\"NEW_SCENARIO\"}")).isNotEmpty();
        assertThat(WebFixtures.schemaErrors("internal-practice-scenario-request.schema.json","{"+ids+",\"kind\":\"ORIGINAL\",\"previousScenarioId\":\""+UUID.randomUUID()+"\"}")).isNotEmpty();
        var bad=new Scenario(UUID.randomUUID(),"ORIGINAL","PEER","기회","상황","친구","대사",new Scenario.Validation(false,true,false));
        assertThat(WebFixtures.schemaErrors("practice-scenario.schema.json",JsonCodec.shared().write(bad))).isNotEmpty();
    }
}
