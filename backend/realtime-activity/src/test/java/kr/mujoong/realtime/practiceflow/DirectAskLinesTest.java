package kr.mujoong.realtime.practiceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.mujoong.realtime.practiceflow.PracticeScript.Scene;
import kr.mujoong.realtime.practiceflow.PracticeFlowTypes.SceneKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * C안 장면 안 말 시험 (docs/practice-direct-ask.md 3.2 · 5장 · 7.4). 고정 문장은 시작 패키지 approvedSpeech에서 오고 없으면 같은 문장의 상수로
 * 대신하며, 칸이 없으면(옛 대본 v1 · core가 뺀 칸) 7.4 대체 문장을 상대 호칭별(계약 10.18 표)로 쓰고, 이어 읽는 문장은 문장부호 규칙(마침표 ·
 * 쉼표 · 물음표)을 지키는지 본다. 문장은 시험용 가짜 값이다.
 */
@Tag("practice-flow")
class DirectAskLinesTest {

    private static final DirectAskLines PACKAGE = new DirectAskLines(PracticeScriptFixtures.FIXED_SPEECH);
    private static final DirectAskLines EMPTY = new DirectAskLines(Map.of());

    /** C안 판 v2 장면(새 칸 다섯 개 + 상황 안내). */
    private static Scene v2() {
        return new Scene("base-1", SceneKind.BASE, "친구와 그림을 그린다.", "손이 좀 아파서 오래 못 그릴 것 같아.",
                "친구가 손이 아파서 그림을 오래 그리기 힘들대.", "같은 반 친구", List.of("친구 마음을 먼저 물어보기"),
                List.of("손 괜찮아?"), "친구", "교실", "그림 그리기", "친구랑 그림을 그리는 이야기야.", null,
                "그런데 친구가 손이 아프대.", "너라면 친구한테 뭐라고 말할래?", "친구 손이 아프다고 했지? 어떤 말을 해 줄까?",
                "친구 마음을 먼저 물어보면 돼. 예를 들면 손 괜찮아처럼 말할 수 있어. 네 말로 해 볼래?",
                "그렇게 말해 주면 친구도 고마워할 거야.");
    }

    /** 옛 대본 v1 장면: 새 칸 없음. 기회 문장에 따옴표 · 느낌표 · 말줄임표가 있다. */
    private static Scene v1(List<String> focusPoints, List<String> examples) {
        return new Scene("base-1", SceneKind.BASE, "친구가 상자 두 개를 들고 있어.", "“그거 줘! 빨리…”",
                "친구는 빨간 상자와 파란 상자를 들고 있어", "같은 반 친구", focusPoints, examples, "친구", "교실", "물건 부탁");
    }

    @Test
    void theFixedSentencesAreTheDesignTableAndTheFixtureSendsTheSameTextsAsTheConstants() {
        assertThat(DirectAskLines.FIXED).containsExactlyInAnyOrderEntriesOf(Map.of(
                "HELP_OFFER", "무중이가 조금 도와줄까?",
                "VARIANT_OFFER", "비슷한 이야기로 한 번 더 해 볼까?",
                "RETRY_PROMPT", "네 말로 한 번 말해 볼래?",
                "INPUT_CONFIRM", "잘 못 들었어. 한 번 더 말해 줄래?",
                "SCENE_CLOSE", "알겠어. 이 이야기는 여기까지 할게.",
                "WAIT_LEAD", "천천히 생각해도 돼.",
                "ASK_DEFAULT", "너라면 친구한테 뭐라고 말할래?"));
        assertThat(PracticeScriptFixtures.FIXED_SPEECH).isEqualTo(DirectAskLines.FIXED);
    }

