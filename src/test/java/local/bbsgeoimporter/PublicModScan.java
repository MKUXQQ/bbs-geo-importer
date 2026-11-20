package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;
/** Public release is a test input only; no model mappings are supplied. */
public final class PublicModScan {
 public static void main(String[] args)throws Exception{
  try(var fs=FileSystems.newFileSystem(Path.of(args[0]),Map.of())){
   Path output=Path.of(args[1]);Files.createDirectories(output);Path run=Files.createTempDirectory(output,"run-");
   var sources=List.of(new GeoImporter.ModSource("unknown_public_"+UUID.randomUUID().toString().replace("-",""),"Unadapted Public Mod","release",List.of(fs.getPath("/"))));
   var importer=new GeoImporter(run,System.out::println);var first=importer.scan(sources);
   var report=ImporterAcceptance.json(run.resolve("bbs/model-import-report.json"));var counts=report.getAsJsonObject("modelCompleteness");
   if(first.discovered()==0||first.failed()!=0||!counts.has("COMPLETE")||counts.get("COMPLETE").getAsInt()==0)throw new AssertionError("Public release has no proven complete models: "+counts);
   var repeat=importer.scan(sources);if(repeat.imported()!=0||repeat.updated()!=0||repeat.skipped()!=first.imported())throw new AssertionError("Public release repeat changed models");
   System.out.println("PUBLIC MOD PASS: "+first+"; completeness="+counts+"; run="+run);
  }
 }
}
