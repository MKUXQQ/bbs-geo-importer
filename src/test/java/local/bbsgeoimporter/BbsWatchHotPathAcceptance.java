package local.bbsgeoimporter;

import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import local.bbsgeoimporter.mixin.CaptureMixinPlugin;

/** Actual BBS 2.7 enumeration/watch/section/cell bytecode, with CPU-only resource/UI seams. */
public final class BbsWatchHotPathAcceptance {
    private static final String MANAGER="mchorse/bbs_mod/cubic/model/ModelManager";
    private static final String SECTION="mchorse/bbs_mod/forms/sections/ModelFormSection";
    public static void main(String[] args)throws Exception {
        Path base=Path.of("build/bbs-1517-watch-cpu").toAbsolutePath();Files.createDirectories(base);
        Path jar=Path.of(args.length==0?"E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.7-1.20.1.jar":args[0]);
        compileFacades(base);
        try(JarFile archive=new JarFile(jar.toFile())){
            setupQueue(archive);
            ClassNode manager=read(archive,MANAGER);manager.interfaces.clear();manager.superName="java/lang/Object";
            manager.fields.removeIf(f->!Set.of("models","requested","provider","loader").contains(f.name));
            manager.methods.removeIf(m->!Set.of("getAvailableKeys","lambda$getAvailableKeys$0","getModelKey","isRelodable","accept","forget","getModel").contains(m.name));
            MethodNode ctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Lmchorse/bbs_mod/resources/AssetProvider;)V",null,null);
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false));
            setNew(ctor,MANAGER,"models","Ljava/util/Map;","java/util/concurrent/ConcurrentHashMap");
            setNew(ctor,MANAGER,"requested","Ljava/util/Set;","java/util/HashSet");
            setNew(ctor,MANAGER,"loader","Lmchorse/bbs_mod/cubic/model/ModelLoader;","mchorse/bbs_mod/cubic/model/ModelLoader");
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));ctor.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,MANAGER,"provider","Lmchorse/bbs_mod/resources/AssetProvider;"));ctor.instructions.add(new InsnNode(Opcodes.RETURN));manager.methods.add(ctor);
            CaptureMixinPlugin.instrumentRefresh(manager);watch(manager);write(base,manager);
            ClassNode parent=read(archive,"mchorse/bbs_mod/forms/sections/SubFormSection");stubCategoryUi(parent);write(base,parent);
            ClassNode section=read(archive,SECTION);stubCategoryUi(section);watch(section);initiate(section);write(base,section);
            ClassNode cell=read(archive,"mchorse/bbs_mod/ui/forms/FormCellRenderer");
            check(!CaptureMixinPlugin.instrumentListPreview(read(archive,"mchorse/bbs_mod/forms/FormUtilsClient")),"global/native preview utility is not redirected");
            cell.methods.removeIf(m->!Set.of("render","hasName","renderHotkey").contains(m.name));
            for(MethodNode m:cell.methods)if(m.name.equals("renderHotkey"))empty(m,false);
            check(CaptureMixinPlugin.instrumentListPreview(cell),"actual BBS cell preview hook");verify(cell);write(base,cell);
            ClassNode context=new ClassNode();new ClassReader(Files.readAllBytes(base.resolve("mchorse/bbs_mod/ui/framework/UIContext.class"))).accept(context,0);
            MethodNode post=read(archive,"mchorse/bbs_mod/ui/framework/UIContext").methods.stream().filter(m->m.name.equals("postRender")&&m.desc.equals("()V")).findFirst().orElseThrow();
            for(var n:post.instructions.toArray())if(n.getOpcode()==Opcodes.RETURN){InsnList end=new InsnList();end.add(new VarInsnNode(Opcodes.ALOAD,0));end.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/ListPreviewRequests","endFrame","(Ljava/lang/Object;)V",false));post.instructions.insertBefore(n,end);}context.methods.add(post);write(base,context);
            ClassNode renderer=read(archive,"mchorse/bbs_mod/forms/renderers/ModelFormRenderer");
            renderer.superName="java/lang/Object";renderer.interfaces.clear();renderer.fields.clear();renderer.methods.removeIf(m->!m.name.equals("getModel")||!m.desc.equals("(Lmchorse/bbs_mod/forms/forms/ModelForm;)Lmchorse/bbs_mod/cubic/ModelInstance;"));write(base,renderer);
        }
        try(URLClassLoader loader=new URLClassLoader(new URL[]{base.toUri().toURL(),jar.toUri().toURL()},BbsWatchHotPathAcceptance.class.getClassLoader())){
            Class<?> providerType=loader.loadClass("mchorse.bbs_mod.resources.AssetProvider"),managerType=loader.loadClass(MANAGER.replace('/','.'));
            Object provider=providerType.getConstructor().newInstance();providerType.getField("root").set(provider,base.resolve("assets").toString());
            @SuppressWarnings("unchecked") List<Object> links=(List<Object>)providerType.getField("links").get(provider);
            Class<?> linkType=loader.loadClass("mchorse.bbs_mod.resources.Link");var assets=linkType.getMethod("assets",String.class);
            for(int i=0;i<500;i++)links.add(assets.invoke(null,"models/user/m"+i+"/model.geo.json"));
            loader.loadClass("mchorse.bbs_mod.BBSMod").getField("provider").set(null,provider);
            Object manager=managerType.getConstructor(providerType).newInstance(provider);loader.loadClass("mchorse.bbs_mod.BBSModClient").getField("manager").set(null,manager);
            Class<?> categoriesType=loader.loadClass("mchorse.bbs_mod.forms.FormCategories"),sectionType=loader.loadClass(SECTION.replace('/','.'));
            Object categories=categoriesType.getConstructor().newInstance(),section=sectionType.getConstructor(categoriesType).newInstance(categories);
            Class<?> eventType=loader.loadClass("mchorse.bbs_mod.utils.watchdog.WatchDogEvent");Object modified=eventType.getField("MODIFIED").get(null),created=eventType.getField("CREATED").get(null),deleted=eventType.getField("DELETED").get(null);
            var managerAccept=managerType.getMethod("accept",Path.class,eventType);var sectionAccept=sectionType.getMethod("accept",Path.class,eventType);
            Performance.reset();sectionType.getMethod("initiate").invoke(section);long initialScans=count("bbs.nativeKeyScan"),initialDirty=count("bbs.markDirty");
            check(initialScans==1,"actual native enumeration primed once");
            for(int i=0;i<20;i++){
                for(Path path:List.of(base.resolve("assets/settings/bbs.json"),base.resolve("outside.txt"),base.resolve("assets/models/user/m0/config.json"))){managerAccept.invoke(manager,path,modified);sectionAccept.invoke(section,path,modified);}
                sectionType.getMethod("initiate").invoke(section);managerType.getMethod("getAvailableKeys").invoke(manager);
            }
            check(count("bbs.nativeKeyScan")==initialScans,"20 actual section openings + unrelated/config events do not reenumerate provider");
            check(count("bbs.markDirty")==initialDirty,"20 unchanged openings do not dirty category lists");
            check((int)providerType.getField("fullScans").get(provider)==1,"one actual provider models-root walk");
            check((int)providerType.getField("subtreeScans").get(provider)==0,"modified events do not enumerate model subtrees");
            Path newPath=base.resolve("assets/models/user/new/model.geo.json");links.add(assets.invoke(null,"models/user/new/model.geo.json"));
            managerAccept.invoke(manager,newPath,created);sectionAccept.invoke(section,newPath,created);
            check(((List<?>)managerType.getMethod("getAvailableKeys").invoke(manager)).contains("user/new")&&sectionKeys(section).contains("user/new"),"new native user model updates cached list and real section");
            links.removeIf(l->{try{return linkType.getField("path").get(l).equals("models/user/new/model.geo.json");}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}});
            managerAccept.invoke(manager,newPath,deleted);sectionAccept.invoke(section,newPath,deleted);
            check(!((List<?>)managerType.getMethod("getAvailableKeys").invoke(manager)).contains("user/new")&&!sectionKeys(section).contains("user/new"),"removed native user model disappears without a root walk");
            check(count("bbs.nativeKeyScan")==initialScans,"native create/delete keeps root enumeration cached");
            preview(loader,manager,managerType);
            check(count("bbs.watchBridgeFailure")==0&&count("bbs.previewBridgeFailure")==0,"actual bridge signatures work");
            System.out.println("BBS WATCH/CELL CPU PASS: 500 models, 20 initiate cycles, native root scans=1; unrelated+config events add zero scans/dirty; user create/delete preserved; 200 static frames capture once; scrolling submits zero cold requests; local skin/size/content invalidation; real setup ASM wraps=2, queued tasks execute once; fake GPU only; metrics="+Performance.snapshot());
        }
    }
    private static void setupQueue(JarFile archive)throws Exception {
        ClassNode instance=read(archive,"mchorse/bbs_mod/cubic/ModelInstance");
        instance.methods.removeIf(method->!method.name.equals("setup")||!method.desc.equals("()V"));
        check(instance.methods.size()==1,"one real BBS ModelInstance.setup method");
        MethodNode setup=instance.methods.get(0);
        check(calls(setup,"mchorse/bbs_mod/cubic/model/ModelSetupQueue","add")==2,"actual BOBJ and cubic setup paths each queue one task");
        check(CaptureMixinPlugin.instrumentListPreview(instance),"real BBS setup bytecode is instrumented");
        check(calls(setup,"local/bbsgeoimporter/ListPreviewSetup","wrap")==2,"both real setup enqueue sites receive a wrapper");
        for(var instruction:setup.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals("mchorse/bbs_mod/cubic/model/ModelSetupQueue")&&call.name.equals("add")){
            check(call.getPrevious() instanceof MethodInsnNode wrap&&wrap.owner.equals("local/bbsgeoimporter/ListPreviewSetup")&&wrap.name.equals("wrap")&&wrap.desc.equals("(Ljava/lang/Runnable;Ljava/lang/Object;)Ljava/lang/Runnable;"),"native queue receives the instance-specific wrapper");
            check(call.getPrevious().getPrevious() instanceof VarInsnNode owner&&owner.getOpcode()==Opcodes.ALOAD&&owner.var==0,"wrapper receives the same ModelInstance");
        }
        verify(instance);CaptureMixinPlugin.instrumentListPreview(instance);verify(instance);
        check(calls(setup,"local/bbsgeoimporter/ListPreviewSetup","wrap")==2,"repeated transform does not double-wrap setup tasks");
        Object model=new Object();int[] work={0};
        check(ListPreviewSetup.ready(model),"CPU-only models with no queued setup are ready");
        Runnable nativeQueued=ListPreviewSetup.wrap(()->work[0]++,model);
        check(!ListPreviewSetup.ready(model)&&work[0]==0,"queued setup remains pending before the list budget runs it");
        ListPreviewSetup.finish(model);
        check(ListPreviewSetup.ready(model)&&work[0]==1,"finish executes the pending setup exactly once");
        nativeQueued.run();nativeQueued.run();ListPreviewSetup.finish(model);
        check(work[0]==1,"the later native queue wrapper and repeated finish are no-ops");
        Object multiple=new Object();int[] tasks={0,0};Runnable first=ListPreviewSetup.wrap(()->tasks[0]++,multiple),second=ListPreviewSetup.wrap(()->tasks[1]++,multiple);
        ListPreviewSetup.finish(multiple);
        check(tasks[0]==1&&tasks[1]==0&&!ListPreviewSetup.ready(multiple),"one finish consumes one setup and retains remaining work");
        first.run();ListPreviewSetup.finish(multiple);second.run();
        check(tasks[0]==1&&tasks[1]==1&&ListPreviewSetup.ready(multiple),"all tasks finish once even when native drain runs their wrappers later");
    }
    private static long calls(MethodNode method,String owner,String name){return java.util.stream.StreamSupport.stream(method.instructions.spliterator(),false).filter(instruction->instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name)).count();}
    private static void preview(ClassLoader loader,Object manager,Class<?> managerType)throws Exception {
        ListModelResidency.resetForTest();
        Class<?> form=loader.loadClass("mchorse.bbs_mod.forms.forms.Form"),model=loader.loadClass("mchorse.bbs_mod.forms.forms.ModelForm"),context=loader.loadClass("mchorse.bbs_mod.ui.framework.UIContext"),state=loader.loadClass("mchorse.bbs_mod.ui.utils.cells.CellState");
        Object ui=context.getConstructor().newInstance(),cellState=state.getConstructor().newInstance();
        var render=loader.loadClass("mchorse.bbs_mod.ui.forms.FormCellRenderer").getMethod("render",context,form,int.class,int.class,int.class,int.class,state);
        List<Object> forms=new ArrayList<>();for(int i=0;i<500;i++){Object f=model.getConstructor().newInstance();Object value=model.getField("model").get(f);value.getClass().getMethod("set",Object.class).invoke(value,"user/m"+i);forms.add(f);}
        Map<String,Long> startupBefore=scanCounters();CpuThumbnailBackend backend=new CpuThumbnailBackend();
        ListPreviewRequests.backendForTest(backend);
        try {
            long before=count("bbs.previewColdRequest");
            context.getField("settled").setBoolean(ui,false);
            // Real transformed cells receive only the native viewport's visible subset.
            for(int i=0;i<12;i++)render.invoke(null,ui,forms.get(i),0,0,50,50,cellState);
            check(count("bbs.previewColdRequest")==before&&backend.captures==0,"wheel scrolling submits zero cold requests and captures");
            Object dragged=context.getConstructor().newInstance();ScrollFixtureView view=new ScrollFixtureView();view.scroll.dragging=true;
            ListPreviewRequests.scroll(view,dragged);
            for(int i=0;i<12;i++)render.invoke(null,dragged,forms.get(i),0,0,50,50,cellState);
            check(count("bbs.previewColdRequest")==before&&backend.captures==0,"scrollbar dragging defers even newly visible model keys");
            view.scroll.dragging=false;Object smoothing=context.getConstructor().newInstance();ListPreviewRequests.scroll(view,smoothing);view.scroll.position=45;ListPreviewRequests.scroll(view,smoothing);
            render.invoke(null,smoothing,forms.get(12),0,0,50,50,cellState);
            check(count("bbs.previewColdRequest")==before,"smooth scroll movement also defers the current frame");
            ui=context.getConstructor().newInstance();
            for(int i=0;i<12;i++)render.invoke(null,ui,forms.get(i),0,0,50,50,cellState);
            check(count("bbs.previewColdRequest")==before+1,"one settled cell frame requests at most one cold model");
            context.getMethod("postRender").invoke(ui);render.invoke(null,ui,forms.get(1),0,0,50,50,cellState);
            check(count("bbs.previewColdRequest")==before+1,"another frame cannot queue a second unfinished UI model");
            @SuppressWarnings("unchecked") Map<String,Object> loaded=(Map<String,Object>)managerType.getField("models").get(manager);
            loaded.put("user/m0",loader.loadClass("mchorse.bbs_mod.cubic.ModelInstance").getConstructor().newInstance());
            ListModelResidency.completed(manager,"user/m0");context.getMethod("postRender").invoke(ui);render.invoke(null,ui,forms.get(1),0,0,50,50,cellState);
            check(count("bbs.previewColdRequest")==before+2,"actual UI postRender advances the next request after the first completes");
            for(int i=20;i<24;i++)loaded.put("user/m"+i,loader.loadClass("mchorse.bbs_mod.cubic.ModelInstance").getConstructor().newInstance());
            Class<?> utility=loader.loadClass("mchorse.bbs_mod.forms.FormUtilsClient");long nativeBefore=utility.getField("nativeCalls").getInt(null);
            ui=context.getConstructor().newInstance();
            for(int frame=0;frame<200;frame++){render.invoke(null,ui,forms.get(20),0,0,50,50,cellState);context.getMethod("postRender").invoke(ui);}
            check(backend.captures==1&&utility.getField("nativeCalls").getInt(null)==nativeBefore+1,"200 frames render the same loaded item once through native preview");
            check(backend.draws==200,"subsequent static frames draw only the cached fake surface");
            Object reopened=context.getConstructor().newInstance();render.invoke(null,reopened,forms.get(20),0,0,50,50,cellState);
            check(backend.captures==1,"closing and reopening a list reuses the thumbnail");
            Object retained=loaded.remove("user/m20");long requestsBefore=count("bbs.previewColdRequest");
            render.invoke(null,reopened,forms.get(20),0,0,50,50,cellState);
            check(backend.captures==1&&count("bbs.previewColdRequest")==requestsBefore&&!loaded.containsKey("user/m20"),"thumbnail hit survives native instance eviction without getModel or reload");
            loaded.put("user/m20",retained);
            form.getField("skin").set(forms.get(20),"alternate");form.getField("poseVersion").setInt(forms.get(20),1);
            render.invoke(null,reopened,forms.get(20),0,0,50,50,cellState);
            check(backend.captures==2,"a different skin creates an independent thumbnail");
            context.getMethod("postRender").invoke(reopened);render.invoke(null,reopened,forms.get(20),0,0,64,50,cellState);
            check(backend.captures==3,"a changed cell size creates the corresponding resolution");
            context.getMethod("postRender").invoke(reopened);render.invoke(null,reopened,forms.get(20),0,0,50,50,cellState);
            check(backend.captures==3,"the earlier skin and size cache survives alternate dimensions");
            render.invoke(null,reopened,forms.get(21),0,0,50,50,cellState);check(backend.captures==4,"second model has its own cached image");
            ImportedModelGroups.nativeContentsChanged("user/m20");context.getMethod("postRender").invoke(reopened);
            render.invoke(null,reopened,forms.get(20),0,0,50,50,cellState);render.invoke(null,reopened,forms.get(21),0,0,50,50,cellState);
            check(backend.captures==5,"content event invalidates only its model and preserves the other image");
            Object slowUi=context.getConstructor().newInstance();backend.slowNext=true;render.invoke(null,slowUi,forms.get(22),0,0,50,50,cellState);int afterSlow=backend.captures;
            render.invoke(null,slowUi,forms.get(23),0,0,50,50,cellState);
            check(afterSlow==6&&backend.captures==afterSlow,"a 60 ms native capture admits no further generation in the same frame");
            context.getMethod("postRender").invoke(slowUi);render.invoke(null,slowUi,forms.get(23),0,0,50,50,cellState);
            check(backend.captures==afterSlow,"slow capture cooldown remains active after frame reset");
            ImportedModelGroups.Snapshot previous=ImportedModelGroups.snapshot();var snapshotField=ImportedModelGroups.class.getDeclaredField("snapshot");snapshotField.setAccessible(true);
            Set<String> hidden=new HashSet<>(previous.inactive());hidden.add("user/m20");
            try {
                snapshotField.set(null,new ImportedModelGroups.Snapshot(previous.generation(),previous.version()+1,previous.keys(),previous.modelKeys(),Set.copyOf(hidden)));int beforeDraw=backend.draws;long disabled=count("bbs.previewDisabled");
                render.invoke(null,context.getConstructor().newInstance(),forms.get(20),0,0,50,50,cellState);
                check(count("bbs.previewDisabled")==disabled+1&&backend.draws==beforeDraw,"disabled source does not draw or request a list preview");
                Object existing=loaded.get("user/m20");check(managerType.getMethod("getModel",String.class).invoke(manager,"user/m20")==existing,"disabled-list model remains available to existing scenes");
                int outsideBefore=utility.getField("nativeCalls").getInt(null);utility.getMethod("renderPreview",form,context,int.class,int.class,int.class,int.class).invoke(null,forms.get(20),ui,0,0,50,50);
                check(utility.getField("nativeCalls").getInt(null)==outsideBefore+1&&backend.captures==afterSlow,"native preview outside FormCellRenderer is not redirected or blocked");
            } finally {snapshotField.set(null,previous);}
            var requestedField=managerType.getDeclaredField("requested");requestedField.setAccessible(true);
            check(((Set<?>)requestedField.get(manager)).equals(Set.of("user/m0","user/m1")),"only two requested visible keys enter native loader; offscreen forms stay untouched");
            check(scanCounters().equals(startupBefore),"all preview/scroll/open/toggle operations add zero source scan, hash, parse, import or index work");
        } finally {ListPreviewRequests.backendForTest(null);}
    }
    public static final class ScrollFixtureView {public final ScrollFixture scroll=new ScrollFixture();}
    public static final class ScrollFixture {public boolean dragging;public double position;public double getScroll(){return position;}}
    private static final class CpuThumbnailBackend implements ListPreviewRequests.Backend {
        int captures,draws,releases;boolean slowNext;
        public int[] size(Object context,int x,int y,int right,int bottom){return new int[]{right-x,bottom-y};}
        public Object capture(ListPreviewRequests.Access access,Object form,Object context,int x,int y,int right,int bottom,int width,int height)throws ReflectiveOperationException {
            captures++;if(slowNext){slowNext=false;try{Thread.sleep(60);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
            access.nativeRender(form,context,x,y,right,bottom);return new Object();
        }
        public void draw(Object surface,Object context,int x,int y,int right,int bottom){draws++;}
        public void release(Object surface){releases++;}
    }
    private static Map<String,Long> scanCounters(){Map<String,Long> result=new TreeMap<>();for(var entry:Performance.snapshot().entrySet())if(entry.getKey().startsWith("startup.")||entry.getKey().startsWith("source.")||entry.getKey().startsWith("snapshot.")||entry.getKey().startsWith("catalog.")||entry.getKey().startsWith("import.")||entry.getKey().startsWith("index."))result.put(entry.getKey(),((Number)((Map<?,?>)entry.getValue()).get("count")).longValue());return result;}
    private static Set<String> sectionKeys(Object section)throws Exception {Set<String> result=new HashSet<>();for(Object c:(List<?>)section.getClass().getMethod("getCategories").invoke(section))for(Object f:(List<?>)c.getClass().getMethod("getDirectForms").invoke(c)){Object value=f.getClass().getField("model").get(f);result.add((String)value.getClass().getMethod("get").invoke(value));}return result;}
    private static void compileFacades(Path base)throws Exception {
        Map<String,String> f=new LinkedHashMap<>();
        f.put("mchorse/bbs_mod/resources/Link","public class Link {public String path;public static Link assets(String p){Link l=new Link();l.path=p;return l;}public String toString(){return path;}}");
        f.put("mchorse/bbs_mod/resources/AssetProvider","public class AssetProvider {public String root;public int fullScans,subtreeScans;public final java.util.List<Link> links=new java.util.ArrayList<>();public Link getLink(java.io.File f){java.nio.file.Path p=f.toPath().toAbsolutePath().normalize(),r=java.nio.file.Path.of(root).toAbsolutePath().normalize();return p.startsWith(r)?Link.assets(r.relativize(p).toString().replace('\\\\','/')):null;}public java.util.Collection<Link> getLinksFromPath(Link l,boolean deep){if(l.path.equals(\"models\"))fullScans++;else subtreeScans++;return links.stream().filter(v->v.path.startsWith(l.path+\"/\")).toList();}}");
        f.put("mchorse/bbs_mod/BBSMod","public class BBSMod {public static mchorse.bbs_mod.resources.AssetProvider provider;public static mchorse.bbs_mod.resources.AssetProvider getProvider(){return provider;}}");
        f.put("mchorse/bbs_mod/BBSModClient","public class BBSModClient {public static mchorse.bbs_mod.cubic.model.ModelManager manager;public static mchorse.bbs_mod.cubic.model.ModelManager getModels(){return manager;}}");
        f.put("mchorse/bbs_mod/cubic/model/ModelManager","public class ModelManager {public mchorse.bbs_mod.cubic.ModelInstance getModel(String key){return null;}}");
        f.put("mchorse/bbs_mod/cubic/model/ModelLoader","public class ModelLoader {public static int requests;public ModelLoader(){}public void add(String key){requests++;}}");
        f.put("mchorse/bbs_mod/cubic/ModelInstance","public class ModelInstance {public void delete(){}}");
        f.put("mchorse/bbs_mod/forms/FormCategories","public class FormCategories {public int dirty;public void markDirty(){dirty++;}}");
        f.put("mchorse/bbs_mod/settings/values/core/ValueString","public class ValueString {private Object value;public Object get(){return value;}public void set(Object v){value=v;}}");
        f.put("mchorse/bbs_mod/forms/forms/Form","public class Form {public int poseVersion;public String skin=\"\";public int getPoseVersion(){return poseVersion;}public Object toData(){return skin;}public String getDisplayName(){return \"fixture\";}}");
        f.put("mchorse/bbs_mod/forms/forms/ModelForm","public class ModelForm extends Form {public final mchorse.bbs_mod.settings.values.core.ValueString model=new mchorse.bbs_mod.settings.values.core.ValueString();}");
        f.put("mchorse/bbs_mod/forms/categories/FormCategory","public class FormCategory {private final java.util.List<mchorse.bbs_mod.forms.forms.Form> forms=new java.util.ArrayList<>();public java.util.List<mchorse.bbs_mod.forms.forms.Form> getForms(){return forms;}public java.util.List<mchorse.bbs_mod.forms.forms.Form> getDirectForms(){return forms;}public void addForm(mchorse.bbs_mod.forms.forms.Form f){forms.add(f);}}");
        f.put("mchorse/bbs_mod/ui/utils/icons/Icon","public class Icon {}");
        f.put("mchorse/bbs_mod/l10n/keys/IKey","public interface IKey {String get();static IKey constant(String s){return ()->s;}static IKey comp(java.util.List<IKey> s){return ()->s.toString();}}");
        f.put("mchorse/bbs_mod/ui/framework/UIContext","public class UIContext {public boolean settled=true;public long lastScroll;public final mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D batcher=new mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D();public final mchorse.bbs_mod.ui.framework.tooltips.UITooltip tooltip=new mchorse.bbs_mod.ui.framework.tooltips.UITooltip();public final mchorse.bbs_mod.ui.framework.notifications.UINotifications notifications=new mchorse.bbs_mod.ui.framework.notifications.UINotifications();public boolean hasNotScrolledForMore(long millis){return settled&&System.currentTimeMillis()-lastScroll>millis;}public void updateScroll(){}}");
        f.put("mchorse/bbs_mod/ui/framework/tooltips/UITooltip","public class UITooltip {public void render(mchorse.bbs_mod.ui.framework.UIContext c){}}");
        f.put("mchorse/bbs_mod/ui/framework/notifications/UINotifications","public class UINotifications {public void render(mchorse.bbs_mod.ui.framework.UIContext c){}}");
        f.put("mchorse/bbs_mod/ui/framework/elements/utils/Batcher2D","public class Batcher2D {public void clip(int x,int y,int w,int h,mchorse.bbs_mod.ui.framework.UIContext c){}public void unclip(mchorse.bbs_mod.ui.framework.UIContext c){}}");
        f.put("mchorse/bbs_mod/ui/utils/cells/CellState","public class CellState {public boolean hover,selected;}");
        f.put("mchorse/bbs_mod/ui/utils/cells/CellPainter","public class CellPainter {public static boolean hasCaption(int w){return w>=80;}public static boolean captionFits(mchorse.bbs_mod.ui.framework.UIContext c,String s,int w){return true;}public static void marks(mchorse.bbs_mod.ui.framework.UIContext c,int x,int y,int w,int h,CellState s){}public static void dim(mchorse.bbs_mod.ui.framework.UIContext c,int x,int y,int w,int h,CellState s){}public static void bar(mchorse.bbs_mod.ui.framework.UIContext c,int x,int y,int w,int h,CellState s){}public static void caption(mchorse.bbs_mod.ui.framework.UIContext c,String n,int x,int y,int w,int h,boolean b){}}");
        f.put("mchorse/bbs_mod/forms/renderers/ModelFormRenderer","public class ModelFormRenderer {public static mchorse.bbs_mod.cubic.ModelInstance getModel(mchorse.bbs_mod.forms.forms.ModelForm f){return null;}}");
        f.put("mchorse/bbs_mod/forms/FormUtilsClient","public class FormUtilsClient {public static int nativeCalls;public static void renderPreview(mchorse.bbs_mod.forms.forms.Form f,mchorse.bbs_mod.ui.framework.UIContext c,int x,int y,int r,int b){nativeCalls++;if(f instanceof mchorse.bbs_mod.forms.forms.ModelForm m)mchorse.bbs_mod.forms.renderers.ModelFormRenderer.getModel(m);}}");
        List<String> args=new ArrayList<>(List.of("-proc:none","-d",base.toString()));
        for(var e:f.entrySet()){Path file=base.resolve(e.getKey()+".java");Files.createDirectories(file.getParent());int slash=e.getKey().lastIndexOf('/');Files.writeString(file,"package "+e.getKey().substring(0,slash).replace('/','.')+";"+e.getValue());args.add(file.toString());}
        check(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new))==0,"CPU facades compile");
    }
    private static void setNew(MethodNode m,String owner,String field,String desc,String type){m.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));m.instructions.add(new TypeInsnNode(Opcodes.NEW,type));m.instructions.add(new InsnNode(Opcodes.DUP));m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,type,"<init>","()V",false));m.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,owner,field,desc));}
    private static ClassNode read(JarFile jar,String name)throws Exception {ClassNode node=new ClassNode();new ClassReader(jar.getInputStream(jar.getJarEntry(name+".class"))).accept(node,0);return node;}
    private static void write(Path base,ClassNode node)throws Exception {ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);node.accept(writer);Path path=base.resolve(node.name+".class");Files.createDirectories(path.getParent());Files.write(path,writer.toByteArray());}
    private static void verify(ClassNode node)throws Exception {for(MethodNode m:node.methods)if((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name,m);}
    private static void watch(ClassNode node){for(MethodNode m:node.methods)if(m.name.equals("accept")&&m.desc.startsWith("(Ljava/nio/file/Path;")){InsnList i=new InsnList();i.add(new VarInsnNode(Opcodes.ALOAD,0));i.add(new VarInsnNode(Opcodes.ALOAD,1));i.add(new VarInsnNode(Opcodes.ALOAD,2));i.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/ModelWatchEvents","beforeAccept","(Ljava/lang/Object;Ljava/nio/file/Path;Ljava/lang/Object;)Z",false));LabelNode go=new LabelNode();i.add(new JumpInsnNode(Opcodes.IFEQ,go));i.add(new InsnNode(Opcodes.RETURN));i.add(go);m.instructions.insert(i);}}
    private static void initiate(ClassNode node){for(MethodNode m:node.methods)if(m.name.equals("initiate")&&m.desc.equals("()V")){InsnList i=new InsnList();i.add(new VarInsnNode(Opcodes.ALOAD,0));i.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/BbsCategoryRefresh","beforeInitiate","(Ljava/lang/Object;)Z",false));LabelNode go=new LabelNode();i.add(new JumpInsnNode(Opcodes.IFEQ,go));i.add(new InsnNode(Opcodes.RETURN));i.add(go);m.instructions.insert(i);for(var n:m.instructions.toArray())if(n.getOpcode()==Opcodes.RETURN){InsnList end=new InsnList();end.add(new VarInsnNode(Opcodes.ALOAD,0));end.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"local/bbsgeoimporter/BbsCategoryRefresh","afterInitiate","(Ljava/lang/Object;)V",false));m.instructions.insertBefore(n,end);}}}
    private static void stubCategoryUi(ClassNode node){for(MethodNode m:node.methods){if(Set.of("createCategory","getTitle","getIcon").contains(m.name)&&(m.access&Opcodes.ACC_ABSTRACT)==0){boolean category=m.name.equals("createCategory");empty(m,true);if(category){m.instructions.clear();m.instructions.add(new TypeInsnNode(Opcodes.NEW,"mchorse/bbs_mod/forms/categories/FormCategory"));m.instructions.add(new InsnNode(Opcodes.DUP));m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"mchorse/bbs_mod/forms/categories/FormCategory","<init>","()V",false));m.instructions.add(new InsnNode(Opcodes.ARETURN));}}}}
    private static void empty(MethodNode m,boolean object){m.instructions.clear();m.tryCatchBlocks.clear();if(m.localVariables!=null)m.localVariables.clear();if(object)m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));m.instructions.add(new InsnNode(object?Opcodes.ARETURN:Opcodes.RETURN));}
    private static long count(String key){Object value=Performance.snapshot().get(key);return value instanceof Map<?,?> m?((Number)m.get("count")).longValue():0;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
