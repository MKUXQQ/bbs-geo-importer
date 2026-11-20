package local.bbsgeoimporter;
import java.nio.file.*;import java.util.*;import java.util.jar.*;import javax.script.*;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;import org.objectweb.asm.tree.analysis.*;
/** Runs the shipped coremod script against the actual installed addon bytes. */
public final class CataclysmCompatAcceptance {
 static final String OLD="com/github/L_Ender/cataclysm/client/render/entity/Ancient_Remnant_Rework_Renderer", NEW=OLD.replace("Rework_","");
 static ClassNode read(JarFile jar,String name)throws Exception{ClassNode n=new ClassNode();new ClassReader(jar.getInputStream(jar.getJarEntry(name+".class"))).accept(n,0);return n;}
 public static void main(String[] args)throws Exception{
  Path mods=Path.of("E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods");Path cat;try(var stream=Files.list(mods)){cat=stream.filter(p->p.getFileName().toString().contains("L_Enders_Cataclysm-3.31")).findFirst().orElseThrow();}
  try(JarFile addon=new JarFile(mods.resolve("cataclysm_spellbooks-1.2.9-1.20.1-all.jar").toFile());JarFile library=new JarFile(cat.toFile())){
   if(library.getJarEntry(OLD+".class")!=null||library.getJarEntry(NEW+".class")==null)throw new AssertionError("Expected 3.31 rename fixture");
   var renderer=read(library,NEW);if(renderer.methods.stream().noneMatch(m->m.name.equals("<init>")&&m.desc.equals("(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;)V")))throw new AssertionError("Context constructor incompatible");
   var layer=read(library,"com/github/L_Ender/cataclysm/client/render/layer/Ancient_Remnant_Layer");if(layer.methods.stream().noneMatch(m->m.name.equals("<init>")&&m.desc.equals("(L"+NEW+";)V")))throw new AssertionError("Layer constructor incompatible");
   var engine=new ScriptEngineManager().getEngineByName("nashorn");if(engine==null)throw new AssertionError("Nashorn unavailable");engine.eval(Files.readString(Path.of("src/forge/resources/coremods/cataclysm_renderer_331.js")));Object config=((Invocable)engine).invokeFunction("initializeCoreMod");engine.put("config",config);
   for(String key:List.of("phantom_remnant_renderer","phantom_remnant_layer")){String target=(String)engine.eval("String(config['"+key+"'].target.name)");ClassNode n=read(addon,target.replace('.','/'));int methodCount=n.methods.size(),instructions=n.methods.stream().mapToInt(m->m.instructions.size()).sum();engine.put("node",n);engine.eval("config['"+key+"'].transformer(node)");
    if(n.methods.size()!=methodCount||n.methods.stream().mapToInt(m->m.instructions.size()).sum()!=instructions)throw new AssertionError("Original rendering instructions removed");
    for(MethodNode m:n.methods)if((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new Analyzer<>(new BasicVerifier()).analyze(n.name,m);
    ClassWriter out=new ClassWriter(0);n.accept(out);byte[] bytes=out.toByteArray();if(new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1).contains(OLD))throw new AssertionError("Stale superclass/descriptor remains");
    engine.eval("config['"+key+"'].transformer(node)");ClassWriter twice=new ClassWriter(0);n.accept(twice);if(!Arrays.equals(bytes,twice.toByteArray()))throw new AssertionError("Not idempotent");
    if(key.equals("phantom_remnant_renderer")&&!n.superName.equals(NEW))throw new AssertionError("Parent not adapted");
    System.out.println("CATACLYSM COMPAT PASS: "+target+"; original instructions="+instructions+"; no old type; verifier and idempotence passed");
   }
  }
 }
}