    @Test
    void theConstantsAreWordForWordTheContractTablesOfSection1018() throws java.io.IOException {
        java.nio.file.Path repo = java.nio.file.Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();
        String doc = java.nio.file.Files.readString(repo.resolve("contracts/ai-io.md"), java.nio.charset.StandardCharsets.UTF_8);
        String section = doc.substring(doc.indexOf("### 10.18 "));

        for (Map.Entry<String, String> fixed : DirectAskLines.FIXED.entrySet()) {
            assertThat(section).as(fixed.getKey())
                    .containsPattern("\\| `" + fixed.getKey() + "`(\\(\\d+\\))? \\| " + java.util.regex.Pattern.quote(fixed.getValue()) + " \\|");
        }
        assertThat(section).contains("| `spokenIntroduction` | " + DirectAskLines.INTRODUCTION_FALLBACK + " |");
        assertThat(section).contains("| `opportunityRelay` | " + DirectAskLines.RELAY_LEAD + " + `opportunitySentence` |");
        assertThat(section).contains("| `askPrompt` | 고정 `ASK_DEFAULT` |");
        assertThat(section).contains("| `reaskPrompt` | " + DirectAskLines.REASK_FALLBACK + " |");
        assertThat(section).contains("| `helpSpeech` | `focusPoints[0]` + \"" + DirectAskLines.EXAMPLE_LEAD + "\" + `expressionExamples[0]` + \""
                + DirectAskLines.EXAMPLE_TAIL + " " + DirectAskLines.HELP_TAIL + "\" |");
        assertThat(section).contains("| `friendReaction` | " + DirectAskLines.FRIEND_REACTION_FALLBACK + " |");
        // 상대 호칭별 대체 표: 전하기 앞머리 | 묻기 | 반응(친구 행의 묻기는 고정 ASK_DEFAULT)
        for (DirectAskLines.Partner partner : DirectAskLines.Partner.values()) {
            String ask = partner == DirectAskLines.Partner.FRIEND ? "고정 `ASK_DEFAULT`" : partner.ask();
            assertThat(section).as(partner.name()).contains("| " + partner.relayLead() + " + `opportunitySentence` | " + ask + " | "
                    + partner.reaction() + " |");
        }
    }

    /** relation만 다른 옛 대본 장면(새 칸 없음). */
    private static Scene v1(String relation) {
        return new Scene("base-1", SceneKind.BASE, "상자가 두 개 있어.", "그거 줘.", "상자가 두 개 있어.", "역할", List.of(), List.of(),
                relation, "교실", "물건 부탁");
    }

    @Test
    void theFallbackTitleFollowsTheRelationTable() {
        record Row(String relation, String relay, String ask, String reaction) {
        }
        String friendAsk = "너라면 친구한테 뭐라고 말할래?";
        for (Row row : List.of(
                new Row("TEACHER", "선생님이 이렇게 말씀하셨어. 그거 줘.", "너라면 선생님께 뭐라고 말씀드릴래?", "그렇게 말씀드리면 선생님도 네 마음을 아실 거야."),
                new Row("CLUB_SENIOR", "선배가 이렇게 말했어. 그거 줘.", "너라면 선배한테 뭐라고 말할래?", "그렇게 말해 주면 선배도 네 마음을 알 거야."),
                new Row("CLUB_JUNIOR", "동생이 이렇게 말했어. 그거 줘.", "너라면 동생한테 뭐라고 말할래?", "그렇게 말해 주면 동생도 네 마음을 알 거야."),
                new Row("FAMILY", "그때 이렇게 말했어. 그거 줘.", "너라면 뭐라고 말할래?", "그렇게 말해 주면 네 마음이 잘 전해질 거야."))) {
            Scene scene = v1(row.relation());
            assertThat(EMPTY.relay(scene)).as(row.relation()).isEqualTo(row.relay());
            assertThat(PACKAGE.ask(scene)).as(row.relation() + " 묻기는 시드 ASK_DEFAULT가 아니라 호칭별 상수").isEqualTo(row.ask());
            assertThat(EMPTY.friendReaction(scene)).as(row.relation()).isEqualTo(row.reaction());
            assertThat(EMPTY.opportunity(scene)).isEqualTo(row.relay() + " " + row.ask());
        }
        for (String friend : List.of("FRIEND", "FRIEND_CLOSE", "FRIEND_CASUAL", "FRIEND_PRIVATE", "CLASSMATE", "PEER", "PEER_RETURN",
                "CLUB_PEER", "TEAMMATE", "친구", "모둠 친구", "동아리 친구", "UNKNOWN_CODE", "")) {
            Scene scene = v1(friend);
            assertThat(EMPTY.relay(scene)).as(friend + "는 지금 친구 문장 그대로").isEqualTo("친구가 이렇게 말했어. 그거 줘.");
            assertThat(EMPTY.ask(scene)).as(friend).isEqualTo(friendAsk);
            assertThat(EMPTY.friendReaction(scene)).as(friend).isEqualTo(DirectAskLines.FRIEND_REACTION_FALLBACK);
        }
        assertThat(new DirectAskLines(Map.of("ASK_DEFAULT", "너라면 친구한테 어떻게 말할래?")).ask(v1("PEER")))
                .as("친구 장면의 묻기만 시드 승인 문장을 따른다").isEqualTo("너라면 친구한테 어떻게 말할래?");
    }

