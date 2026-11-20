package local.bbsgeoimporter;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Render hooks only publish addresses; no IO, reflection or model method calls. */
public final class RuntimeCapture {
    private static final int LIMIT=4096;
    private static final LinkedHashSet<ResourceEvidence.Observation> pending=new LinkedHashSet<>();
    private static final Set<ResourceEvidence.Observation> completed=new LinkedHashSet<>();
    private static final ThreadLocal<Frame> active=new ThreadLocal<>();
    private static final java.lang.ref.ReferenceQueue<Object> collected=new java.lang.ref.ReferenceQueue<>();
    private static final class IdentityRef extends java.lang.ref.WeakReference<Object> {
        final int hash;
        IdentityRef(Object value,boolean queued){super(value,queued?collected:null);hash=System.identityHashCode(value);}
        public int hashCode(){return hash;}
        public boolean equals(Object other){return this==other || other instanceof IdentityRef ref && get()!=null && get()==ref.get();}
    }
    private record Recent(long epoch,String model) {}
    private static final LinkedHashMap<IdentityRef,Recent> recentModels=new LinkedHashMap<>();
    private static void cleanRecent(){java.lang.ref.Reference<?> ref;while((ref=collected.poll())!=null)recentModels.remove(ref);}
    private static final AtomicLong generation=new AtomicLong();
    private static long dropped,observed;
    private static final class Frame {
        final Object renderer,animatable; final long epoch;
        String model;
        Frame(Object renderer,Object animatable){this.renderer=renderer;this.animatable=animatable;epoch=generation.get();}
    }
    public static void begin(Object renderer,Object animatable){active.set(new Frame(renderer,animatable));}
    public static void end(){active.remove();}
    public static void modelResult(Object resource){Frame f=active.get();if(f!=null)model(f.renderer,f.animatable,resource);}
    public static void textureResult(Object resource){Frame f=active.get();if(f!=null)texture(f.renderer,f.animatable,resource);}
    public static synchronized void model(Object renderer,Object animatable,Object resource) {
        Frame f=active.get();
        if(f!=null&&f.epoch==generation.get()&&f.renderer==renderer&&f.animatable==animatable){f.model=resource==null?null:resource.toString();if(f.model!=null&&animatable!=null){cleanRecent();recentModels.put(new IdentityRef(animatable,true),new Recent(f.epoch,f.model));if(recentModels.size()>LIMIT)recentModels.remove(recentModels.keySet().iterator().next());}}
    }
    public static void texture(Object renderer,Object animatable,Object resource) {
        Frame f=active.get();
        if(f==null||f.renderer!=renderer||f.animatable!=animatable||f.model==null||resource==null)return;
        try{enqueue(new ResourceEvidence.Observation(f.epoch,f.model,resource.toString(),null,"geckolib4"));}
        catch(IllegalArgumentException ignored){ /* Dynamic non-resource textures cannot be exported. */ }
    }
    public static synchronized void animationResult(Object result,Object animatable,Object resource){
        if(result==null||resource==null||animatable==null)return;
        Frame f=active.get();cleanRecent();Recent recent=f!=null?(f.animatable==animatable?new Recent(f.epoch,f.model):null):recentModels.get(new IdentityRef(animatable,false));
        if(recent==null||recent.model()==null||recent.epoch()!=generation.get())return;
        try{enqueue(new ResourceEvidence.Observation(recent.epoch(),recent.model(),null,resource.toString(),"geckolib4-animation"));}catch(IllegalArgumentException ignored){}
    }
    public static synchronized boolean enqueue(ResourceEvidence.Observation observation) {
        if(observation.generation()!=generation.get())return false;
        observed++;
        if(completed.contains(observation)||pending.contains(observation))return true;
        if(pending.size()>=LIMIT){dropped++;return false;}
        pending.add(observation);return true;
    }
    public static synchronized List<ResourceEvidence.Observation> drain(){var result=List.copyOf(pending);pending.clear();return result;}
    public static synchronized void acknowledge(ResourceEvidence.Observation observation){if(observation.generation()==generation.get()){completed.add(observation);if(completed.size()>16384)completed.remove(completed.iterator().next());}}
    static final Object publicationLock=new Object();
    public static long reload(){synchronized(publicationLock){synchronized(RuntimeCapture.class){pending.clear();completed.clear();recentModels.clear();return generation.incrementAndGet();}}}
    public static long generation(){return generation.get();}
    public static synchronized Map<String,Long> status(){return Map.of("generation",generation.get(),"observed",observed,"pending",(long)pending.size(),"completed",(long)completed.size(),"dropped",dropped);}
    private RuntimeCapture(){}
}
