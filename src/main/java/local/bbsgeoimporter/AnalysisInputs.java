package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;
/** One bounded scan lifetime: shared inputs are released when publication completes. */
final class AnalysisInputs {
    private static final ThreadLocal<Map<Path,byte[]>> cache=new ThreadLocal<>();
    static void begin(){cache.set(new HashMap<>());}
    static void end(){cache.remove();}
    static byte[] bytes(Path p)throws java.io.IOException{var values=cache.get();if(values==null)return Files.readAllBytes(p);byte[] result=values.get(p);if(result==null){long start=Performance.start();result=Files.readAllBytes(p);Performance.event(p.toString().endsWith(".class")?"analysis.classRead":"analysis.resourceRead",System.nanoTime()-start,result.length);values.put(p,result);}return result;}
    static String text(Path p)throws java.io.IOException{return new String(bytes(p),java.nio.charset.StandardCharsets.UTF_8);}
}
