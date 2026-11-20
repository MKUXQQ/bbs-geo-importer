package local.bbsgeoimporter;

import java.lang.reflect.*;
import java.util.*;

/** Splits the native cubic builder at ModelVAOData. No GL is issued by the loader phase. */
public final class CubicMeshPreparation {
    private static final ThreadLocal<Build> building=new ThreadLocal<>();
    static final long MAX_BYTES=16L*1024*1024;
    private static final int MAX_PLANS=8;
    private static final Object budget=new Object();
    private static long pendingBytes,peakBytes;
    private static int pendingPlans;
    private static volatile String lastFailure="";
    private static final class Build {
        final List<Part> parts=new ArrayList<>(); final Map<String,Object> scratch=new HashMap<>(); long bytes;
    }
    private static final class Part {final Object group;final String material;final long bytes;Object data;Part(Object group,String material,Object data,long bytes){this.group=group;this.material=material;this.data=data;this.bytes=bytes;}}
    private static final ClassValue<Access> accesses=new ClassValue<>(){protected Access computeValue(Class<?> c){try{return new Access(c.getClassLoader());}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}}};
    private static final class Access {
        final Field model,vaos;final Constructor<?> builder,pose,vao;final Method process,delete;
        Access(ClassLoader loader)throws ReflectiveOperationException {
            Class<?> instance=Class.forName("mchorse.bbs_mod.cubic.ModelInstance",false,loader),b=Class.forName("mchorse.bbs_mod.cubic.render.CubicVAOBuilderRenderer",true,loader),cube=Class.forName("mchorse.bbs_mod.cubic.render.CubicCubeRenderer",true,loader);
            if(!b.getField("bbsGeoPreparationIsolated").getBoolean(null)||!cube.getField("bbsGeoPreparationIsolated").getBoolean(null))throw new NoSuchFieldException("Native cubic preparation shape not verified");
            model=instance.getField("model");vaos=instance.getDeclaredField("vaos");vaos.setAccessible(true);builder=b.getConstructor(Map.class);
            Class<?> renderer=Class.forName("mchorse.bbs_mod.cubic.render.CubicRenderer",false,loader);process=Arrays.stream(renderer.getMethods()).filter(m->m.getName().equals("processRenderModel")&&m.getParameterCount()==4).findFirst().orElseThrow(()->new NoSuchMethodException("processRenderModel"));pose=process.getParameterTypes()[2].getConstructor();
            Class<?> v=Class.forName("mchorse.bbs_mod.cubic.render.vao.ModelVAO",false,loader),data=Class.forName("mchorse.bbs_mod.cubic.render.vao.ModelVAOData",false,loader);
            if(!VaoBufferOwnership.supported(v))throw new NoSuchFieldException("Native VAO ownership shape not verified");
            vao=v.getConstructor(data);delete=v.getMethod("delete");
        }
    }
    static Plan prepare(Object instance){
        // This is the existing single BBS loader, before ModelManager publishes the instance.
        if(!Thread.currentThread().getName().equals("BBS model loader"))return null;
        try{
            Field model=instance.getClass().getField("model");Object geometry=model.get(instance);if(geometry==null||!geometry.getClass().getName().equals("mchorse.bbs_mod.cubic.data.model.Model"))return null;
            Access a=accesses.get(instance.getClass());if(!beginPlan())throw new IllegalStateException("Cubic plan slots exhausted");Build build=new Build();long start=Performance.start();building.set(build);
            boolean transferred=false;
            try{a.process.invoke(null,a.builder.newInstance(new HashMap<>()),null,a.pose.newInstance(),geometry);Plan result=new Plan(a,build.parts,build.bytes);transferred=true;return result;}
            finally{building.remove();if(!transferred){release(build.bytes);build.parts.clear();endPlan();}Performance.end("bbs.meshCpuPrepare",start);}
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){failure(ex);Performance.count("bbs.meshPrepareNativeFallback",0);return null;}
    }
    /** Called only by the signature-checked native builder constructor seam. */
    public static Object captureVao(Object data,Object group,String material){
        Build build=building.get();
        try{
            if(build==null)return Class.forName("mchorse.bbs_mod.cubic.render.vao.ModelVAO",false,data.getClass().getClassLoader()).getConstructor(data.getClass()).newInstance(data);
            long bytes=0;for(String name:List.of("vertices","normals","tangents","texCoords")){float[] values=(float[])data.getClass().getMethod(name).invoke(data);if(values!=null)bytes+=(long)values.length*4;}
            // Never wait for a permit on the loader: a scene request cannot depend on an open UI.
            // Both in-progress builds and completed, uncommitted plans share this one byte budget.
            if(!reserve(bytes))throw new IllegalStateException("Global cubic staging budget exhausted");
            build.bytes+=bytes;build.parts.add(new Part(group,material,data,bytes));return null;
        }catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
    }
    /** Native render thread keeps its existing temporaries; loader preparation owns separate matrices/vectors. */
    public static Object scratch(Object original,String field){
        Build build=building.get();if(build==null)return original;
        return build.scratch.computeIfAbsent(field,k->{try{return original.getClass().getConstructor().newInstance();}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}});
    }
    public static float[] tangents(float[] vertices,float[] normals,float[] uv){
        long start=Performance.start();try{ClassLoader loader=Thread.currentThread().getContextClassLoader();return (float[])Class.forName("mchorse.bbs_mod.client.BBSRendering",false,loader).getMethod("calculateTangents",float[].class,float[].class,float[].class).invoke(null,vertices,normals,uv);}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}finally{Performance.end("bbs.meshTangents",start);}
    }
    static final class Plan {
        final Access access;final List<Part> parts;final Map<Object,Map<String,Object>> staged=new HashMap<>();final long bytes;long retained;int cursor;boolean published,closed;
        Plan(Access access,List<Part> parts,long bytes){this.access=access;this.parts=parts;this.bytes=bytes;retained=bytes;}
        /** One native material upload, then a single complete-map swap. Never exposes a half-built body. */
        boolean step(Object instance)throws ReflectiveOperationException {
            if(closed)return true;
            if(cursor<parts.size()){Part part=parts.get(cursor);long start=Performance.start();Object vao;try{vao=upload(part.data);}finally{Performance.end("bbs.meshGlMaterial",start);}staged.computeIfAbsent(part.group,g->new HashMap<>()).put(part.material,vao);part.data=null;release(part.bytes);retained-=part.bytes;cursor++;Performance.count("bbs.meshMaterialCommitted",0);}
            if(cursor==parts.size()){access.vaos.set(instance,staged);published=true;parts.clear();close();Performance.count("bbs.meshPublished",bytes);return true;}return false;
        }
        void cancel()throws ReflectiveOperationException {
            if(closed)return;ReflectiveOperationException failure=null;
            try{if(!published)for(Map<String,Object> group:staged.values())for(Object vao:group.values())try{access.delete.invoke(vao);}catch(ReflectiveOperationException ex){failure=ex;}}
            finally{parts.clear();if(!published)staged.clear();close();Performance.count("bbs.meshCancelled",0);}
            if(failure!=null)throw failure;
        }
        private void close(){if(closed)return;closed=true;release(retained);retained=0;endPlan();}
        private Object upload(Object data)throws ReflectiveOperationException {
            VaoBufferOwnership.begin();Object vao;
            try{vao=access.vao.newInstance(data);}
            catch(ReflectiveOperationException|RuntimeException|LinkageError ex){try{VaoBufferOwnership.end(null,false);}catch(Throwable cleanup){if(cleanup!=ex)ex.addSuppressed(cleanup);}throw ex;}
            try{VaoBufferOwnership.end(vao,true);return vao;}
            catch(RuntimeException|LinkageError ex){try{access.delete.invoke(vao);}catch(ReflectiveOperationException|RuntimeException cleanup){ex.addSuppressed(cleanup);}throw ex;}
        }
    }
    private static boolean reserve(long bytes){synchronized(budget){if(bytes<0||bytes>MAX_BYTES-pendingBytes)return false;pendingBytes+=bytes;peakBytes=Math.max(peakBytes,pendingBytes);return true;}}
    private static boolean beginPlan(){synchronized(budget){if(pendingPlans>=MAX_PLANS)return false;pendingPlans++;return true;}}
    private static void endPlan(){synchronized(budget){if(--pendingPlans<0)throw new IllegalStateException("Unbalanced cubic plan reservation");}}
    private static void release(long bytes){synchronized(budget){pendingBytes-=bytes;if(pendingBytes<0)throw new IllegalStateException("Unbalanced cubic staging reservation");}}
    static void failure(Throwable ex){while(ex instanceof InvocationTargetException target&&target.getCause()!=null)ex=target.getCause();lastFailure=ex.getClass().getSimpleName()+":"+ex.getMessage();}
    public static Map<String,Object> diagnostics(){synchronized(budget){return Map.of("pendingBytes",pendingBytes,"peakBytes",peakBytes,"limitBytes",MAX_BYTES,"pendingPlans",pendingPlans,"planLimit",MAX_PLANS,"lastFailure",lastFailure);}}
    private CubicMeshPreparation(){}
}
