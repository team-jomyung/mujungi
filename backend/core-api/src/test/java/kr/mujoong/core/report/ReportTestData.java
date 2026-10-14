package kr.mujoong.core.report;
import java.time.*;
import java.util.*;
import kr.mujoong.contract.json.JsonCodec;
import kr.mujoong.core.record.ActivityRecordRepository.StoredRecord;
import kr.mujoong.core.result.*;
final class ReportTestData {
    static final Instant NOW=Instant.parse("2026-10-14T01:00:00Z");
    static ReportInput input(int no,String type,int lines,String status,String level){
        UUID session=UUID.randomUUID();List<Map<String,Object>> transcript=new ArrayList<>();for(int i=1;i<=lines;i++)transcript.add(Map.of("seq",i,"speaker","CHILD","text",i==1?"응":"함께 블록 쌓자","startedAt",NOW.minusSeconds(no).toString(),"uncertain",false));
        var record=new StoredRecord(session,UUID.randomUUID(),UUID.randomUUID(),type,"ENDED","CHILD_STOP",true,NOW.minusSeconds(120),NOW,NOW.minusSeconds(no),NOW.minusSeconds(120),NOW,JsonCodec.shared().write(transcript),"[]",null,"hash",NOW);
        var result=new ActivityResultRepository.Result(UUID.randomUUID(),session,1,UUID.randomUUID(),level,"[]","[]","[]",null,"{}","[]",NOW,NOW,null);
        return new ReportInput(no,record,status,result,List.of(),Map.of(),List.of());
    }
    static ReportInput practice(int no,String status,String level,UUID goal,List<Map<String,Object>> cues){
        var base=input(no,"PRACTICE",3,status,level);var r=base.record();var p=Map.of("goals",List.of(Map.of("goalSelectionId",goal.toString(),"behaviorKey","ask","behaviorVersion",1,"displayName","부탁하기","cues",cues)));
        var record=new StoredRecord(r.sessionId(),r.guardianId(),r.childId(),r.activityType(),r.finalStatus(),r.endReason(),r.confirmed(),r.startedAt(),r.endedAt(),r.listedAt(),r.recordFrom(),r.recordTo(),r.transcriptJson(),r.gapsJson(),JsonCodec.shared().write(p),r.snapshotHash(),r.recordedAt());
        var scene=new SceneJudgmentRepository.Scene(goal,1,1,"ORIGINAL",null,"functional","IMMEDIATE","비공개 근거","none",true,null,"DONE_IMMEDIATE");
        return new ReportInput(no,record,status,base.result(),List.of(scene),Map.of(),List.of(Map.of("displayName","부탁하기","coreFunction","필요한 것을 부탁함")));
    }
    static ReportRepository.Report report(){return new ReportRepository.Report(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"key","hash","GENERATING",LocalDate.of(2026,10,1),LocalDate.of(2026,10,14),2,0,null,null,"하늘",7,NOW,null,null,null);}
}
