package local.bbsgeoimporter;
import java.nio.file.*;

public final class Focused1515Acceptance {
    public static void main(String[] args)throws Exception{
        Path base=Files.createTempDirectory(Path.of("build"),"focused-1515-");
        EnumSelectorAcceptance.run(base.resolve("enum"));
        FactoryUvAcceptance.run(base.resolve("uv"));
        StartupOutputAcceptance.run(base.resolve("outputs"));
        LiveStartupAcceptance.run(base.resolve("live"));
        EarlyPublicationAcceptance.run(base.resolve("publication"));
        System.out.println("FOCUSED 1515 PASS: finite selectors, source-family isolation, output repair, startup and early BBS publication");
    }
}
