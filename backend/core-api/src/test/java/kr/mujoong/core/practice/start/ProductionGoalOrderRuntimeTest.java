package kr.mujoong.core.practice.start;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProductionGoalOrderRuntimeTest {
    @Test void goalOrderingWorksWithoutOptionalRandomProviders() throws Exception {
        String classes=Path.of(WeightedGoalOrder.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String tests=Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "--limit-modules","java.base","-cp",classes+File.pathSeparator+tests,Probe.class.getName())
                .redirectErrorStream(true).start();
        boolean finished=process.waitFor(10,TimeUnit.SECONDS);
        if(!finished)process.destroyForcibly();
        assertThat(finished).isTrue();
        assertThat(process.exitValue()).withFailMessage(new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8)).isZero();
    }
    public static class Probe {
        public static void main(String[] args) {
            UUID guardian=UUID.randomUUID(), child=UUID.randomUUID(), goal=UUID.randomUUID();
            var selection=new WeightedGoalOrder.Selection(guardian,child,goal,"synthetic goal","ACCEPT_STOP",1,"synthetic criteria",1,false);
            var result=new WeightedGoalOrder().freeze(guardian,child,List.of(selection));
            if(result.size()!=1||!result.getFirst().goalSelectionId().equals(goal)||result.getFirst().weight()!=1)
                throw new AssertionError("Invalid goal ordering");
        }
    }
}
