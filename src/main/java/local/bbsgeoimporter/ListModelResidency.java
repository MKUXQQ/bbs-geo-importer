package local.bbsgeoimporter;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Bounds list-only native instances. Source files, shared textures and 2D images are untouched. */
public final class ListModelResidency {
    static final int LIMIT=8;
    static final int PENDING_LIMIT=1;
    static final long OFFSCREEN_MILLIS=2_000;
    private static final ThreadLocal<Integer> listDepth=new ThreadLocal<>();
    private static final Map<String,Usage> usages=new HashMap<>();
    private static final LinkedHashMap<String,Entry> residents=new LinkedHashMap<>(16,.75f,true);
    private static final LinkedHashMap<String,Pending> unfinished=new LinkedHashMap<>();
    private static final Map<String,LoadFailure> loadFailures=new HashMap<>();
    private static final Map<String,Object> retryTokens=new HashMap<>();
    private static long evictions,cancelled,loaderFailures;
    private static String lastError="";
    private static final class Usage {
        final WeakReference<Object> manager;
        boolean pinned;
        Usage(Object manager,boolean pinned){this.manager=new WeakReference<>(manager);this.pinned=pinned;}
    }
    private static final class Pending {
        final WeakReference<Object> manager;
        WeakReference<Object> instance;
        boolean observed;
        boolean terminalFailure;
        String terminalReason;
        final Object bindingToken,retryToken;
        long lastSeen;
        Pending(Object manager,String key,long now){this.manager=new WeakReference<>(manager);lastSeen=now;bindingToken=SharedModelAssets.previewToken(key);retryToken=retryTokens.get(key);}
        void observe(Object current){if(!observed&&current!=null){instance=new WeakReference<>(current);observed=true;}}
    }
    private record LoadFailure(WeakReference<Object> manager,Object bindingToken,Object retryToken) {}
    /* A weak instance identity prevents an older completion from forgetting a replacement. */
    private record Entry(String key,WeakReference<Object> manager,WeakReference<Object> instance) {}
    private static final ClassValue<Access> access=new ClassValue<>() {
        protected Access computeValue(Class<?> type){
            try{return new Access(type);}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
        }
    };
    private static final class Access {
        final Field models,requested;
        final Method forget;
        Access(Class<?> type)throws ReflectiveOperationException {
            models=field(type,"models");models.setAccessible(true);
            requested=field(type,"requested");requested.setAccessible(true);
            forget=method(type,"forget",String.class);forget.setAccessible(true);
        }
        Map<?,?> models(Object manager)throws IllegalAccessException {return (Map<?,?>)models.get(manager);}
        Set<?> requested(Object manager)throws IllegalAccessException {return (Set<?>)requested.get(manager);}
    }

    public static void beginList(){Integer depth=listDepth.get();listDepth.set(depth==null?1:depth+1);}
    public static void endList(){Integer depth=listDepth.get();if(depth==null||depth<=1)listDepth.remove();else listDepth.set(depth-1);}

    /** Native getModel HEAD: every non-list consumer conservatively pins this key. */
    public static synchronized void requested(Object manager,String key){
        if(manager==null||key==null||key.isEmpty())return;
        boolean fromList=listDepth.get()!=null;Usage usage=usages.get(key);
        if(usage==null||usage.manager.get()!=manager){
            boolean pinned=!fromList||(usage!=null&&usage.pinned);
            usage=new Usage(manager,pinned);usages.put(key,usage);residents.remove(key);unfinished.remove(key);
        }else if(!fromList)usage.pinned=true;
        if(usage.pinned){residents.remove(key);unfinished.remove(key);return;}
        try{
            Object instance=access.get(manager.getClass()).models(manager).get(key);
            if(instance==null||!ListPreviewSetup.ready(instance)){
                Pending pending=unfinished.computeIfAbsent(key,k->new Pending(manager,key,System.currentTimeMillis()));
                pending.lastSeen=System.currentTimeMillis();pending.observe(instance);
            }
        }catch(ReflectiveOperationException|RuntimeException ex){
            // An unreadable manager is never safe to evict.
            usage.pinned=true;residents.remove(key);unfinished.remove(key);failure(ex);
        }
    }

    public static synchronized boolean isListOnly(String key){
        Usage usage=usages.get(key);return usage!=null&&!usage.pinned&&usage.manager.get()!=null;
    }

    /** Native loader worker callback. It records a terminal result; all native mutations stay in sweep. */
    public static synchronized void loaderFinished(Object manager,String key,Object result,Throwable error){
        Pending pending=unfinished.get(key);Usage usage=usages.get(key);
        if(pending==null||pending.manager.get()!=manager||usage==null||usage.manager.get()!=manager||usage.pinned)return;
        if(result==null||error!=null){
            pending.terminalFailure=true;
            pending.terminalReason=error==null?"Native model loader returned null":error.getClass().getSimpleName()+":"+error.getMessage();
        }
    }

