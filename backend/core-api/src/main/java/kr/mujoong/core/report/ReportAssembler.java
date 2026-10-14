package kr.mujoong.core.report;

import static kr.mujoong.core.result.ResultData.*;
import java.time.Duration;
import java.util.*;
import kr.mujoong.core.result.SceneChipMapper;
import org.springframework.stereotype.Component;

/** BR9.5·BR9.8·BR9.9: 관찰 기록을 집계하며 판정값·관심사·향상 수치는 만들지 않는다. */
@Component
public class ReportAssembler {
    public static final List<String> TITLES=List.of("활동 개요","관찰된 장면(인용)","연습 목표와 장면","아동 정보","최근 실제 경험과 출처","주요 대화 내용","살펴본 화용 행동","제공한 도움","과거 기록과의 차이","제한사항");
    public static final String LIMITATION="AI와의 연습 장면에서 본 기록이며 실제 친구 관계에서도 같은지는 알 수 없어요.";
    public Map<Integer,ReportRepository.Section> assemble(ReportRepository.Report report,List<ReportInput> inputs){
        Map<Integer,ReportRepository.Section> result=new TreeMap<>();
        long free=inputs.stream().filter(i->i.record().activityType().equals("FREE_TALK")).count();
        long seconds=inputs.stream().map(ReportInput::record).filter(r->r.recordFrom()!=null&&r.recordTo()!=null).mapToLong(r->Math.max(0,Duration.between(r.recordFrom(),r.recordTo()).getSeconds())).sum();
        result.put(1,section(1,report.periodFrom()+" ~ "+report.periodTo()+" · 자유대화 "+free+"회 · 연습 "+(inputs.size()-free)+"회 · 총 "+seconds/60+"분"));
        result.put(4,section(4,(report.childDisplayName()==null?"이름 없음":report.childDisplayName())+" · "+(report.childAge()==null?"나이 없음":report.childAge()+"세")));
        List<String> sceneLines=new ArrayList<>(),definitionLines=new ArrayList<>();Map<String,List<String>> history=new LinkedHashMap<>();Map<String,Set<UUID>> historySessions=new LinkedHashMap<>();
        int cues=0;Map<String,Integer> consent=new LinkedHashMap<>();String example=null;
        for(var input:inputs.stream().sorted(Comparator.comparing(i->i.record().listedAt())).toList()){
            var goals=goals(practice(input.record().practiceJson()));
            for(var d:input.definitions()){String line=text(d,"displayName")+" · "+text(d,"coreFunction");if(!definitionLines.contains(line))definitionLines.add(line);}
            for(var goal:goals){
                var help=objects(goal.get("cues"));cues+=help.size();
                for(var cue:help){String c=text(cue,"consentResponse");consent.merge(c,1,Integer::sum);if(example==null&&Boolean.FALSE.equals(cue.get("containsAnswerSentence"))&&!text(cue,"deliveredText").isBlank())example=text(cue,"deliveredText");}
                if(!input.done())continue;
                UUID id=uuid(goal.get("goalSelectionId"));String key=text(goal,"behaviorKey")+":"+number(goal,"behaviorVersion");if(key.equals(":0"))key=String.valueOf(id);
                var scenes=input.scenes().stream().filter(s->s.goalSelectionId().equals(id)).toList();if(scenes.isEmpty())continue;
                String line=text(goal,"displayName")+" · 장면 "+scenes.size()+"개 · "+String.join(" · ",scenes.stream().map(s->"장면 "+s.sceneNo()+" · "+SceneChipMapper.text(s.sceneChip(),s.notDoneReason())).toList())+" · 도움 "+help.size()+"회";
                sceneLines.add(line);history.computeIfAbsent(key,k->new ArrayList<>()).add(input.record().listedAt().atZone(ReportService.ZONE).toLocalDate()+" · "+line);historySessions.computeIfAbsent(key,k->new HashSet<>()).add(input.record().sessionId());
            }
        }
        result.put(3,section(3,String.join("\n",sceneLines)));result.put(7,section(7,String.join("\n",definitionLines)));
        result.put(8,section(8,"도움 "+cues+"회 · 동의함 "+consent.getOrDefault("ACCEPTED",0)+"회 · 거절함 "+consent.getOrDefault("DECLINED",0)+"회 · 답 없음 "+consent.getOrDefault("NO_RESPONSE",0)+"회"+(example==null?"":"\n예 · "+example)));
        List<String> past=new ArrayList<>();for(var e:history.entrySet())if(historySessions.get(e.getKey()).size()>=2)past.addAll(e.getValue());
        result.put(9,section(9,String.join("\n",past)));return result;
    }
    public String level(List<ReportInput> inputs){if(!inputs.isEmpty()&&inputs.stream().allMatch(i->i.done()&&i.result().level().equals("INSUFFICIENT")))return "INSUFFICIENT";return !inputs.isEmpty()&&inputs.stream().allMatch(i->i.done()&&i.result().level().equals("FULL"))?"FULL":"PARTIAL";}
    public static ReportRepository.Section section(int no,String text){boolean missing=text.isBlank();return new ReportRepository.Section(no,TITLES.get(no-1),missing?"자료 부족":text,missing,false,List.of());}
}
