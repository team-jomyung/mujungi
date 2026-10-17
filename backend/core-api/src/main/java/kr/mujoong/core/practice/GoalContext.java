package kr.mujoong.core.practice;

import kr.mujoong.core.practicegoal.FourElements;
import kr.mujoong.core.practicegoal.SavedGoalReader.SavedGoalView;
import kr.mujoong.core.practicegoal.SavedGoalReader.SavedGoalView.Kind;

/** 담은 버전의 종류별 내용. 후보는 행동 카탈로그 항목이 아니다(U10-a). */
public record GoalContext(Kind kind,String candidateRef,Integer candidateVersion,String targetExpression,
        FourElements fourElements,String skillRef,String legacyBehaviorKey,Integer legacyBehaviorVersion) {
    public GoalContext {
        if(kind==null)kind=Kind.LEGACY_BEHAVIOR;
        if(kind==Kind.CANDIDATE&&(candidateRef==null||!candidateRef.matches("[A-Za-z0-9_-]{1,80}")
                ||candidateVersion==null||candidateVersion<1||targetExpression==null||targetExpression.isBlank()
                ||fourElements==null||skillRef==null||skillRef.isBlank()))throw new IllegalArgumentException("Invalid candidate snapshot");
        if(kind==Kind.LEGACY_BEHAVIOR&&(legacyBehaviorKey==null||legacyBehaviorKey.isBlank()
                ||legacyBehaviorVersion==null||legacyBehaviorVersion<1))throw new IllegalArgumentException("Invalid legacy snapshot");
    }
    public static GoalContext legacy(String key,int version){return new GoalContext(Kind.LEGACY_BEHAVIOR,null,null,null,null,"NONE",key,version);}
    public static GoalContext from(SavedGoalView view){return new GoalContext(view.kind(),view.candidateRef(),view.candidateVersion(),view.targetExpression(),view.fourElements(),view.skillRef(),view.legacyBehaviorKey(),view.legacyBehaviorVersion());}
    public boolean candidate(){return kind==Kind.CANDIDATE;}
    /** 내용만 정리하고 담은 원본의 불투명 식별자·판은 보존한다. */
    public GoalContext mapContent(java.util.function.UnaryOperator<String> clean){
        if(!candidate())return this;
        return new GoalContext(kind,candidateRef,candidateVersion,clean.apply(targetExpression),
                new FourElements(clean.apply(fourElements.situation()),clean.apply(fourElements.partner()),
                        clean.apply(fourElements.intent()),clean.apply(fourElements.expression())),
                skillRef,legacyBehaviorKey,legacyBehaviorVersion);
    }
    public String carrierKey(){return candidate()?"candidate:"+candidateRef:legacyBehaviorKey;}
    public int carrierVersion(){return candidate()?candidateVersion:legacyBehaviorVersion;}
    public String criteria(){
        if(!candidate())throw new IllegalStateException("Legacy criteria belong to pinned definition");
        return "상황: "+fourElements.situation()+" / 상대: "+fourElements.partner()+" / 의도: "+fourElements.intent()+" / 표현: "+fourElements.expression();
    }
    @Override public String toString(){return "GoalContext[kind="+kind+", content=***]";}
}