    /** Render guard: a failed key is retried only after content/binding invalidation, never every frame. */
    public static synchronized boolean failedRequest(Object manager,String key){
        LoadFailure failed=loadFailures.get(key);if(failed==null)return false;
        Usage usage=usages.get(key);
        if(failed.manager().get()!=manager||failed.bindingToken()!=SharedModelAssets.previewToken(key)||failed.retryToken()!=retryTokens.get(key)||usage!=null&&usage.pinned){loadFailures.remove(key);return false;}
        return true;
    }

    /** A local content event also invalidates a failure that is still finishing on the worker. */
    public static synchronized void retry(String key){
        if(key==null||key.isEmpty())return;
        loadFailures.remove(key);retryTokens.put(key,new Object());
    }

    /** A visible cache miss keeps only its already-requested native work alive. */
    public static void seen(Object manager,String key){seen(manager,key,System.currentTimeMillis());}
    static synchronized void seen(Object manager,String key,long now){
        Pending pending=unfinished.get(key);Usage usage=usages.get(key);
        if(manager==null||pending==null||pending.manager.get()!=manager||usage==null||usage.pinned||usage.manager.get()!=manager)return;
        pending.lastSeen=now;
        try{pending.observe(access.get(manager.getClass()).models(manager).get(key));}
        catch(ReflectiveOperationException|RuntimeException ex){failure(ex);}
    }

    /** Client frame maintenance; a closed list simply stops calling seen. Never waits for loaders. */
    public static void sweep(){sweep(System.currentTimeMillis());}
    public static synchronized void sweep(long now){
        var iterator=unfinished.entrySet().iterator();int checked=0;
        while(iterator.hasNext()&&checked++<PENDING_LIMIT){
            var entry=iterator.next();Pending pending=entry.getValue();Object manager=pending.manager.get();Usage usage=usages.get(entry.getKey());
            if(manager==null||usage==null||usage.pinned||usage.manager.get()!=manager){iterator.remove();continue;}
            try{
                Access api=access.get(manager.getClass());Object current=api.models(manager).get(entry.getKey());
                if(pending.terminalFailure&&current==null){
                    // The worker has returned/failed, so no late publication remains to race a retry.
                    // This native HashSet is client-owned; never clear it from loaderFinished.
                    if(api.models(manager).get(entry.getKey())!=null)continue;
                    api.requested(manager).remove(entry.getKey());
                    if(pending.bindingToken==SharedModelAssets.previewToken(entry.getKey())&&pending.retryToken==retryTokens.get(entry.getKey()))
                        loadFailures.put(entry.getKey(),new LoadFailure(pending.manager,pending.bindingToken,pending.retryToken));
                    lastError=pending.terminalReason+": "+entry.getKey();loaderFailures++;Performance.count("bbs.listNativeLoadFailed",0);
                    residents.remove(entry.getKey());iterator.remove();continue;
                }
                if(pending.observed&&pending.instance.get()!=current){iterator.remove();continue;}
                pending.observe(current);
                if(now-pending.lastSeen<OFFSCREEN_MILLIS||current==null)continue;
                // Unlike completed-LRU eviction, offscreen cancellation may delete unfinished setup.
                // Native delete's hook cancels its plan and makes the queued old Runnable a no-op.
                long before=evictions;
                if(release(new Entry(entry.getKey(),pending.manager,new WeakReference<>(current)),true)){
                    residents.remove(entry.getKey());iterator.remove();
                    if(evictions!=before){cancelled++;Performance.count("bbs.listPendingCancelled",0);}
                }
            }catch(ReflectiveOperationException|RuntimeException ex){failure(ex);}
        }
    }

