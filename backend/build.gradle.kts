// 무중 백엔드 루트 빌드 (U1 3.5 Step 3)
// 모듈 공통 규약(Java 21 툴체인 · 컴파일 옵션 · BOM · 시험 · JaCoCo 80%)은
// build-logic의 mujoong.java-conventions 플러그인이 모듈마다 건다(J105).
// 루트는 clean · check · assemble 수명 주기와 빌드 구성 확인 작업만 가진다.
plugins {
    base
}

// ---- Luna 실호출 시험 분리 확인 (U9 SD8 · BR9.3 · NFR5.1 · 인프라 검토 R-05) ----
// build-logic에는 시험 디렉터리가 없어 포함 빌드 시험이 CI(clean check)에서 돌지 않을 수 있으므로, check에 물리는 확인 작업으로 둔다.
// 세 가지를 단언한다: check 작업 그래프에 lunaLiveTest 없음, 기본 test 제외 목록에 luna-live 있음, lunaLiveTest 포함 목록 = luna-live.
// 다른 프로젝트 작업의 의존을 실행 단계(병렬)에서 풀 수 없으므로 작업 그래프가 정해질 때 계산하고 doLast는 결과만 판정한다.
val lunaLiveTag = "luna-live"
val lunaLiveProblems = mutableListOf<String>()
val lunaLiveModules = mutableListOf<String>()

val verifyLunaLiveIsolation = tasks.register("verifyLunaLiveIsolation") {
    group = "verification"
    description = "lunaLiveTest가 check · 기본 test와 분리돼 있는지 확인한다(U9 BR9.3)."
    doLast {
        if (lunaLiveProblems.isNotEmpty()) {
            throw GradleException("Luna 실호출 시험 분리 위반: ${lunaLiveProblems.joinToString("; ")}")
        }
        logger.lifecycle(
            "verifyLunaLiveIsolation: 모듈 ${lunaLiveModules.sorted()} — check 그래프에 lunaLiveTest 없음, " +
                "test는 ${lunaLiveTag} 제외, lunaLiveTest는 ${lunaLiveTag}만",
        )
    }
}

tasks.named("check") {
    dependsOn(verifyLunaLiveIsolation)
}

gradle.taskGraph.whenReady {
    if (!hasTask(verifyLunaLiveIsolation.get())) {
        return@whenReady
    }
    val modules = subprojects.filter { it.tasks.findByName("lunaLiveTest") != null }
    lunaLiveModules += modules.map { it.name }
    val missing = setOf("contract-model", "core-api", "realtime-activity") - lunaLiveModules.toSet()
    if (missing.isNotEmpty()) {
        lunaLiveProblems += "lunaLiveTest 작업이 없는 모듈: $missing"
    }
    val reachable = mutableSetOf<Task>()
    val pending = ArrayDeque(listOf(tasks.getByName("check")) + subprojects.mapNotNull { it.tasks.findByName("check") })
    while (pending.isNotEmpty()) {
        val task = pending.removeFirst()
        if (reachable.add(task)) {
            pending.addAll(task.taskDependencies.getDependencies(task))
        }
    }
    reachable.filter { it.name == "lunaLiveTest" }.forEach { lunaLiveProblems += "check 그래프에 ${it.path}가 있다" }
    for (module in modules) {
        val test = module.tasks.getByName("test") as Test
        val live = module.tasks.getByName("lunaLiveTest") as Test
        val testOptions = test.options as JUnitPlatformOptions
        val liveOptions = live.options as JUnitPlatformOptions
        if (lunaLiveTag !in testOptions.excludeTags) {
            lunaLiveProblems += "${test.path} 제외 목록에 ${lunaLiveTag} 없음(${testOptions.excludeTags})"
        }
        if (liveOptions.includeTags != setOf(lunaLiveTag)) {
            lunaLiveProblems += "${live.path} 포함 목록이 ${lunaLiveTag} 하나가 아님(${liveOptions.includeTags})"
        }
        if (test.environment.containsKey("OPENAI_API_KEY")) {
            lunaLiveProblems += "${test.path}가 OPENAI_API_KEY를 시험 JVM에 넘긴다"
        }
    }
}

// ---- GPT-Live 실호출 시험 분리 확인 (U11 FR9.1 · BR9.1 · 기능 설계 W7) ----
// lunaLiveTest와 같은 방식이다. 네 가지를 단언한다: check · test 작업 그래프에 gptLiveTest 없음, realtime-activity에 gptLiveTest가 있고
// 포함 목록 = gpt-live, 모든 모듈의 기본 test 제외 목록에 gpt-live 있음, 기본 test가 PROVIDER_API_KEY를 시험 JVM에 넘기지 않음.
val gptLiveTag = "gpt-live"
val gptLiveProblems = mutableListOf<String>()
val gptLiveModules = mutableListOf<String>()

val verifyGptLiveIsolation = tasks.register("verifyGptLiveIsolation") {
    group = "verification"
    description = "gptLiveTest가 check · 기본 test와 분리돼 있는지 확인한다(U11 BR9.1)."
    doLast {
        if (gptLiveProblems.isNotEmpty()) {
            throw GradleException("GPT-Live 실호출 시험 분리 위반: ${gptLiveProblems.joinToString("; ")}")
        }
        logger.lifecycle(
            "verifyGptLiveIsolation: 모듈 ${gptLiveModules.sorted()} — check · test 그래프에 gptLiveTest 없음, " +
                "모든 test는 ${gptLiveTag} 제외 · PROVIDER_API_KEY 없음, gptLiveTest는 ${gptLiveTag}만",
        )
    }
}

tasks.named("check") {
    dependsOn(verifyGptLiveIsolation)
}

gradle.taskGraph.whenReady {
    if (!hasTask(verifyGptLiveIsolation.get())) {
        return@whenReady
    }
    val modules = subprojects.filter { it.tasks.findByName("gptLiveTest") != null }
    gptLiveModules += modules.map { it.name }
    if ("realtime-activity" !in gptLiveModules) {
        gptLiveProblems += "realtime-activity에 gptLiveTest 작업이 없다"
    }
    val reachable = mutableSetOf<Task>()
    val roots = listOf(tasks.getByName("check")) +
        subprojects.mapNotNull { it.tasks.findByName("check") } +
        subprojects.mapNotNull { it.tasks.findByName("test") }
    val pending = ArrayDeque(roots)
    while (pending.isNotEmpty()) {
        val task = pending.removeFirst()
        if (reachable.add(task)) {
            pending.addAll(task.taskDependencies.getDependencies(task))
        }
    }
    reachable.filter { it.name == "gptLiveTest" }.forEach { gptLiveProblems += "check · test 그래프에 ${it.path}가 있다" }
    for (module in subprojects) {
        val test = module.tasks.findByName("test") as? Test ?: continue
        val testOptions = test.options as JUnitPlatformOptions
        if (gptLiveTag !in testOptions.excludeTags) {
            gptLiveProblems += "${test.path} 제외 목록에 ${gptLiveTag} 없음(${testOptions.excludeTags})"
        }
        if (test.environment.containsKey("PROVIDER_API_KEY")) {
            gptLiveProblems += "${test.path}가 PROVIDER_API_KEY를 시험 JVM에 넘긴다"
        }
    }
    for (module in modules) {
        val live = module.tasks.getByName("gptLiveTest") as Test
        val liveOptions = live.options as JUnitPlatformOptions
        if (liveOptions.includeTags != setOf(gptLiveTag)) {
            gptLiveProblems += "${live.path} 포함 목록이 ${gptLiveTag} 하나가 아님(${liveOptions.includeTags})"
        }
    }
}
