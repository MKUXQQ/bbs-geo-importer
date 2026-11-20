package local.bbsgeoimporter;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.ToolProvider;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Real preparation/Task/budget code with CPU-only native facade classes; no GL or game. */
public final class CubicMeshLifecycleAcceptance {
    private static final String INSTANCE="mchorse.bbs_mod.cubic.ModelInstance";
    private static final String MODEL="mchorse.bbs_mod.cubic.data.model.Model";
    private static final String VAO="mchorse.bbs_mod.cubic.render.vao.ModelVAO";
    private static final String CLIENT="Cubic CPU client";

    public static void main(String[] args)throws Exception {
        Path base=Files.createTempDirectory(Path.of("build"),"cubic-lifecycle-1519-").toAbsolutePath();
        compile(base);
        String originalThread=Thread.currentThread().getName();
        ClassLoader originalLoader=Thread.currentThread().getContextClassLoader();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{base.toUri().toURL()},CubicMeshLifecycleAcceptance.class.getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                synchronized(getClassLoadingLock(name)){
                    Class<?> found=findLoadedClass(name);
                    if(found==null&&(name.startsWith("mchorse.bbs_mod.")||name.equals("org.lwjgl.opengl.GL30")))try{found=findClass(name);}catch(ClassNotFoundException ignored){}
                    if(found==null)found=super.loadClass(name,false);
                    if(resolve)resolveClass(found);return found;
                }
            }
        }){
            Thread.currentThread().setName(CLIENT);Thread.currentThread().setContextClassLoader(loader);
            Fixture f=new Fixture(loader);
            emptyBudget("entry");Performance.reset();
            publication(f);cancel(f);ownerLost(f);commitFailure(f);budget(f);
            emptyBudget("all lifecycle cases");
            check(number(CubicMeshPreparation.diagnostics(),"peakBytes")<=CubicMeshPreparation.MAX_BYTES,"global staging never exceeds 16MiB");
            System.out.println("CUBIC MESH LIFECYCLE CPU PASS: loader preparation creates zero VAOs; one material/client step and atomic publication; cancel/owner-loss/failure release staging; >16MiB fallback and shared plan budget verified. Facade VAO only; no GPU/game execution.");
        }finally{Thread.currentThread().setName(originalThread);Thread.currentThread().setContextClassLoader(originalLoader);}
    }

    private static void publication(Fixture f)throws Exception {
        f.reset();Object instance=f.instance(7,11);Object initial=f.map(instance);
        check(CubicMeshPreparation.prepare(instance)==null,"client cannot perform loader CPU preparation");
        CubicMeshPreparation.Plan plan=onLoader(f.loader,()->CubicMeshPreparation.prepare(instance));
        check(plan!=null&&f.integer("created")==0&&f.integer("attempts")==0,"loader prepares two native data records without constructing VAOs");
        check(f.geometry(instance).getClass().getField("preparedThread").get(f.geometry(instance)).equals("BBS model loader"),"native process executed on existing loader thread");
        check(number(CubicMeshPreparation.diagnostics(),"pendingPlans")==1,"completed CPU plan is budgeted");
        check(!plan.step(instance)&&f.integer("created")==1,"first client step commits exactly one material");
        check(f.map(instance)==initial&&((Map<?,?>)initial).isEmpty(),"first material stays staged and does not publish a partial model");
        check(plan.step(instance)&&f.integer("created")==2,"second client step commits remaining material");
        Object published=f.map(instance);check(published!=initial,"native complete-map reference swapped only at completion");
        Map<?,?> groups=(Map<?,?>)published;check(groups.size()==1,"one native geometry group published");
        Map<?,?> materials=(Map<?,?>)groups.values().iterator().next();check(materials.size()==2&&materials.keySet().equals(Set.of("material-0","material-1")),"all material identities retained");
        int[] expected=(int[])f.geometry(instance).getClass().getField("hashes").get(f.geometry(instance));
        for(int i=0;i<expected.length;i++){Object vao=materials.get("material-"+i);check(vao.getClass().getField("arrayHash").getInt(vao)==expected[i],"material arrays preserved exactly at native constructor seam");}
        for(Object name:f.threads())check(CLIENT.equals(name),"VAO facade constructors run only on client");
        check(plan.step(instance)&&f.integer("created")==2&&f.map(instance)==published,"completed plan does not reupload or swap again");
        for(Object vao:materials.values())vao.getClass().getMethod("delete").invoke(vao);
        check(f.deletedBuffers()==10,"completed native owners release their five buffers each");
        check(f.deletedArrays()==4,"completed native owners release their two VAOs each");
        emptyBudget("successful publication");
    }

    private static void cancel(Fixture f)throws Exception {
        f.reset();Object instance=f.instance(9,13);Object initial=f.map(instance);int[] fallback={0};
        Runnable queued=onLoader(f.loader,()->ListPreviewSetup.wrap(()->fallback[0]++,instance));
        check(!ListPreviewSetup.ready(instance)&&ListPreviewSetup.readyModel(instance)==null,"prepared queued instance remains gated until complete");
        queued.run();check(f.integer("attempts")==0,"native queue drain cannot upload a prepared full model");
        ListPreviewSetup.finish(instance);check(f.integer("created")==1&&!ListPreviewSetup.ready(instance),"Task advances a single material per finish");
        ListPreviewSetup.cancel(instance);check(f.integer("deleted")==1&&f.map(instance)==initial,"cancel deletes unpublished VAO and keeps native prior map");
        check(f.deletedBuffers()==5,"partial cancellation releases every staged VBO");
        check(f.deletedArrays()==2,"partial cancellation releases staged VAOs");
        check(fallback[0]==0,"prepared plan never executes captured whole-model Runnable");
        queued.run();ListPreviewSetup.finish(instance);check(f.integer("attempts")==1,"cancelled task cannot resurrect uploads");
        emptyBudget("partial Task cancellation");
    }

    private static void ownerLost(Fixture f)throws Exception {
        f.reset();Object instance=f.instance(17,19);Runnable queued=onLoader(f.loader,()->ListPreviewSetup.wrap(()->{throw new AssertionError("native fallback not expected");},instance));
        Field field=queued.getClass().getDeclaredField("owner");field.setAccessible(true);((WeakReference<?>)field.get(queued)).clear();
        ListPreviewSetup.finish(instance);check(f.integer("attempts")==0,"lost weak owner cancels without making a VAO");
        emptyBudget("weak owner disappearance");ListPreviewSetup.cancel(instance);
    }

    private static void commitFailure(Fixture f)throws Exception {
        f.reset();Object instance=f.instance(23,29);Object initial=f.map(instance);int[] fallback={0};
        Runnable queued=onLoader(f.loader,()->ListPreviewSetup.wrap(()->fallback[0]++,instance));
        ListPreviewSetup.finish(instance);f.vao.getField("failNext").setBoolean(null,true);
        long failures=count("bbs.meshCommitFailure");ListPreviewSetup.finish(instance);
        check(count("bbs.meshCommitFailure")==failures+1,"real Task catches native constructor failure and records it once");
        check(!ListPreviewSetup.ready(instance)&&ListPreviewSetup.readyModel(instance)==null,"failed commit is not declared ready or returned to native renderer");
        check(f.integer("attempts")==2&&f.integer("created")==1&&f.integer("deleted")==1,"partial staging is deleted after second material constructor fails");
        check(f.deletedBuffers()==7,"failed constructor's two buffers and prior material's five buffers are released");
        check(f.deletedArrays()==4,"failed constructor and previous material each release their two VAOs");
        check(f.map(instance)==initial&&fallback[0]==0,"failed Task publishes no partial map and does not retry native whole-model work");
        queued.run();ListPreviewSetup.finish(instance);check(f.integer("attempts")==2,"failed Task is terminal");
        emptyBudget("Task submission failure");ListPreviewSetup.cancel(instance);
    }

    private static void budget(Fixture f)throws Exception {
        f.reset();int floats=(int)(CubicMeshPreparation.MAX_BYTES/4);
        long fallbacks=count("bbs.meshPrepareNativeFallback");
        Object oversized=f.instance(floats,1);
        CubicMeshPreparation.Plan rejected=onLoader(f.loader,()->CubicMeshPreparation.prepare(oversized));
        check(rejected==null&&count("bbs.meshPrepareNativeFallback")==fallbacks+1,"single plan above 16MiB records explicit native fallback");
        check(String.valueOf(CubicMeshPreparation.diagnostics().get("lastFailure")).contains("budget exhausted"),"budget fallback explains cause");
        check(f.integer("created")==0,"rejected preparation performs zero GL constructors");emptyBudget("oversized plan rollback");
        CubicMeshPreparation.Plan a=onLoader(f.loader,()->CubicMeshPreparation.prepare(f.instance(floats/2)));
        CubicMeshPreparation.Plan b=onLoader(f.loader,()->CubicMeshPreparation.prepare(f.instance(floats/2)));
        check(a!=null&&b!=null&&number(CubicMeshPreparation.diagnostics(),"pendingBytes")==CubicMeshPreparation.MAX_BYTES,"two uncommitted plans share the exact global budget");
        CubicMeshPreparation.Plan denied=onLoader(f.loader,()->CubicMeshPreparation.prepare(f.instance(1)));
        check(denied==null&&number(CubicMeshPreparation.diagnostics(),"pendingPlans")==2,"third plan cannot exceed shared budget or corrupt existing reservations");
        a.cancel();CubicMeshPreparation.Plan resumed=onLoader(f.loader,()->CubicMeshPreparation.prepare(f.instance(1)));
        check(resumed!=null,"cancel immediately returns capacity for another preparation");
        resumed.cancel();b.cancel();emptyBudget("global budget recovery");
    }

    private static final class Fixture {
        final ClassLoader loader;final Class<?> instance,model,vao,gl;
        Fixture(ClassLoader loader)throws Exception {this.loader=loader;instance=loader.loadClass(INSTANCE);model=loader.loadClass(MODEL);vao=loader.loadClass(VAO);gl=loader.loadClass("org.lwjgl.opengl.GL30");}
        Object instance(int... lengths)throws Exception {return instance.getConstructor(model).newInstance(model.getConstructor(int[].class).newInstance((Object)lengths));}
        Object geometry(Object value)throws Exception {return instance.getField("model").get(value);}
        Object map(Object value)throws Exception {return instance.getMethod("published").invoke(value);}
        int integer(String name)throws Exception {return vao.getField(name).getInt(null);}
        int deletedBuffers()throws Exception {return ((List<?>)gl.getField("deleted").get(null)).size();}
        int deletedArrays()throws Exception {return ((List<?>)gl.getField("deletedArrays").get(null)).size();}
        List<?> threads()throws Exception {return (List<?>)vao.getField("threads").get(null);}
        void reset()throws Exception {vao.getMethod("reset").invoke(null);((List<?>)gl.getField("deleted").get(null)).clear();((List<?>)gl.getField("deletedArrays").get(null)).clear();}
    }
    private static <T>T onLoader(ClassLoader loader,Callable<T> call)throws Exception {
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread thread=new Thread(()->{try{result.set(call.call());}catch(Throwable ex){failure.set(ex);}},"BBS model loader");thread.setContextClassLoader(loader);thread.start();thread.join(10_000);
        check(!thread.isAlive(),"CPU loader preparation terminates without waiting on a client upload");
        if(failure.get()!=null)throw new AssertionError("loader failure",failure.get());return result.get();
    }
    private static void emptyBudget(String at){Map<String,Object> d=CubicMeshPreparation.diagnostics();check(number(d,"pendingBytes")==0&&number(d,"pendingPlans")==0,at+" releases every byte and plan reservation: "+d);}
    private static long number(Map<String,Object> map,String key){return ((Number)map.get(key)).longValue();}
    private static long count(String name){Object raw=Performance.snapshot().get(name);return raw instanceof Map<?,?> values?((Number)values.get("count")).longValue():0;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}

    private static void compile(Path base)throws Exception {
        Map<String,String> source=new LinkedHashMap<>();
        source.put("mchorse/bbs_mod/cubic/data/model/ModelGroup","public final class ModelGroup {}");
        source.put(MODEL.replace('.','/'),"public final class Model {public final int[] lengths;public final int[] hashes;public final ModelGroup group=new ModelGroup();public String preparedThread;public Model(int[] lengths){this.lengths=lengths;hashes=new int[lengths.length];}}");
        source.put(INSTANCE.replace('.','/'),"public final class ModelInstance {public final mchorse.bbs_mod.cubic.data.model.Model model;public String id;private java.util.Map<Object,java.util.Map<String,Object>> vaos=new java.util.HashMap<>();public ModelInstance(mchorse.bbs_mod.cubic.data.model.Model model){this.model=model;}public Object published(){return vaos;}}");
        source.put("mchorse/bbs_mod/cubic/render/CubicVAOBuilderRenderer","public final class CubicVAOBuilderRenderer {public static final boolean bbsGeoPreparationIsolated=true;public CubicVAOBuilderRenderer(java.util.Map<?,?> ignored){}}");
        source.put("mchorse/bbs_mod/cubic/render/CubicCubeRenderer","public final class CubicCubeRenderer {public static final boolean bbsGeoPreparationIsolated=true;}");
        source.put("mchorse/bbs_mod/cubic/render/Pose","public final class Pose {public Pose(){}}");
        source.put("mchorse/bbs_mod/cubic/render/vao/ModelVAOData","public record ModelVAOData(float[] vertices,float[] normals,float[] tangents,float[] texCoords) {}");
        source.put(VAO.replace('.','/'),"public final class ModelVAO {private transient int[] bbsGeoOwnedBuffers;private int vao,vao2;public static int created,deleted,attempts,nextId,nextArray;public static boolean failNext;public static final java.util.List<String> threads=new java.util.ArrayList<>();public final int arrayHash;private boolean removed;public ModelVAO(ModelVAOData data){local.bbsgeoimporter.VaoBufferOwnership.owner(this);attempts++;threads.add(Thread.currentThread().getName());vao=++nextArray;vao2=++nextArray;for(int i=0;i<5;i++){local.bbsgeoimporter.VaoBufferOwnership.track(this,++nextId);if(failNext&&i==1){failNext=false;throw new IllegalStateException(\"controlled native constructor failure\");}}created++;arrayHash=hash(data);}public static int hash(ModelVAOData d){int h=1;for(float[] a:new float[][]{d.vertices(),d.normals(),d.tangents(),d.texCoords()})h=31*h+java.util.Arrays.hashCode(a);return h;}public void delete(){if(removed)throw new AssertionError(\"double delete\");removed=true;deleted++;org.lwjgl.opengl.GL30.glDeleteVertexArrays(vao);org.lwjgl.opengl.GL30.glDeleteVertexArrays(vao2);local.bbsgeoimporter.VaoBufferOwnership.release(this);}public static void reset(){created=deleted=attempts=nextId=nextArray=0;failNext=false;threads.clear();}}");
        source.put("org/lwjgl/opengl/GL30","public final class GL30 {public static final java.util.List<Integer> deleted=new java.util.ArrayList<>(),deletedArrays=new java.util.ArrayList<>();public static void glDeleteBuffers(int id){record(deleted,id);}public static void glDeleteVertexArrays(int id){record(deletedArrays,id);}private static void record(java.util.List<Integer> values,int id){if(!Thread.currentThread().getName().equals(\"Cubic CPU client\"))throw new AssertionError(\"buffer release off client\");if(id==0||values.contains(id))throw new AssertionError(\"zero or duplicate buffer deletion\");values.add(id);}}");
        source.put("mchorse/bbs_mod/cubic/render/CubicRenderer","public final class CubicRenderer {public static void processRenderModel(CubicVAOBuilderRenderer builder,Object buffer,Pose pose,mchorse.bbs_mod.cubic.data.model.Model model){model.preparedThread=Thread.currentThread().getName();for(int i=0;i<model.lengths.length;i++){float[] vertices=new float[model.lengths[i]];java.util.Arrays.fill(vertices,i+0.25f);var data=new mchorse.bbs_mod.cubic.render.vao.ModelVAOData(vertices,new float[0],new float[0],new float[0]);model.hashes[i]=mchorse.bbs_mod.cubic.render.vao.ModelVAO.hash(data);local.bbsgeoimporter.CubicMeshPreparation.captureVao(data,model.group,\"material-\"+i);}}}");
        List<String> args=new ArrayList<>(List.of("-proc:none","-encoding","UTF-8","-cp",System.getProperty("java.class.path"),"-d",base.toString()));
        for(var e:source.entrySet()){Path file=base.resolve(e.getKey()+".java");Files.createDirectories(file.getParent());int slash=e.getKey().lastIndexOf('/');Files.writeString(file,"package "+e.getKey().substring(0,slash).replace('/','.')+";"+e.getValue(),StandardCharsets.UTF_8);args.add(file.toString());}
        ByteArrayOutputStream errors=new ByteArrayOutputStream();check(ToolProvider.getSystemJavaCompiler().run(null,null,errors,args.toArray(String[]::new))==0,"CPU cubic facade compilation: "+errors.toString(StandardCharsets.UTF_8));
        Path vaoClass=base.resolve(VAO.replace('.','/')+".class");ClassNode node=new ClassNode();new ClassReader(Files.readAllBytes(vaoClass)).accept(node,0);
        node.fields.stream().filter(field->field.name.equals("bbsGeoOwnedBuffers")).findFirst().orElseThrow().access|=Opcodes.ACC_SYNTHETIC;
        ClassWriter writer=new ClassWriter(0);node.accept(writer);Files.write(vaoClass,writer.toByteArray());
    }
    private CubicMeshLifecycleAcceptance(){}
}
