package kr.mujoong.core.activity;

import java.util.LinkedHashSet;
import java.util.List;
import kr.mujoong.contract.context.ConversationGuide;
import kr.mujoong.contract.context.ConversationGuide.Topic;
import kr.mujoong.contract.context.ConversationGuide.SourceType;

/** 원문은 Core에 보존하고 대화에는 허용 주제 코드만 보낸다. 입력의 명령·주장·개인정보는 복사하지 않는다. */
public final class GuardianConversationGuide {
    private GuardianConversationGuide() {}
    public static ConversationGuide from(StartActivityService.Context context) {
        if (context == null || ActivityContextRepository.isEmpty(context)) return null;
        var topics = new LinkedHashSet<Topic>();
        String text = (context.knownContent() == null ? "" : context.knownContent()) + " "
                + (context.question() == null ? "" : context.question());
        add(topics, text, Topic.BLOCKS, "블록", "레고");
        add(topics, text, Topic.DRAWING, "그림", "그리기");
        add(topics, text, Topic.SPORTS, "축구", "농구", "운동");
        add(topics, text, Topic.DINOSAURS, "공룡");
        if (context.situationType() != null) switch (context.situationType()) {
            case "FRIEND" -> topics.add(Topic.FRIEND);
            case "SCHOOL" -> topics.add(Topic.SCHOOL);
            case "FAMILY" -> topics.add(Topic.FAMILY);
            case "ACADEMY" -> topics.add(Topic.ACADEMY);
            default -> { }
        }
        add(topics, text, Topic.FRIEND, "친구");
        add(topics, text, Topic.SCHOOL, "학교");
        add(topics, text, Topic.FAMILY, "가족");
        add(topics, text, Topic.ACADEMY, "학원");
        add(topics, text, Topic.PLAY, "놀이");
        if (topics.isEmpty()) topics.add(Topic.DAILY);
        SourceType source = context.sourceType() == null ? SourceType.UNSPECIFIED : SourceType.valueOf(context.sourceType());
        return new ConversationGuide(topics.stream().limit(3).toList(), source);
    }
    private static void add(LinkedHashSet<Topic> topics, String text, Topic topic, String... words) {
        for (String word : words) if (text.contains(word)) { topics.add(topic); return; }
    }
}
