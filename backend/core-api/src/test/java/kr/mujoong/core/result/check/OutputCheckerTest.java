package kr.mujoong.core.result.check;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import kr.mujoong.core.result.ResultTextGateway;
import org.junit.jupiter.api.*;
@Tag("u6")
class OutputCheckerTest {
    ResultTextGateway gateway=mock(ResultTextGateway.class);OutputChecker checker=new OutputChecker(new ForbiddenDictionary(),gateway);Instant deadline=Instant.parse("2026-10-15T00:00:30Z");
    OutputChecker.Draft draft(String id,String text){return new OutputChecker.Draft(id,text,List.of("함께 놀자"),"자료 부족");}
    @Test void unverifiedQuoteFallsBackWithoutClassifier(){assertThat(checker.check(List.of(draft("s1","\"내가 좋아\"라고 했어요")),deadline)).containsEntry("s1","자료 부족");verifyNoInteractions(gateway);}
    @Test void numberAndDictionaryViolationsSkipClassifier(){assertThat(checker.check(List.of(draft("s1","90% 좋아졌어요"),draft("s2","성격이 좋아요")),deadline).values()).containsOnly("자료 부족");verifyNoInteractions(gateway);}
    @Test void allCleanTextsAreClassifiedInOneBatch(){when(gateway.call(eq("classify"),anyMap(),anyMap(),eq(deadline),any(Runnable.class))).thenReturn(Map.of("results",List.of(Map.of("id","s1","violation",false),Map.of("id","c1","violation",true))));assertThat(checker.check(List.of(draft("s1","\"함께 놀자\"라고 했어요"),draft("c1","이야기를 나눴어요")),deadline)).containsEntry("s1","\"함께 놀자\"라고 했어요").containsEntry("c1","자료 부족");verify(gateway,times(1)).call(eq("classify"),anyMap(),anyMap(),eq(deadline),any(Runnable.class));}
    @Test void missingClassificationFailsSchema(){when(gateway.call(anyString(),anyMap(),anyMap(),any(),any(Runnable.class))).thenReturn(Map.of("results",List.of()));assertThatThrownBy(()->checker.check(List.of(draft("s1","이야기를 나눴어요")),deadline)).hasMessage("SCHEMA_INVALID");}
    @Test void duplicateClassificationFailsSchema(){when(gateway.call(anyString(),anyMap(),anyMap(),any(),any(Runnable.class))).thenReturn(Map.of("results",List.of(Map.of("id","s1","violation",false),Map.of("id","s1","violation",false))));assertThatThrownBy(()->checker.check(List.of(draft("s1","이야기를 나눴어요")),deadline)).hasMessage("SCHEMA_INVALID");}
    @Test void emptyDraftsNeverCallAi(){assertThat(checker.check(List.of(),deadline)).isEmpty();verifyNoInteractions(gateway);}
}
