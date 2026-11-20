package local.bbsgeoimporter;
import java.nio.file.*;
public final class Focused1517Acceptance {
    public static void main(String[] args)throws Exception{
        Path base=Files.createTempDirectory(Path.of("build"),"focused-1517-");
        StartupGateAcceptance.run(base.resolve("gate"));
        StartupOutputAcceptance.run(base.resolve("outputs"));
        SourceToggleStartupAcceptance.run(base.resolve("disabled"));
        PerformanceAcceptance.run(base.resolve("performance"));
        ModelManagementAcceptance.run(base.resolve("settings"));
        System.out.println("FOCUSED 1517 PASS: startup gate/output repair, real BBS settings and source visibility");
    }
}