    @Test
    void aRelationNameWithTheSameTitleWordUsesThatTitle() {
        assertThat(DirectAskLines.Partner.of("담임 선생님")).isEqualTo(DirectAskLines.Partner.TEACHER);
        assertThat(DirectAskLines.Partner.of("보조 교사")).isEqualTo(DirectAskLines.Partner.TEACHER);
        assertThat(DirectAskLines.Partner.of("동아리 선배")).isEqualTo(DirectAskLines.Partner.SENIOR);
        assertThat(DirectAskLines.Partner.of("동아리 후배")).isEqualTo(DirectAskLines.Partner.JUNIOR);
        assertThat(DirectAskLines.Partner.of("옆집 동생")).isEqualTo(DirectAskLines.Partner.JUNIOR);
        assertThat(DirectAskLines.Partner.of("가족")).isEqualTo(DirectAskLines.Partner.NEUTRAL);
        assertThat(DirectAskLines.Partner.of(" TEACHER ")).as("앞뒤 공백").isEqualTo(DirectAskLines.Partner.TEACHER);
        assertThat(DirectAskLines.Partner.of(null)).isEqualTo(DirectAskLines.Partner.FRIEND);
    }

    /** core가 그래프 코드로 바꾸지 않은 목표의 관계 이름(5회차 S10 '동아리 형' 같은 값)도 표의 호칭을 고른다. */
    @Test
    void aClubElderNameIsASeniorAndOtherElderOrFamilyNamesGetNoTitle() {
        for (String senior : List.of("동아리 형", "동아리 누나", "동아리 언니", "동아리오빠")) {
            assertThat(DirectAskLines.Partner.of(senior)).as(senior).isEqualTo(DirectAskLines.Partner.SENIOR);
        }
        assertThat(DirectAskLines.Partner.of("동아리 동생")).isEqualTo(DirectAskLines.Partner.JUNIOR);
        for (String neutral : List.of("형", "옆집 누나", "같은 반 언니", "사촌 오빠", "엄마", "아빠", "부모님", "할머니", "할아버지")) {
            assertThat(DirectAskLines.Partner.of(neutral)).as(neutral + "는 가족일 수도 있어 호칭을 붙이지 않는다")
                    .isEqualTo(DirectAskLines.Partner.NEUTRAL);
        }
        for (String friend : List.of("동아리 친구", "모둠 친구", "같은 반 친구", "친한 친구")) {
            assertThat(DirectAskLines.Partner.of(friend)).as(friend).isEqualTo(DirectAskLines.Partner.FRIEND);
        }

        Scene clubElder = v1("동아리 형");
        assertThat(EMPTY.opportunity(clubElder)).isEqualTo("선배가 이렇게 말했어. 그거 줘. 너라면 선배한테 뭐라고 말할래?");
        assertThat(EMPTY.friendReaction(clubElder)).isEqualTo("그렇게 말해 주면 선배도 네 마음을 알 거야.");
        Scene mother = v1("엄마");
        assertThat(EMPTY.opportunity(mother)).isEqualTo("그때 이렇게 말했어. 그거 줘. 너라면 뭐라고 말할래?");
        assertThat(EMPTY.friendReaction(mother)).isEqualTo("그렇게 말해 주면 네 마음이 잘 전해질 거야.");
    }

