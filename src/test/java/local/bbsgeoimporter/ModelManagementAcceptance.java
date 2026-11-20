package local.bbsgeoimporter;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Real BBS Settings/Builder/ValueBoolean/L10n/persistence and UI label methods; render-only dependencies are seams. */
public final class ModelManagementAcceptance {
    public static void main(String[] args)throws Exception{run(Files.createTempDirectory(Path.of("build"),"settings-1517-"));}
    @SuppressWarnings("unchecked")
    static void run(Path base)throws Exception{
        Path fixtures=base.resolve("classes");Files.createDirectories(fixtures);
        Path bbs=Path.of("E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.7-1.20.1.jar");
        Map<String,String> stubs=new LinkedHashMap<>();
        stubs.put("mchorse/bbs_mod/BBSMod","public class BBSMod {public static final mchorse.bbs_mod.settings.SettingsManager settings=new mchorse.bbs_mod.settings.SettingsManager();public static mchorse.bbs_mod.settings.SettingsManager getSettings(){return settings;}public static mchorse.bbs_mod.resources.AssetProvider getProvider(){return new mchorse.bbs_mod.resources.AssetProvider();}}");
        stubs.put("mchorse/bbs_mod/BBSModClient","public class BBSModClient {public static final mchorse.bbs_mod.l10n.L10n l10n=new mchorse.bbs_mod.l10n.L10n();public static mchorse.bbs_mod.l10n.L10n getL10n(){return l10n;}}");
        stubs.put("mchorse/bbs_mod/resources/Link","public class Link {public static Link assets(String path){return new Link();}}");
        stubs.put("mchorse/bbs_mod/resources/AssetProvider","public class AssetProvider {public java.io.InputStream getAsset(Link link){return new java.io.ByteArrayInputStream(\"{}\".getBytes(java.nio.charset.StandardCharsets.UTF_8));}}");
        stubs.put("mchorse/bbs_mod/ui/utils/icons/Icon","public class Icon {}");
        stubs.put("mchorse/bbs_mod/ui/utils/icons/Icons","public class Icons {public static final Icon FOLDER=new Icon();}");
        stubs.put("mchorse/bbs_mod/settings/SettingsThread","public class SettingsThread {public static int queued;public static synchronized void add(Settings settings){queued++;}}");
        stubs.put("mchorse/bbs_mod/utils/IOUtils","public class IOUtils {public static String readText(java.io.InputStream in)throws java.io.IOException{return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}public static String readText(java.io.File f)throws java.io.IOException{return java.nio.file.Files.readString(f.toPath());}public static void writeText(java.io.File f,String s)throws java.io.IOException{java.nio.file.Files.writeString(f.toPath(),s);}}");
        stubs.put("mchorse/bbs_mod/utils/keyframes/factories/KeyframeFactories","public class KeyframeFactories {public static final BooleanKeyframeFactory BOOLEAN=new BooleanKeyframeFactory();}");
        List<String> javac=new ArrayList<>(List.of("-proc:none","-encoding","UTF-8","-cp",bbs.toString(),"-d",fixtures.toString()));
        for(var entry:stubs.entrySet()){
            Path file=fixtures.resolve(entry.getKey()+".java");Files.createDirectories(file.getParent());int slash=entry.getKey().lastIndexOf('/');
            Files.writeString(file,"package "+entry.getKey().substring(0,slash).replace('/','.')+";"+entry.getValue());javac.add(file.toString());
        }
        check(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,javac.toArray(String[]::new))==0,"settings CPU seams compile");
        try(JarFile archive=new JarFile(bbs.toFile())){
            ClassNode factory=new ClassNode();new ClassReader(archive.getInputStream(archive.getJarEntry("mchorse/bbs_mod/settings/ui/UIValueFactory.class"))).accept(factory,0);
            Set<String> keyMethods=Set.of("getTitleKey","getCategoryTitleKey","getCategoryTooltipKey","getValueLabelKey","getValueCommentKey");
            factory.methods.removeIf(method->!keyMethods.contains(method.name));
            ClassWriter writer=new ClassWriter(0);factory.accept(writer);Path file=fixtures.resolve(factory.name+".class");Files.createDirectories(file.getParent());Files.write(file,writer.toByteArray());
            ClassNode overlay=new ClassNode();new ClassReader(archive.getInputStream(archive.getJarEntry("mchorse/bbs_mod/settings/ui/UISettingsOverlayPanel.class"))).accept(overlay,0);
            boolean modules=false;for(MethodNode method:overlay.methods)for(AbstractInsnNode instruction:method.instructions)
                if(instruction instanceof FieldInsnNode field&&field.owner.equals("mchorse/bbs_mod/settings/SettingsManager")&&field.name.equals("modules"))modules=true;
            check(modules,"actual settings overlay enumerates registered modules");
        }
        Path config=base.resolve("first/config"),file=config.resolve("bbs/mod-model-management.json");Files.createDirectories(file.getParent());
        Files.writeString(file,"{\"sources\":{\"historic_absent\":false,\"cold_source\":false}}");
        Performance.reset();ModelManagement.configure(config);long reads=count("settings.configRead");
        List<String> toggles=new ArrayList<>();
        com.google.gson.JsonObject state=new com.google.gson.JsonObject(),mods=new com.google.gson.JsonObject();state.add("mods",mods);
        for(String id:List.of("source_a","source_b")){com.google.gson.JsonObject mod=new com.google.gson.JsonObject(),files=new com.google.gson.JsonObject();mod.addProperty("directory",id);mod.add("files",files);mods.add(id,mod);for(String suffix:List.of("main","skins/one","skins/two")){com.google.gson.JsonObject record=new com.google.gson.JsonObject();record.addProperty("target",suffix+"/model.geo.json");record.addProperty("writtenHash","fixture");files.add(suffix,record);}}
        ImportedModelGroups.update(state);Object cachedManager=new Object();ImportedModelGroups.filterAvailable(cachedManager,List.of("user/custom","source_a/user/custom"));
        Map<String,SharedModelAssets.Binding> sceneBindings=Map.copyOf(SharedModelAssets.bindingsForTest());
        ModelManagement.onChanged((id,on)->{toggles.add(id+"="+on);ImportedModelGroups.setSourceEnabled(id,on);});
        var sources=List.of(new ModelManagement.SourceInfo("source_a","测试 A","1",700,true),new ModelManagement.SourceInfo("source_b","测试 B","2",19,true),new ModelManagement.SourceInfo("cold_source","未准备来源","3",24,false),new ModelManagement.SourceInfo("external:bundle@v1","同名资源包","v1",2,true),new ModelManagement.SourceInfo("external:bundle@v2","同名资源包","v2",3,true));
        ModelManagement.publishManifest(sources);
        try(URLClassLoader loader=new URLClassLoader(new URL[]{fixtures.toUri().toURL(),bbs.toUri().toURL()},ModelManagementAcceptance.class.getClassLoader())){
            check(ModelManagement.registerOnClient(loader),"real BBS module registration: "+ModelManagement.diagnostics());
            Object manager=loader.loadClass("mchorse.bbs_mod.BBSMod").getMethod("getSettings").invoke(null);
            Map<String,Object> modules=(Map<String,Object>)manager.getClass().getField("modules").get(manager);Object module=modules.get(ModelManagement.MODULE_ID);
            check(module!=null,"module is in actual BBS SettingsManager UI registry");
            Object l10n=loader.loadClass("mchorse.bbs_mod.BBSModClient").getMethod("getL10n").invoke(null);
            Class<?> baseValue=loader.loadClass("mchorse.bbs_mod.settings.values.base.BaseValue"),uiFactory=loader.loadClass("mchorse.bbs_mod.settings.ui.UIValueFactory");
            String title=(String)uiFactory.getMethod("getTitleKey",baseValue).invoke(null,module);
            check(label(l10n,title).equals("Mod 模型管理"),"real UI title key resolves Chinese module title");
            Object a=module.getClass().getMethod("get",String.class,String.class).invoke(module,"sources","source_a");
            Object b=module.getClass().getMethod("get",String.class,String.class).invoke(module,"sources","source_b");
            Object cold=module.getClass().getMethod("get",String.class,String.class).invoke(module,"sources","cold_source");
            Object historical=module.getClass().getMethod("get",String.class,String.class).invoke(module,"sources","historic_absent");
            check(!(boolean)baseValue.getMethod("isVisible").invoke(historical)&&!ModelManagement.enabled("historic_absent"),"removed-source switch is hidden and retained");
            String coldKey=(String)uiFactory.getMethod("getValueLabelKey",baseValue).invoke(null,cold);
            check(label(l10n,coldKey).contains("24 个 Geo")&&!label(l10n,coldKey).contains("下次启动加载"),"manifest quantity comes from memory and disabled row is clear");
            check(label(l10n,"bbs_geo_models.config.sources.external:bundle@v1").contains("v1")&&label(l10n,"bbs_geo_models.config.sources.external:bundle@v2").contains("v2"),"same-name external packages keep distinct id/version rows");
            Object category=((Map<?,?>)module.getClass().getField("categories").get(module)).get("sources");
            List<?> values=(List<?>)category.getClass().getMethod("getAll").invoke(category);int visible=0;
            for(Object value:values)if((boolean)baseValue.getMethod("isVisible").invoke(value))visible++;
            check(visible==sources.size(),"one visible native Boolean row per current source");
            a.getClass().getMethod("toggle").invoke(a);
            check(!ModelManagement.enabled("source_a")&&ModelManagement.enabled("source_b")&&Boolean.TRUE.equals(b.getClass().getMethod("get").invoke(b)),"toggle affects only chosen source");
            var hiddenKeys=ImportedModelGroups.cachedAvailable(cachedManager);
            check(hiddenKeys.stream().noneMatch(k->k.startsWith("source_a/main")||k.startsWith("source_a/skins"))&&hiddenKeys.contains("source_b/main")&&hiddenKeys.contains("user/custom")&&hiddenKeys.contains("source_a/user/custom"),"real switch hides all managed variants from native search and keeps B/user models");
            check(!ImportedModelGroups.previewAllowed("source_a/skins/one")&&SharedModelAssets.bindingsForTest().equals(sceneBindings),"disabled list previews blocked while scene bindings remain untouched");
            a.getClass().getMethod("toggle").invoke(a);cold.getClass().getMethod("toggle").invoke(cold);
            check(ImportedModelGroups.cachedAvailable(cachedManager).contains("source_a/skins/two"),"real switch restores prepared variants from memory");
            check(ModelManagement.enabled("source_a")&&ModelManagement.pendingRestart("cold_source")&&label(l10n,coldKey).contains("下次启动加载"),"re-enable prepared source is immediate, unprepared source says next startup");
            check(toggles.equals(List.of("source_a=false","source_a=true","cold_source=true")),"actual BBS postCallback forwards one visibility update per transition");
            long registered=count("settings.register");for(int open=0;open<20;open++){check(ModelManagement.registerOnClient(loader),"repeat register succeeds");ModelManagement.configure(config);}
            check(count("settings.register")==registered&&count("settings.configRead")==reads,"20 settings access attempts reuse module and do not read preferences again");
            check(count("startup.sourceEnumerate")==0&&count("startup.scan")==0&&count("source.hash")==0,"settings and toggles never scan/hash/import sources");
            var readySources=new ArrayList<>(sources);readySources.set(2,new ModelManagement.SourceInfo("cold_source","未准备来源","3",24,true));
            ModelManagement.publishManifest(readySources);check(ModelManagement.registerOnClient(loader),"ready state updates labels");
            check(!ModelManagement.pendingRestart("cold_source")&&!label(l10n,coldKey).contains("下次启动加载")&&modules.get(ModelManagement.MODULE_ID)==module,"readiness label update reuses native module and values");
            a.getClass().getMethod("toggle").invoke(a);module.getClass().getMethod("save").invoke(module);
            var saved=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("sources");
            check(!saved.get("source_a").getAsBoolean()&&!saved.get("historic_absent").getAsBoolean()&&saved.get("cold_source").getAsBoolean(),"real BBS serialization persists current and historical switches");
            ModelManagement.configure(base.resolve("second/config"));check(ModelManagement.enabled("source_a"),"different config root resets cached preferences");
            ModelManagement.configure(config);check(!ModelManagement.enabled("source_a")&&ModelManagement.enabled("cold_source"),"next startup restores saved source preferences");
            ModelManagement.publishManifest(sources);check(ModelManagement.registerOnClient(loader),"restart module registers");
            check(ModelManagement.pendingRestart("cold_source"),"pending unprepared state restores from manifest plus saved preference");
        }
        ModelManagement.onChanged(null);
        System.out.println("MODEL MANAGEMENT CPU PASS: actual BBS registry/labels/callback/save, "+sources.size()+" sources, 20 cached registrations, no scan/hash/import");
    }
    private static String label(Object l10n,String key)throws Exception{Object label=l10n.getClass().getMethod("getKey",String.class).invoke(l10n,key);return (String)label.getClass().getMethod("get").invoke(label);}
    private static long count(String name){Object value=Performance.snapshot().get(name);return value instanceof Map<?,?> stat?((Number)stat.get("count")).longValue():0;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
