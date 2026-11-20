package local.bbsgeoimporter;
import java.nio.file.*;import java.util.*;
/** One startup census, transferred from reload preparation to the import worker. */
final class SourceCensus {
 record View(Map<Path,List<Path>> files,Map<Path,String> kinds,Set<Path> failed){}
 private static final ThreadLocal<Map<Path,List<Path>>> files=new ThreadLocal<>();
 private static final ThreadLocal<Map<Path,String>> kinds=new ThreadLocal<>();
 private static final ThreadLocal<Set<Path>> failed=ThreadLocal.withInitial(HashSet::new);
 static void begin(){failed.get().clear();files.set(new HashMap<>());kinds.set(new HashMap<>());}
 static void end(){files.remove();kinds.remove();failed.remove();}
 static boolean failed(Path root){return failed.get().contains(root);}
 static boolean incomplete(){return !failed.get().isEmpty();}
 static void readFailed(Path file){var memo=files.get();if(memo!=null)for(Path root:memo.keySet())if(root.getFileSystem()==file.getFileSystem()&&file.startsWith(root))failed.get().add(root);}
 static View snapshot(){return new View(files.get()==null?Map.of():Map.copyOf(files.get()),kinds.get()==null?Map.of():Map.copyOf(kinds.get()),Set.copyOf(failed.get()));}
 static void resume(View view){begin();if(view!=null){files.get().putAll(view.files());kinds.get().putAll(view.kinds());failed.get().addAll(view.failed());}}
 static List<Path> files(Path root)throws java.io.IOException{
  var memo=files.get();if(memo!=null&&memo.containsKey(root)){Performance.count("source.censusReuse",0);return memo.get(root);}
  long start=Performance.start();List<Path> result;try(var walk=Files.walk(root)){result=walk.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList();}catch(java.io.IOException|RuntimeException ex){failed.get().add(root);throw ex;}
  if(memo!=null)memo.put(root,result);Performance.event("source.census",System.nanoTime()-start,result.size());return result;
 }
 static String kind(String path,Path file,java.util.function.Supplier<String> classify){var memo=kinds.get();if(memo==null)return classify.get();return memo.computeIfAbsent(file,k->classify.get());}
}