    @Test
    void scriptFieldsWinOverTheTitleFallback() {
        Scene teacher = new Scene("base-1", SceneKind.BASE, "선생님과 그림을 그린다.", "오늘은 여기까지 하자.", "선생님이 그림을 그만하재.",
                "역할", List.of(), List.of(), "TEACHER", "교실", "그림", null, null, "그런데 선생님이 오늘은 여기까지 하자고 하셨어.",
                "너라면 선생님께 뭐라고 말씀드릴래?", null, null, "그렇게 말씀드리면 선생님도 기뻐하실 거야.");

        assertThat(PACKAGE.relay(teacher)).isEqualTo("그런데 선생님이 오늘은 여기까지 하자고 하셨어.");
        assertThat(PACKAGE.friendReaction(teacher)).isEqualTo("그렇게 말씀드리면 선생님도 기뻐하실 거야.");
        assertThat(PACKAGE.reask(teacher)).as("되묻기 대체는 호칭이 없다").isEqualTo(DirectAskLines.REASK_FALLBACK);
    }

    @Test
    void aFixedSentenceComesFromThePackageAndAMissingOrBlankCodeFallsBackToTheConstant() {
        Map<String, String> approved = new HashMap<>();
        approved.put("HELP_OFFER", "무중이가 도와줄까?");
        approved.put("SCENE_CLOSE", "  ");
        DirectAskLines lines = new DirectAskLines(approved);

        assertThat(lines.fixed("HELP_OFFER")).as("시드 승인 문장이 앞선다").isEqualTo("무중이가 도와줄까?");
        assertThat(lines.fixed("SCENE_CLOSE")).isEqualTo("알겠어. 이 이야기는 여기까지 할게.");
        assertThat(lines.fixed("RETRY_PROMPT")).isEqualTo("네 말로 한 번 말해 볼래?");
        assertThat(new DirectAskLines(null).fixed("WAIT_LEAD")).isEqualTo("천천히 생각해도 돼.");
        assertThatThrownBy(() -> EMPTY.fixed("COACH_SWITCH")).isInstanceOf(NullPointerException.class);
        approved.put("RETRY_PROMPT", "바뀐 문장");
        assertThat(lines.fixed("RETRY_PROMPT")).as("만든 뒤 바뀐 지도의 값을 읽지 않는다").isEqualTo("네 말로 한 번 말해 볼래?");
    }

    @Test
    void aV2SceneIsReadFieldByFieldAndTwoSentencesShareOneSlot() {
        Scene scene = v2();

        assertThat(PACKAGE.introduction(scene)).isEqualTo("친구랑 그림을 그리는 이야기야.");
        assertThat(PACKAGE.opportunity(scene)).isEqualTo("그런데 친구가 손이 아프대. 너라면 친구한테 뭐라고 말할래?");
        assertThat(PACKAGE.reask(scene)).isEqualTo("친구 손이 아프다고 했지? 어떤 말을 해 줄까?");
        assertThat(PACKAGE.reexplanation(scene)).as("다시 설명 뒤 묻는 말을 다시 읽는다")
                .isEqualTo("친구가 손이 아파서 그림을 오래 그리기 힘들대. 너라면 친구한테 뭐라고 말할래?");
        assertThat(PACKAGE.waitAndAsk(scene)).isEqualTo("천천히 생각해도 돼. 너라면 친구한테 뭐라고 말할래?");
        assertThat(PACKAGE.waitAndReask(scene)).isEqualTo("천천히 생각해도 돼. 친구 손이 아프다고 했지? 어떤 말을 해 줄까?");
        assertThat(PACKAGE.help(scene)).isEqualTo(new DirectAskLines.Help(
                "친구 마음을 먼저 물어보면 돼. 예를 들면 손 괜찮아처럼 말할 수 있어. 네 말로 해 볼래?", true));
        assertThat(PACKAGE.friendReaction(scene)).isEqualTo("그렇게 말해 주면 친구도 고마워할 거야.");
    }