    /** Called on the client thread before requesting another cold list model. */
    public static synchronized boolean hasCapacity(){
        var iterator=unfinished.entrySet().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();Object manager=entry.getValue().manager.get();Usage usage=usages.get(entry.getKey());
            if(manager==null||usage==null||usage.pinned||usage.manager.get()!=manager)iterator.remove();
        }
        if(unfinished.size()<PENDING_LIMIT)return true;
        // Ready but never-captured offscreen work must not occupy all request slots forever.
        iterator=unfinished.entrySet().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();Pending pending=entry.getValue();Object manager=pending.manager.get();
            if(manager==null){iterator.remove();continue;}
            try{
                Object instance=access.get(manager.getClass()).models(manager).get(entry.getKey());
                if(pending.observed&&pending.instance.get()!=instance){iterator.remove();continue;}
                pending.observe(instance);
                if(instance!=null&&ListPreviewSetup.ready(instance)&&release(new Entry(entry.getKey(),pending.manager,new WeakReference<>(instance)))){
                    residents.remove(entry.getKey());iterator.remove();
                    if(unfinished.size()<PENDING_LIMIT)return true;
                }
            }catch(ReflectiveOperationException|RuntimeException ex){failure(ex);}
        }
        return unfinished.size()<PENDING_LIMIT;
    }

    /** A successful thumbnail permits native model eviction; it does not evict that thumbnail. */
    public static synchronized void completed(Object manager,String key){
        if(manager==null||key==null||key.isEmpty())return;
        Usage usage=usages.get(key);
        if(usage==null||usage.manager.get()!=manager||usage.pinned)return;
        unfinished.remove(key);
        try{
            Object instance=access.get(manager.getClass()).models(manager).get(key);
            if(instance==null){residents.remove(key);return;}
            residents.put(key,new Entry(key,new WeakReference<>(manager),new WeakReference<>(instance)));
            var iterator=residents.entrySet().iterator();
            while(residents.size()>LIMIT&&iterator.hasNext())if(release(iterator.next().getValue()))iterator.remove();
        }catch(ReflectiveOperationException|RuntimeException ex){failure(ex);}
    }
    /** A failed thumbnail cannot hold the single unfinished UI slot forever. Scene pins stay intact. */
    static synchronized void failed(Object manager,String key){
        Usage usage=usages.get(key);if(usage==null||usage.manager.get()!=manager)return;
        unfinished.remove(key);
        if(usage.pinned)return;
        try{Object instance=access.get(manager.getClass()).models(manager).get(key);if(instance!=null)release(new Entry(key,new WeakReference<>(manager),new WeakReference<>(instance)),true);residents.remove(key);}
        catch(ReflectiveOperationException|RuntimeException ex){failure(ex);}
    }

    /** Uses BBS's native forget, including its requested-set cleanup and instance.delete(). */
    private static boolean release(Entry entry){return release(entry,false);}
    private static boolean release(Entry entry,boolean cancelPending){
        Object manager=entry.manager().get(),expected=entry.instance().get();Usage usage=usages.get(entry.key());
        if(manager==null||expected==null||usage==null||usage.manager.get()!=manager||usage.pinned)return true;
        try{
            Access api=access.get(manager.getClass());
            if(api.models(manager).get(entry.key())!=expected)return true;
            if(!cancelPending&&!ListPreviewSetup.ready(expected))return false;
            // Check again immediately before the native mutation; never target a newer instance.
            if(api.models(manager).get(entry.key())!=expected)return true;
            api.forget.invoke(manager,entry.key());
            if(api.models(manager).get(entry.key())==expected){lastError="Native forget retained "+entry.key();return false;}
            evictions++;Performance.count("bbs.listModelEvicted",0);return true;
        }catch(ReflectiveOperationException|RuntimeException ex){failure(ex);return false;}
    }

    public static synchronized Map<String,Object> diagnostics(){
        int pinned=0;for(Usage usage:usages.values())if(usage.pinned&&usage.manager.get()!=null)pinned++;
        return Map.of("resident",residents.size(),"evictions",evictions,"pinned",pinned,"awaitingThumbnail",unfinished.size(),"cancelled",cancelled,"limit",LIMIT,"pendingLimit",PENDING_LIMIT,"failedRequests",loadFailures.size(),"loaderFailures",loaderFailures,"lastError",lastError);
    }
    private static Field field(Class<?> type,String name)throws NoSuchFieldException {
        for(Class<?> c=type;c!=null;c=c.getSuperclass())try{return c.getDeclaredField(name);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(type.getName()+"."+name);
    }
    private static Method method(Class<?> type,String name,Class<?> argument)throws NoSuchMethodException {
        for(Class<?> c=type;c!=null;c=c.getSuperclass())try{return c.getDeclaredMethod(name,argument);}catch(NoSuchMethodException ignored){}
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    private static void failure(Throwable ex){
        if(ex instanceof InvocationTargetException target&&target.getCause()!=null)ex=target.getCause();
        lastError=ex.getClass().getSimpleName()+":"+ex.getMessage();Performance.count("bbs.listResidencyFailure",0);
    }
    static synchronized void resetForTest(){listDepth.remove();usages.clear();residents.clear();unfinished.clear();loadFailures.clear();retryTokens.clear();evictions=0;cancelled=0;loaderFailures=0;lastError="";}
    private ListModelResidency(){}
}
