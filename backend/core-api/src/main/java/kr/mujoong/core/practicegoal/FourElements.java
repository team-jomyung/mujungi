package kr.mujoong.core.practicegoal;

/** Immutable internal goal context; never returned in a parent response. */
public record FourElements(String situation,String partner,String intent,String expression) {
    public FourElements {
        require(situation,500);require(partner,200);require(intent,500);require(expression,500);
    }
    private static void require(String value,int maximum){
        if(value==null||value.isBlank()||value.codePointCount(0,value.length())>maximum)throw new IllegalArgumentException("Invalid goal context");
    }
    @Override public String toString(){return "FourElements[redacted]";}
}