    @Test
    void anOldV1SceneUsesTheFallbackSentencesOfTheDesign() {
        Scene scene = v1(List.of("어느 것인지 말하기"), List.of("“파란 상자를 줘.”"));

        assertThat(EMPTY.introduction(scene)).isEqualTo("상상 속 이야기를 떠올려 봐. 무중이가 이야기를 들려줄게.");
        assertThat(EMPTY.relay(scene)).as("기회 문장을 이으며 따옴표 · 느낌표 · 말줄임표를 덜어 낸다")
                .isEqualTo("친구가 이렇게 말했어. 그거 줘. 빨리.");
        assertThat(EMPTY.ask(scene)).as("묻는 말은 고정 ASK_DEFAULT").isEqualTo("너라면 친구한테 뭐라고 말할래?");
        assertThat(EMPTY.opportunity(scene)).isEqualTo("친구가 이렇게 말했어. 그거 줘. 빨리. 너라면 친구한테 뭐라고 말할래?");
        assertThat(EMPTY.reask(scene)).isEqualTo("조금 더 자세히 말해 줄래?");
        assertThat(EMPTY.reexplanation(scene)).as("마침표가 없는 다시 설명에도 마침표를 채운다")
                .isEqualTo("친구는 빨간 상자와 파란 상자를 들고 있어. 너라면 친구한테 뭐라고 말할래?");
        assertThat(EMPTY.help(scene)).isEqualTo(new DirectAskLines.Help(
                "어느 것인지 말하기. 예를 들면 파란 상자를 줘처럼 말할 수 있어. 네 말로 해 볼래?", true));
        assertThat(EMPTY.friendReaction(scene)).isEqualTo("그렇게 말해 주면 친구도 네 마음을 알 거야.");
    }

    @Test
    void anOldSceneWithoutFocusPointsOrExamplesStillHelpsWithTheReexplanationAndRecordsNoExample() {
        DirectAskLines.Help bare = EMPTY.help(v1(List.of(), List.of()));
        assertThat(bare.text()).isEqualTo("친구는 빨간 상자와 파란 상자를 들고 있어. 네 말로 해 볼래?");
        assertThat(bare.exampleGiven()).isFalse();

        DirectAskLines.Help blankExample = EMPTY.help(v1(List.of(" ", "요점"), List.of("  ")));
        assertThat(blankExample.text()).as("빈 요점은 건너뛴다").isEqualTo("요점. 네 말로 해 볼래?");
        assertThat(blankExample.exampleGiven()).isFalse();
    }

    @Test
    void joinFillsAFullStopOnlyWhereASentenceDoesNotEndAndSkipsEmptyParts() {
        assertThat(DirectAskLines.join("하나", "둘?")).isEqualTo("하나. 둘?");
        assertThat(DirectAskLines.join("하나?", "둘")).isEqualTo("하나? 둘");
        assertThat(DirectAskLines.join("하나,", "둘")).isEqualTo("하나, 둘");
        assertThat(DirectAskLines.join(" 하나. ", null, "", "  ", "둘")).isEqualTo("하나. 둘");
        assertThat(DirectAskLines.join()).isEmpty();
    }

    @Test
    void plainKeepsOnlyTheAllowedPunctuation() {
        assertThat(DirectAskLines.plain("“그거 줘!” (빨리) ~ 알았지…")).isEqualTo("그거 줘. 빨리 알았지.");
        assertThat(DirectAskLines.plain("'좋아'... 그래  !")).isEqualTo("좋아. 그래 .");
        assertThat(DirectAskLines.plain(null)).isEmpty();
    }

    @Test
    void theUnheardRestStartsAtTheSentenceThatHoldsTheCut() {
        String intro = "너는 친구랑 공룡 그림을 같이 그리는 중이야. 친구한테 같이 그리자고 했어.";
        String second = "친구한테 같이 그리자고 했어.";
        int secondStart = intro.indexOf(second);

        assertThat(DirectAskLines.unheardRest(intro, "너는".length())).as("첫 단위 '너는'에서 끊기면 첫 문장부터 전체").isEqualTo(intro);
        assertThat(DirectAskLines.unheardRest(intro, 0)).isEqualTo(intro);
        assertThat(DirectAskLines.unheardRest(intro, secondStart + "친구한테".length())).as("둘째 문장 중간이면 둘째 문장부터")
                .isEqualTo(second);
        assertThat(DirectAskLines.unheardRest(intro, intro.indexOf(". ") + 1)).as("문장 끝 부호까지 들렸으면 다음 문장부터")
                .isEqualTo(second);
        assertThat(DirectAskLines.unheardRest(intro, intro.indexOf(". ") + 2)).as("문장 사이 공백에서 끊겨도 다음 문장부터")
                .isEqualTo(second);
        assertThat(DirectAskLines.unheardRest(intro, intro.indexOf(". "))).as("마침표 바로 앞이면 그 문장부터")
                .isEqualTo(intro);
    }

