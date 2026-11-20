package local.bbsgeoimporter;

import java.lang.reflect.*;
import java.util.*;

/** Only FormCellRenderer calls this bridge. Editors and scene rendering retain native rendering. */
public final class ListPreviewRequests {
    static final long FRAME_NANOS=4_000_000L, SETTLE_MILLIS=180;
    static final int MAX_IMAGES=256;
    static final long MAX_IMAGE_BYTES=32L*1024*1024;
    private static final String MODEL_FORM="mchorse.bbs_mod.forms.forms.ModelForm";
    private static final Map<Object,Frame> frames=new WeakHashMap<>();
    private static final Map<Object,Appearance> appearances=new WeakHashMap<>();
    private static final Map<Object,Double> scrollPositions=new WeakHashMap<>();
    private static final Map<String,Long> contents=new java.util.concurrent.ConcurrentHashMap<>();
    private static final LinkedHashMap<Key,Image> images=new LinkedHashMap<>(32,.75f,true);
    private static final Set<Key> failedImages=new LinkedHashSet<>();
    private static final Deque<Map<String,Object>> slow=new ArrayDeque<>();
    private static long imageBytes;
    private static long frameNumber;
    private static volatile String lastError="";
    private static Backend testBackend;
    private record Key(Object manager,String model,String appearance,Object binding,long content,int width,int height) {}
    private record Image(Backend backend,Object surface,long bytes) {}
    private record Appearance(int version,String data) {}
    private static final class Frame {int actions;long spent,pauseUntil;}
    /** GPU seam for CPU acceptance; the actual BBS cell bytecode remains in that test. */
    interface Backend {
        int[] size(Object context,int x,int y,int right,int bottom)throws ReflectiveOperationException;
        Object capture(Access access,Object form,Object context,int x,int y,int right,int bottom,int width,int height)throws ReflectiveOperationException;
        void draw(Object surface,Object context,int x,int y,int right,int bottom)throws ReflectiveOperationException;
        void release(Object surface)throws ReflectiveOperationException;
    }
    private static final ClassValue<Access> access=new ClassValue<>() {
        protected Access computeValue(Class<?> form){try{return new Access(form.getClassLoader());}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}}
    };
    static final class Access {
        final Method render,getModels,getModel,getValue,poseVersion,toData,settled;
        final Field model,models,requested;
        Backend backend;
        Access(ClassLoader loader)throws ReflectiveOperationException {
            Class<?> forms=Class.forName("mchorse.bbs_mod.forms.forms.Form",false,loader),context=Class.forName("mchorse.bbs_mod.ui.framework.UIContext",false,loader);
            Class<?> manager=Class.forName("mchorse.bbs_mod.cubic.model.ModelManager",false,loader);
            render=Class.forName("mchorse.bbs_mod.forms.FormUtilsClient",false,loader).getMethod("renderPreview",forms,context,int.class,int.class,int.class,int.class);
            getModels=Class.forName("mchorse.bbs_mod.BBSModClient",false,loader).getMethod("getModels");getModel=manager.getMethod("getModel",String.class);
            model=Class.forName(MODEL_FORM,false,loader).getField("model");getValue=model.getType().getMethod("get");
            poseVersion=forms.getMethod("getPoseVersion");toData=forms.getMethod("toData");settled=context.getMethod("hasNotScrolledForMore",long.class);
            models=manager.getDeclaredField("models");models.setAccessible(true);requested=manager.getDeclaredField("requested");requested.setAccessible(true);
        }
        Backend backend()throws ReflectiveOperationException {if(testBackend!=null)return testBackend;if(backend==null)backend=new ListThumbnailGpu(render.getDeclaringClass().getClassLoader());return backend;}
        void nativeRender(Object form,Object context,int x,int y,int right,int bottom)throws ReflectiveOperationException {
            long start=Performance.start();ListModelResidency.beginList();try{render.invoke(null,form,context,x,y,right,bottom);}finally{ListModelResidency.endList();Performance.end("bbs.listPreview",start);}
        }
    }
    public static void render(Object form,Object context,int x,int y,int right,int bottom){
        if(form==null||context==null||right<=x||bottom<=y)return;
        try {
            Access a=access.get(form.getClass());
            if(!isModelForm(form.getClass())){a.nativeRender(form,context,x,y,right,bottom);return;}
            String model=(String)a.getValue.invoke(a.model.get(form));
            if(!ImportedModelGroups.previewAllowed(model)){Performance.count("bbs.previewDisabled",0);return;}
            Object manager=a.getModels.invoke(null);
            int version=((Number)a.poseVersion.invoke(form)).intValue();Appearance appearance=appearances.get(form);
            if(appearance==null||appearance.version()!=version){appearance=new Appearance(version,String.valueOf(a.toData.invoke(form)));appearances.put(form,appearance);}
            Backend gpu=a.backend();int[] size=gpu.size(context,x,y,right,bottom);
            if(size[0]<=0||size[1]<=0)return;
            Key key=new Key(manager,model,appearance.data(),SharedModelAssets.previewToken(model),contents.getOrDefault(model,0L),size[0],size[1]);
            Image hit=images.get(key);
            if(hit!=null){hit.backend().draw(hit.surface(),context,x,y,right,bottom);Performance.count("bbs.thumbnailHit",0);return;}
            if(failedImages.contains(key)||ListModelResidency.failedRequest(manager,model))return;
            ListModelResidency.seen(manager,model);
            Frame frame=frames.computeIfAbsent(context,k->new Frame());
            if(!Boolean.TRUE.equals(a.settled.invoke(context,SETTLE_MILLIS))||System.currentTimeMillis()<frame.pauseUntil){Performance.count("bbs.previewScrollDeferred",0);return;}
            if(frame.actions>=1||frame.spent>=FRAME_NANOS){Performance.count("bbs.previewDeferred",0);return;}
            SharedModelAssets.beforeGet(manager,model);
            Object instance=((Map<?,?>)a.models.get(manager)).get(model);
            if(instance==null&&((Set<?>)a.requested.get(manager)).contains(model))return;
            if(instance==null&&!ListModelResidency.hasCapacity()){Performance.count("bbs.previewResidentDeferred",0);return;}
            long start=Performance.start();frame.actions++;
            try {
                if(instance==null){Performance.count("bbs.previewColdRequest",0);ListModelResidency.beginList();try{ListModelResidency.requested(manager,model);a.getModel.invoke(manager,model);}finally{ListModelResidency.endList();}return;}
                // A manager entry is published before BBS's queued GPU setup finishes.
                if(!ListPreviewSetup.ready(instance)){ListPreviewSetup.finish(instance);if(ListPreviewSetup.failed(instance)){if(failedImages.size()>=MAX_IMAGES)failedImages.remove(failedImages.iterator().next());failedImages.add(key);ListModelResidency.failed(manager,model);}return;}
                if(testBackend==null&&!ListTextureWarmup.prepare(instance,form,context,model))return;
                Object surface=gpu.capture(a,form,context,x,y,right,bottom,size[0],size[1]);
                if(surface==null)return;
                Image image=new Image(gpu,surface,(long)size[0]*size[1]*8);images.put(key,image);imageBytes+=image.bytes();
                evict();gpu.draw(surface,context,x,y,right,bottom);Performance.count("bbs.thumbnailBuilt",image.bytes());
                ListModelResidency.completed(manager,model);
            }finally{
                long nanos=System.nanoTime()-start;frame.spent+=nanos;Performance.event("bbs.thumbnailWork",nanos,0);
                if(nanos>50_000_000L){frame.pauseUntil=System.currentTimeMillis()+SETTLE_MILLIS;synchronized(slow){if(slow.size()==8)slow.removeFirst();slow.addLast(Map.of("model",model,"millis",nanos/1e6,"thread",Thread.currentThread().getName()));}}
            }
        }catch(InvocationTargetException ex){failure(ex.getCause());}
        catch(ReflectiveOperationException|LinkageError|RuntimeException ex){failure(ex);}
    }
    private static void failure(Throwable ex){lastError=ex.getClass().getSimpleName()+":"+ex.getMessage();Performance.count("bbs.previewBridgeFailure",0);/* Caption stays usable; never fall back to unlimited cold 3D draws. */}
    private static void evict()throws ReflectiveOperationException {
        var iterator=images.entrySet().iterator();
        while((images.size()>MAX_IMAGES||imageBytes>MAX_IMAGE_BYTES)&&iterator.hasNext()){Image image=iterator.next().getValue();iterator.remove();imageBytes-=image.bytes();image.backend().release(image.surface());Performance.count("bbs.thumbnailEvicted",image.bytes());}
    }
    public static void endFrame(Object context){frameNumber++;ListModelResidency.sweep();Frame frame=frames.get(context);if(frame!=null){frame.actions=0;frame.spent=0;}}
    static long frameNumber(){return frameNumber;}
    /** Runs after native Scroll.drag and before child cells, including scrollbar and inertia. */
    public static void scroll(Object view,Object context){
        try{Object scroll=view.getClass().getField("scroll").get(view);double position=((Number)scroll.getClass().getMethod("getScroll").invoke(scroll)).doubleValue();Double old=scrollPositions.put(scroll,position);
            if(scroll.getClass().getField("dragging").getBoolean(scroll)||(old!=null&&Math.abs(old-position)>.01))frames.computeIfAbsent(context,k->new Frame()).pauseUntil=System.currentTimeMillis()+SETTLE_MILLIS;
        }catch(ReflectiveOperationException|RuntimeException ex){Performance.count("bbs.previewScrollBridgeFailure",0);}
    }
    static void invalidate(String key){contents.merge(key,1L,Long::sum);ListModelResidency.retry(key);}
    public static Map<String,Object> diagnostics(){synchronized(slow){return Map.of("images",images.size(),"gpuBytes",imageBytes,"slow",List.copyOf(slow),"lastError",lastError,"modelResidency",ListModelResidency.diagnostics(),"meshStaging",CubicMeshPreparation.diagnostics());}}
    static void backendForTest(Backend backend)throws ReflectiveOperationException {for(Image image:images.values())image.backend().release(image.surface());images.clear();failedImages.clear();imageBytes=0;frames.clear();appearances.clear();scrollPositions.clear();testBackend=backend;}
    private static boolean isModelForm(Class<?> type){for(;type!=null;type=type.getSuperclass())if(type.getName().equals(MODEL_FORM))return true;return false;}
    private ListPreviewRequests(){}
}
