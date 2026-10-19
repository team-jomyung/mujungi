package kr.mujoong.core.practicegoal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.identity.GuardianWriteLock;
import kr.mujoong.core.knowledge.*;
import kr.mujoong.core.luna.*;
import kr.mujoong.core.observability.EventLogger;
import kr.mujoong.core.practice.*;
import kr.mujoong.core.textai.*;
import org.junit.jupiter.api.*;

/** Synthetic integration: real PostgreSQL and Neo4j, fake AI only. */
@Tag("knowledge-graph") @Tag("goal-result") @Tag("scenario-generation") @Tag("db")
class GraphWorkflowIT {
  static final String PASSWORD = UUID.randomUUID().toString();
  static final org.testcontainers.containers.GenericContainer<?> NEO4J = new org.testcontainers.containers.GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("neo4j:5.26.31-community"))
      .withEnv("NEO4J_AUTH","neo4j/"+PASSWORD).withExposedPorts(7474)
      .withEnv("NEO4J_server_memory_heap_initial__size","256m")
      .withEnv("NEO4J_server_memory_heap_max__size","512m")
      .withEnv("NEO4J_server_memory_pagecache_size","128m")
      .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/").forPort(7474).forStatusCode(200));
  @BeforeAll static void startGraph(){ NEO4J.start(); }
  @AfterAll static void stopGraph(){ NEO4J.stop(); }
  @Test void realGraphSeedToU14CandidateToU10ApprovedVariantAndScopedDeletion() throws Exception {
    var f = new CandidateSeedReplayIT();
    f.setup();
    ChildKnowledgeService service = null;
    KnowledgeLedgerRepository ledger = null;
    Neo4jChildKnowledgeGraph graph = null;
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      String auth = "neo4j/"+PASSWORD;
      graph = new Neo4jChildKnowledgeGraph(URI.create("http://"+NEO4J.getHost()+":"+NEO4J.getMappedPort(7474)),auth,Duration.ofSeconds(2));
      graph.prepareSchema();
      SeedCatalog catalog;
      try(var source = getClass().getResourceAsStream("/knowledge/social-comm-poc.zip")) {
        catalog = new SeedArchiveLoader().load(source,"poc-v1");
      }
      assertThat(catalog.seeds()).hasSize(14);
      ledger = new KnowledgeLedgerRepository(f.db.jdbc());
      var access = mock(KnowledgeAccessPolicy.class);
      var lock = new GuardianWriteLock(f.db.jdbc());
      doAnswer(c->{ lock.lockActive(f.guardian); return null; }).when(access).requireAllowed(f.guardian,f.child);
      doAnswer(c->{ lock.statusForUpdate(f.guardian); return null; }).when(access).requireDeletionOwner(f.guardian,f.child);
      when(access.source(f.guardian,f.child,f.session)).thenReturn(new KnowledgeAccessPolicy.Source(List.of(),List.of("합성아이")));
      service = new ChildKnowledgeService(ledger,access,graph,f.db.tx().getTransactionManager(),f.clock,catalog,true,Duration.ofSeconds(2));
      assertThat(service.initializeChild(f.guardian,f.child)).isTrue();
      assertThat(service.seedSummaries(f.guardian,f.child)).hasSize(14);
      assertThat(graph.seeds(UUID.randomUUID())).isEmpty();

      var luna = mock(CoreLunaClient.class);
      var signature = f.pending().candidates().getFirst().structureSignature();
      Map<String,Object> output = new HashMap<>();
      output.put("outcome","GENERATED"); output.put("skillName","뜻 나누기");
      output.put("invariantSentence","서로의 뜻을 나눈다"); output.put("family","OTHER");
      output.put("preservationRule","의사를 전하는 구조를 유지한다");
      output.put("relationship","CLASSMATE"); output.put("situation","SCHOOL_GROUP");
      output.put("misreadCost","LOW"); output.put("recoverable",true);
      output.put("signature",Map.of("target_skill",signature.targetSkill(),"required_inference",signature.requiredInference(),
        "perspective_structure",signature.perspectiveStructure(),"decidable_by_evidence",signature.decidableByEvidence(),"direct_check_allowed",signature.directCheckAllowed()));
      when(luna.call(any(),any())).thenReturn(new LunaResult(JsonCodec.shared().write(output),"fake-model","seed-generation.v2",Duration.ZERO,1,false));
      var seeds = new SeedGenerationService(service,Optional.of(luna),new SeedContentValidator(new ForbiddenExpressionPolicy()),true,Duration.ofSeconds(10),"synthetic");
      var accounts = mock(PracticeAccountPort.class);
      when(accounts.requireActive(f.guardian,true)).thenAnswer(c->{lock.lockActive(f.guardian);return new PracticeAccountPort.AccountContext(f.guardian,f.child,null,List.of());});
      var delivery = new CandidateSeedDelivery(f.goals,accounts,f.consent,seeds,f.db.jdbc(),f.clock,mock(EventLogger.class),f.db.tx().getTransactionManager());
      delivery.deliver(f.guardian,f.child,f.session,f.result,f.pending());
      var linked = f.goals.candidates(f.child,f.session).getFirst();
      assertThat(linked.version()).isEqualTo(2);
      String skill = linked.content().skillRef();
      assertThat(skill).startsWith("seed:G");
      assertThat(graph.seeds(f.child)).hasSize(15);
      delivery.deliver(f.guardian,f.child,f.session,f.result,f.pending());
      verify(luna,times(1)).call(any(),any());
      assertThat(f.goals.candidates(f.child,f.session).getFirst().id()).isEqualTo(linked.id());
      assertThat(f.db.jdbc().queryForObject("select job_status from core.core_session_result_state where session_id=:s",Map.of("s",f.session),String.class)).isEqualTo("DONE");
      UUID saved = f.db.inTx(t->f.goals.select(f.guardian,linked,f.clock.now()));
      assertThat(f.goals.saved(f.child,saved).orElseThrow().skillRef()).isEqualTo(skill);

      var deadline = new ScenarioDeadline(f.clock,f.clock.now().plusSeconds(15));
      var context = new ScenarioGraphContext(service,workers,f.clock,Duration.ofSeconds(2)).open(f.guardian,f.child,skill,List.of(),deadline);
      var actual = context.context().orElseThrow();
      assertThat(actual.graphVersion()).isEqualTo("poc-v1");
      var seed = actual.seed();
      var baseContext = new VariantPlanner.Context("놀이","교실","아이와 친구","CLASSMATE","SCHOOL_GROUP");
      var plan = service.variantPlan(f.guardian,f.child,skill,baseContext,List.of()).orElseThrow();
      var base = scene("base","BASE",baseContext,seed,plan);
      var variant = scene("variant","VARIANT",plan.context(),seed,plan);
      assertThat(context.valid(base,variant)).isTrue();
      var provider = mock(ScenarioScriptProvider.class);
      when(provider.generate(any())).thenReturn(new ScenarioScriptProvider.Generated(new ScenarioScript(List.of(base,variant)),"fake"));
      when(provider.review(any())).thenReturn(new ScenarioScriptProvider.Reviewed(new ScenarioReview("APPROVE",List.of()),"fake-review"));
      var generator = new ScenarioScriptGenerator(provider,new ScenarioScriptValidation(new ForbiddenExpressionPolicy()),
        new TextAiProperties(Duration.ofSeconds(3),new TextAiProperties.Retry(1,Duration.ofMillis(250))),workers,f.clock::advance);
      var rules = new ScenarioScriptValidation.Rules("CLASSMATE","친구가 놀이를 준비한다","같이 놀자",Set.of(),null,x->false,context);
      var generated = generator.generate(new ScenarioScriptGenerator.Input(Map.of("structureContext",Map.of("skillRef",skill)),Map.of(),rules,true,"{}"),deadline);
      assertThat(generated.details().generation().graphContextUsed()).isTrue();
      assertThat(generated.details().scenes()).hasSize(2);
      assertThat(generated.details().review().verdict()).isEqualTo("APPROVE");

      service.deleteGeneratedSeedsForRecord(f.guardian,f.child,f.session);
      assertThat(graph.seeds(f.child)).hasSize(14);
      assertThat(service.skillContext(f.guardian,f.child,skill)).isEmpty();
      service.deleteChild(f.guardian,f.child);
      assertThat(graph.seeds(f.child)).isEmpty();
      assertThat(service.initializeChild(f.guardian,f.child)).isFalse();
      System.out.println("PASS U15 real graph 14+1 seeds; U14 versioned candidate and saved reference; U10 approved 2-scene graph variant; replay and deletion verified; fake AI only.");
    } finally {
      if (service != null) {
        try { service.deleteChild(f.guardian,f.child); } finally { service.close(); }
      }
      if(ledger != null) f.db.inTx(t->{ledgerCleanup(f);return null;});
      f.close();
    }
  }
  static void ledgerCleanup(CandidateSeedReplayIT f){
    var ledger = new KnowledgeLedgerRepository(f.db.jdbc());
    ledger.deleteLedger(f.child,null); ledger.forgetDeletedChildren(f.guardian);
  }
  static ScenarioScript.Scene scene(String key,String kind,VariantPlanner.Context c,KnowledgeSeed seed,VariantPlanner.Plan plan){
    return new ScenarioScript.Scene(key,kind,c.topic()+" 중 "+c.place()+"에서 친구가 놀이를 준비한다. 친구가 손짓한다.","친구가 손짓한다.","함께할 준비를 해.",List.of(),List.of(),c.relationship(),c.place(),c.topic(),
      new ScenarioScript.Preservation(seed.seedId(),seed.invariantSentence(),seed.preservationRule(),plan.grade(),plan.changedField(),c.participants(),c.situation()),null);
  }
}