    @Test
    void anUnheardRestWithoutWordsIsNoRestAndSentenceEndsIncludeRunsAndClosingQuotes() {
        String ask = "그런데 친구가 손이 아프대. 너라면 친구한테 뭐라고 말할래?";
        assertThat(DirectAskLines.unheardRest(ask, ask.length())).as("다 들렸다").isNull();
        assertThat(DirectAskLines.unheardRest(ask, ask.length() - 1)).as("말끝 물음표만 남았다: 다시 읽을 말이 없다").isNull();
        assertThat(DirectAskLines.unheardRest(ask, ask.length() + 5)).as("원문보다 긴 위치는 끝으로 본다").isNull();
        assertThat(DirectAskLines.unheardRest(ask, -1)).as("음수는 처음으로 본다").isEqualTo(ask);
        assertThat(DirectAskLines.unheardRest(null, 0)).isNull();

        String legacy = "친구가 이렇게 말했어. “그만해!?” 너라면 뭐라고 말할래?";
        assertThat(DirectAskLines.unheardRest(legacy, legacy.indexOf("너라면") + 1)).as("부호 묶음 · 닫는 따옴표까지 앞 문장이다")
                .isEqualTo("너라면 뭐라고 말할래?");
        assertThat(DirectAskLines.unheardRest(legacy, legacy.indexOf("그만해") + 1)).isEqualTo("“그만해!?” 너라면 뭐라고 말할래?");
    }

    @Test
    void theStringFormsNeverCarryTheSentences() {
        assertThat(PACKAGE.toString()).isEqualTo("DirectAskLines[approvedCodes=7]");
        assertThat(PACKAGE.help(v2()).toString()).doesNotContain("친구").contains("exampleGiven=true");
    }

    // ---- 목표 안내 승인 문장 (편차 FT-1024-g) ----

    @Test
    void theGoalBriefPicksTheCopulaByTheLastSyllablesFinalConsonantAndDropsTheQuotes() {
        // 받침 없음 → '야'
        assertThat(DirectAskLines.goalBrief("부탁하기", 1, 1)).isEqualTo(PracticeScriptFixtures.V2_GOAL_BRIEF);
        assertThat(DirectAskLines.goalBrief("돌려 말한 거절 알기", 1, 1)).startsWith("오늘 연습할 목표는 돌려 말한 거절 알기야. ");
        // 받침 있음(ㅇ · ㄹ · ㄴ) → '이야'
        assertThat(DirectAskLines.goalBrief("모르는 친구에게 설명", 1, 1)).isEqualTo("오늘 연습할 목표는 모르는 친구에게 설명이야. "
                + "이 목표로 짧게 이야기 연습을 하고 오늘 연습을 마무리할 거야. 필요하면 도움을 받아도 되고, 언제든 쉬거나 끝내도 돼.");
        assertThat(DirectAskLines.goalBrief("차례 지키며 말", 1, 1)).startsWith("오늘 연습할 목표는 차례 지키며 말이야. ");
        assertThat(DirectAskLines.goalBrief("고마운 마음 전하는 인사", 1, 1)).startsWith("오늘 연습할 목표는 고마운 마음 전하는 인사야. ");
        assertThat(DirectAskLines.goalBrief("친구 마음 헤아려 말하기", 1, 1)).doesNotContain("‘", "’", "'");
        // 생성형 목표 안내와 같은 문장이고 목표 이름을 감싸던 작은따옴표만 없다(목표 하나)
        assertThat(PracticeInstructions.goalBrief("부탁하기", 1, 1, true))
                .contains(PracticeScriptFixtures.V2_GOAL_BRIEF.replace("부탁하기야.", "‘부탁하기’야."));
    }

    @Test
    void severalGoalsAreCountedInWordsAndTheSentencesKeepTheDirectAskPunctuationRule() {
        assertThat(DirectAskLines.goalBrief("부탁하기", 1, 2)).isEqualTo("오늘 연습할 목표는 부탁하기야. 오늘 고른 목표는 두 개이고, 지금은 첫 번째야. "
                + "이 목표로 짧게 이야기 연습을 해 볼 거야. 필요하면 도움을 받아도 되고, 언제든 쉬거나 끝내도 돼.");
        assertThat(DirectAskLines.goalBrief("거절하기", 2, 2)).contains("두 개이고, 지금은 두 번째야.");
        assertThat(DirectAskLines.goalBrief("사과하기", 3, 3)).contains("세 개이고, 지금은 세 번째야.");
        for (int count = 1; count <= 3; count++) {
            for (int index = 1; index <= count; index++) {
                String brief = DirectAskLines.goalBrief("모르는 친구에게 설명", index, count);
                assertThat(brief).as(index + "/" + count).isNotNull().doesNotContainPattern("[0-9]")
                        .matches("[가-힣 .,?]+").endsWith(DirectAskLines.GOAL_BRIEF_TAIL);
                assertThat(brief.split("(?<=[.?]) ")).as("문장 수(목표 하나 3, 여럿 4)").hasSize(count == 1 ? 3 : 4);
            }
        }
    }

