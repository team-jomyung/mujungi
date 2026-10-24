package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import kr.mujoong.realtime.voice.ProviderClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 실음성 연습 대화 하니스 — C안 '무중이 직접 묻기' 5회차 실측(docs/practice-direct-ask.md 11장). 운영 세션 실행부({@code SessionExecutor} · 게이트 ·
 * 출력 보류 · 승인 원문 대조 · 입력 전사 묶기 · 무응답 시계 협력, {@link PracticeFlowRouter} → {@link PracticeConversation})에 실제 GPT-Live 음성,
 * 운영 Decisions 경로의 실제 Luna 판정, 제브(env가 모두 있으면 운영 {@code JevGuardrailClient}, 아니면 대역)를 붙이고, 가상 기기와 합성 아이(하늘이,
 * OpenAI TTS 음성)가 대화한다. 결과는 '아이가 실제로 들은 말'의 대화 기록이다.
 *
 * <ul>
 *   <li>기본 {@code test} · {@code check} · CI에서는 돌지 않는다({@code @Tag("gpt-live-practice")}, build-logic 규약이 기본 test에서 빼고 키도
 *       지운다, 루트 {@code verifyGptLivePracticeIsolation}이 확인한다). 사람이 env {@code OPENAI_API_KEY}를 넣고
 *       {@code ./gradlew :realtime-activity:gptLivePracticeTest}로만 돈다. 키가 없으면 출력 파일에 {@value #SKIPPED}만 쓰고 건너뛴다(통과 아님).
 *   <li>입력: 출력 폴더의 core 하니스 결과({@code practice-start-packages/<목표>.json} · {@code core-practice-packages.json}). 없으면 시험 고정
 *       대본 하나로 돌고 그 사실을 적는다. 고정 승인 문장(approvedSpeech)은 저장소 시드 004 · 006에서 읽는다.
 *   <li>출력: 시스템 속성 {@value #OUTPUT_PROPERTY} → env {@value #OUTPUT_ENV} → {@code build/live-eval/} 폴더에 {@value #OUTPUT_FILE}(전체 기록)과
 *       {@value #MARKDOWN_FILE}(사람이 읽는 대화 기록 · 요약 표). 지난 실호출 결과는 지우지 않고 {@value #PREVIOUS_DIR}{@code /}로 옮긴다.
 *   <li>상한 · 계획 · 목표는 env로 고른다({@link PracticeVoiceHarness} 상수). 상한을 넘으면 진행 중 대화를 끝내고 남은 대화는 건너뛴 대화로 적는다.
 *   <li>증거 종류: '실제 API(GPT-Live · Luna · 제브 또는 제브 대역) + 합성 아이 음성'. 실제 기기 · 실제 아이 결과가 아니다(R-M11). 콘솔에는 개수와 파일
 *       위치만 쓴다(R-F14). 키 · 요청 머리 · 주소는 어떤 출력에도 쓰지 않는다(R-F3). 운영 코드는 바꾸지 않았다.
 * </ul>
 */
@Tag(PracticeVoiceDialogueLiveTest.TAG)
class PracticeVoiceDialogueLiveTest {

    static final String TAG = "gpt-live-practice";
    static final String OUTPUT_FILE = "practice-voice-dialogues.json";
    static final String MARKDOWN_FILE = "practice-voice-dialogues.md";
    static final String SKIPPED = "SKIPPED_NO_KEY";
    static final String PREVIOUS_DIR = "previous-run";
    static final String OUTPUT_PROPERTY = "mujoong.live.eval.out";
    static final String OUTPUT_ENV = "MUJOONG_LIVE_EVAL_OUT";

    @Test
    void practiceVoiceDialoguesRunTheProductionSessionEngineWithRealProvidersAndRecordWhatTheChildHeard() throws IOException {
        Path out = outputDirectory();
        boolean keyPresent = keyPresent();
        if (!keyPresent) {
            writeSkipped(out);
        }
        assumeTrue(keyPresent, SKIPPED + ": env OPENAI_API_KEY 없음(실호출 안 함, 통과 아님)");

        PracticeVoiceHarness.Outcome outcome = new PracticeVoiceHarness(out, PracticeVoiceHarness.Wiring.live(System::getenv)).run();

        // 콘솔에는 개수와 파일 위치만 쓴다(대화 원문은 파일에만, R-F14)
        System.out.println(outcome.consoleLine());
        assertThat(outcome.ranDialogues()).as("시작한 대화 수(" + outcome.file() + ")").isPositive();
        assertThat(outcome.gptLiveSessions()).as("GPT-Live가 세션을 시작한 대화 수(" + outcome.file() + ")").isPositive();
    }

    // ------------------------------------------------------------------ 출력 위치 · 키 · 저장소

    /** 출력 폴더: 시스템 속성 → env → build/live-eval(core 하니스와 같은 규칙, 같은 폴더를 쓰면 core 결과를 입력으로 읽는다). */
    static Path outputDirectory() {
        String property = System.getProperty(OUTPUT_PROPERTY);
        String env = System.getenv(OUTPUT_ENV);
        String chosen = property != null && !property.isBlank() ? property : env != null && !env.isBlank() ? env : "build/live-eval";
        return Path.of(chosen).toAbsolutePath().normalize();
    }

    /** 키가 있는지만 본다. 값은 변수에 담지 않는다. */
    static boolean keyPresent() {
        String value = System.getenv(ProviderClient.API_KEY);
        return value != null && !value.isBlank();
    }

    /** 저장소 루트(시험 작업이 넘기는 mujoong.repo.root, 없으면 모듈 폴더의 두 단계 위). */
    static Path repoRoot() {
        return Path.of(System.getProperty("mujoong.repo.root", "../..")).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ 키 없음 · 지난 결과

    private static final String SKIPPED_JSON = "{\"outcome\":\"" + SKIPPED + "\"}";
    private static final String SKIPPED_MARKDOWN = "# 연습 음성 대화 기록" + System.lineSeparator() + System.lineSeparator() + SKIPPED
            + ": env OPENAI_API_KEY 없음(실호출 안 함, 통과 아님)";

    /** 키 없음: 출력 파일에 SKIPPED_NO_KEY만 쓴다. 지난 실호출 결과가 있으면 덮기 전에 previous-run/으로 옮긴다. */
    static void writeSkipped(Path out) throws IOException {
        Files.createDirectories(out);
        movePreviousResult(out);
        Files.writeString(out.resolve(OUTPUT_FILE), SKIPPED_JSON + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve(MARKDOWN_FILE), SKIPPED_MARKDOWN + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    /** 지난 실호출 결과(키 없음 표시가 아닌 것)는 지우지 않고 previous-run/으로 옮긴다. */
    static void movePreviousResult(Path out) throws IOException {
        for (String name : List.of(OUTPUT_FILE, MARKDOWN_FILE)) {
            Path current = out.resolve(name);
            if (!Files.isRegularFile(current)) {
                continue;
            }
            String content = Files.readString(current, StandardCharsets.UTF_8).strip();
            if (!content.equals(SKIPPED_JSON) && !content.equals(SKIPPED_MARKDOWN.strip())) {
                Path previous = Files.createDirectories(out.resolve(PREVIOUS_DIR));
                Files.move(current, previous.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