    @Test
    void goalNamesAreTidiedButNeverRewritten() {
        assertThat(DirectAskLines.goalName("‘부탁하기’")).isEqualTo("부탁하기");
        assertThat(DirectAskLines.goalName("\"거절하기\"")).isEqualTo("거절하기");
        assertThat(DirectAskLines.goalName("  부탁\t하기  ")).as("공백 · 제어 문자는 공백 하나로").isEqualTo("부탁 하기");
        assertThat(DirectAskLines.goalName(java.text.Normalizer.normalize("부탁하기", java.text.Normalizer.Form.NFD)))
                .as("풀어 쓴 한글은 조합형으로").isEqualTo("부탁하기");
        assertThat(DirectAskLines.goalName("가".repeat(DirectAskLines.GOAL_NAME_MAX))).hasSize(DirectAskLines.GOAL_NAME_MAX);
    }

    @Test
    void aGoalNameTheApprovedSentenceCannotHoldGivesNoSentenceSoTheFlowFallsBackToTheGeneratedBrief() {
        java.util.List<String> unusable = new java.util.ArrayList<>(java.util.Arrays.asList(null, "", "   ", "‘’", "3단계 부탁하기",
                "OK 말하기", "부탁하기!", "부탁하기?", "부탁하기.", "부탁, 거절", "부탁·거절", "부탁(친구)", "부탁~", "ㅂㅌ하기", "부탁😊",
                "가".repeat(DirectAskLines.GOAL_NAME_MAX + 1)));
        for (String name : unusable) {
            assertThat(DirectAskLines.goalName(name)).as(String.valueOf(name)).isNull();
            assertThat(DirectAskLines.goalBrief(name, 1, 1)).as(String.valueOf(name)).isNull();
        }
        // 목표 수 · 순서가 1 ~ 3 밖(연습 시작 요청의 목표 상한 3)
        assertThat(DirectAskLines.goalBrief("부탁하기", 1, 4)).isNull();
        assertThat(DirectAskLines.goalBrief("부탁하기", 0, 1)).isNull();
        assertThat(DirectAskLines.goalBrief("부탁하기", 3, 2)).isNull();
        assertThat(DirectAskLines.goalBrief("부탁하기", 1, 0)).isNull();
    }

    @Test
    void theGoalBriefFrameIsWordForWordTheContractTableOfSection1018() throws java.io.IOException {
        java.nio.file.Path repo = java.nio.file.Path.of(System.getProperty("mujoong.repo.root", "..")).toAbsolutePath().normalize();
        String doc = java.nio.file.Files.readString(repo.resolve("contracts/ai-io.md"), java.nio.charset.StandardCharsets.UTF_8);
        String section = doc.substring(doc.indexOf("### 10.18 "));

        assertThat(section).contains("`" + DirectAskLines.GOAL_BRIEF_LEAD + "{목표 이름}이야.`")
                .contains("`" + DirectAskLines.GOAL_BRIEF_LEAD + "{목표 이름}야.`")
                .contains("| 둘째 문장(목표 하나) | " + DirectAskLines.GOAL_BRIEF_ONLY + " |")
                .contains("| 둘째 문장(목표 둘 · 셋) | 오늘 고른 목표는 {두 · 세} 개이고, 지금은 {첫 · 두 · 세} 번째야. "
                        + DirectAskLines.GOAL_BRIEF_ONE_OF + " |")
                .contains("| 끝 문장 | " + DirectAskLines.GOAL_BRIEF_TAIL + " |")
                .contains("한글 음절과 띄어쓰기만 " + DirectAskLines.GOAL_NAME_MAX + "자 이하");
    }
}
