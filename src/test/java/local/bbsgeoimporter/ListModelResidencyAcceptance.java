package local.bbsgeoimporter;

import java.util.*;

/** Pure native-manager seam: no source files, game, renderer or GL are used. */
public final class ListModelResidencyAcceptance {
    public static final class Instance {public final String id;boolean deleted;Instance(String id){this.id=id;}}
    private static final class Manager {
        private final Map<String,Instance> models=new HashMap<>();
        private final Set<String> requested=new HashSet<>();
        final List<String> forgotten=new ArrayList<>();
        final Thread client=Thread.currentThread();
        private void forget(String key){
            check(Thread.currentThread()==client,"native release stays on the calling client thread");
            Instance instance=models.remove(key);requested.remove(key);
            if(instance!=null){ListPreviewSetup.cancel(instance);instance.deleted=true;forgotten.add(key);}
        }
        void scanSources(){throw new AssertionError("residency must not scan sources");}
    }
    public static void main(String[] args)throws Exception {
        Map<String,Long> sourceBefore=sourceCounters();
        scope();browse();pin();pending();replacement();offscreen();latePublication();
        check(sourceCounters().equals(sourceBefore),"residency adds no source/hash/import/index work");
        ListModelResidency.resetForTest();
        System.out.println("LIST MODEL RESIDENCY CPU PASS: 100 completed UI models retain at most 8; one unfinished UI request at a time; 500 CPU-ready/GL-pending scroll requests cancel after offscreen timeout; closing frees pending work; not-yet-published loaders retain their slot; old queued completions cannot revive a re-requested key; pin and replacement identity preserved. No game/GL/source scan executed.");
    }
    private static void scope()throws Exception {
        ListModelResidency.resetForTest();Manager manager=new Manager();
        ListModelResidency.beginList();ListModelResidency.beginList();
        try{
            ListModelResidency.requested(manager,"nested");check(ListModelResidency.isListOnly("nested"),"nested list scope is UI-only");
            ListModelResidency.endList();ListModelResidency.requested(manager,"still-list");check(ListModelResidency.isListOnly("still-list"),"outer list scope survives inner end");
            Thread scene=new Thread(()->ListModelResidency.requested(manager,"other-thread"),"residency-scene-fixture");scene.start();scene.join();
            check(!ListModelResidency.isListOnly("other-thread"),"ThreadLocal list scope does not leak to another consumer thread");
        }finally{ListModelResidency.endList();}
        ListModelResidency.requested(manager,"nested");check(!ListModelResidency.isListOnly("nested"),"later non-list use pins the key");
    }
    private static void browse(){
        ListModelResidency.resetForTest();Manager manager=new Manager();
        for(int i=0;i<100;i++){check(ListModelResidency.hasCapacity(),"completed entries do not occupy pending capacity");load(manager,"ui/"+i);ListModelResidency.completed(manager,"ui/"+i);check(manager.models.size()<=8,"100 completed UI instances never exceed 8 native residents");}
        check(manager.models.keySet().equals(Set.of("ui/92","ui/93","ui/94","ui/95","ui/96","ui/97","ui/98","ui/99")),"oldest list-only native instances are released");
        check(number("resident")==8&&number("evictions")==92&&number("pinned")==0,"diagnostics distinguish resident, evictions and pinned");
        ListModelResidency.completed(manager,"ui/92");load(manager,"ui/100");ListModelResidency.completed(manager,"ui/100");
        check(manager.models.containsKey("ui/92")&&!manager.models.containsKey("ui/93"),"completion touches access order");
        check(ListModelResidency.hasCapacity(),"evicted historical UI-only keys never consume pending slots");
    }
    private static void pin(){
        ListModelResidency.resetForTest();Manager manager=new Manager();Instance scene=load(manager,"scene");ListModelResidency.completed(manager,"scene");
        ListModelResidency.requested(manager,"scene");
        for(int i=0;i<20;i++){load(manager,"other/"+i);ListModelResidency.completed(manager,"other/"+i);}
        check(manager.models.get("scene")==scene&&!scene.deleted,"any scene/editor consumer pins its original instance");
        check(manager.models.size()==9&&number("resident")==8&&number("pinned")==1,"scene instances are outside the UI-only LRU");
        ListModelResidency.beginList();try{ListModelResidency.requested(manager,"scene");}finally{ListModelResidency.endList();}
        check(!ListModelResidency.isListOnly("scene"),"later list requests cannot unpin a scene key");
    }
    private static void pending(){
        ListModelResidency.resetForTest();Manager manager=new Manager();List<Runnable> tasks=new ArrayList<>();
        for(int i=0;i<ListModelResidency.PENDING_LIMIT;i++){String key="pending/"+i;Instance instance=new Instance(key);tasks.add(ListPreviewSetup.wrap(()->{},instance));manager.models.put(key,instance);requestList(manager,key);}
        check(!ListModelResidency.hasCapacity()&&manager.forgotten.isEmpty(),"the native pending UI limit stops further requests without releasing incomplete setup");
        ListPreviewSetup.finish(manager.models.get("pending/0"));check(ListModelResidency.hasCapacity(),"a ready offscreen request can free a capacity slot");
        check(manager.forgotten.equals(List.of("pending/0"))&&manager.models.size()==ListModelResidency.PENDING_LIMIT-1,"only the ready unpinned pending entry is released");
        String sceneKey="pending/scene";Instance scene=new Instance(sceneKey);ListPreviewSetup.wrap(()->{},scene);manager.models.put(sceneKey,scene);requestList(manager,sceneKey);
        ListModelResidency.requested(manager,sceneKey);check(ListModelResidency.hasCapacity(),"pin removes its pending list reservation");
        check(manager.models.containsKey(sceneKey),"pinning pending work never releases it");for(Instance instance:manager.models.values())ListPreviewSetup.cancel(instance);
        ListModelResidency.resetForTest();manager=new Manager();Instance protectedInstance=load(manager,"protected");Runnable setup=ListPreviewSetup.wrap(()->{},protectedInstance);ListModelResidency.completed(manager,"protected");
        for(int i=0;i<8;i++){load(manager,"complete/"+i);ListModelResidency.completed(manager,"complete/"+i);}
        check(manager.models.get("protected")==protectedInstance&&!protectedInstance.deleted,"LRU eviction also refuses a newly pending instance");
        ListPreviewSetup.finish(protectedInstance);load(manager,"complete/8");ListModelResidency.completed(manager,"complete/8");
        check(protectedInstance.deleted,"the protected LRU entry becomes releasable only after setup finishes");
    }
    private static void replacement(){
        ListModelResidency.resetForTest();Manager manager=new Manager();load(manager,"replaced");ListModelResidency.completed(manager,"replaced");
        Instance replacement=new Instance("replaced");manager.models.put("replaced",replacement);
        for(int i=0;i<8;i++){load(manager,"replacement-peer/"+i);ListModelResidency.completed(manager,"replacement-peer/"+i);}
        check(manager.models.get("replaced")==replacement&&!replacement.deleted&&!manager.forgotten.contains("replaced"),"an old completion never deletes the newer native instance under the same key");
    }
    private static void offscreen(){
        ListModelResidency.resetForTest();Manager manager=new Manager();List<Runnable> nativeQueue=new ArrayList<>();List<Instance> instances=new ArrayList<>();int[] completed={0};long now=10_000;
        for(int i=0;i<500;i++){
            if(!ListModelResidency.hasCapacity()){now+=ListModelResidency.OFFSCREEN_MILLIS+1;ListModelResidency.sweep(now);}
            check(ListModelResidency.hasCapacity(),"offscreen pending cancellation permits the next scroll request");
            String key="scroll/"+i;Instance instance=new Instance(key);instances.add(instance);
            nativeQueue.add(ListPreviewSetup.wrap(()->completed[0]++,instance));manager.models.put(key,instance);requestList(manager,key);ListModelResidency.seen(manager,key,now);
            check(manager.models.size()<=ListModelResidency.PENDING_LIMIT,"CPU-fast/GL-slow scrolling obeys the separate pending bound");
        }
        ListModelResidency.sweep(now+ListModelResidency.OFFSCREEN_MILLIS-1);check(!manager.models.isEmpty(),"recently visible pending work survives the timeout grace");
        ListModelResidency.sweep(now+ListModelResidency.OFFSCREEN_MILLIS+1);
        check(manager.models.isEmpty()&&manager.requested.isEmpty()&&number("awaitingThumbnail")==0,"closing the list eventually forgets every published UI-only pending model and native request");
        for(int i=0;i<instances.size();i++){nativeQueue.get(i).run();ListPreviewSetup.finish(instances.get(i));check(instances.get(i).deleted&&ListPreviewSetup.ready(instances.get(i)),"cancelled setup stays finished after a late native queue drain");}
        check(completed[0]==0&&number("cancelled")==500,"late native work cannot complete any of the 500 cancelled jobs");
        ListModelResidency.resetForTest();manager=new Manager();Instance visible=new Instance("visible");ListPreviewSetup.wrap(()->{},visible);manager.models.put(visible.id,visible);requestList(manager,visible.id);ListModelResidency.seen(manager,visible.id,now);
        ListModelResidency.seen(manager,visible.id,now+ListModelResidency.OFFSCREEN_MILLIS-1);ListModelResidency.sweep(now+ListModelResidency.OFFSCREEN_MILLIS+1);
        check(manager.models.get(visible.id)==visible&&!visible.deleted,"visible cache misses refresh lastSeen and protect active preparation");
        ListModelResidency.requested(manager,visible.id);ListModelResidency.sweep(now+10*ListModelResidency.OFFSCREEN_MILLIS);
        check(manager.models.get(visible.id)==visible&&!visible.deleted,"scene-pinned pending work never follows offscreen cancellation");ListPreviewSetup.cancel(visible);
    }
    private static void latePublication(){
        ListModelResidency.resetForTest();Manager manager=new Manager();long now=50_000;
        for(int i=0;i<ListModelResidency.PENDING_LIMIT;i++){String key="loader/"+i;requestList(manager,key);ListModelResidency.seen(manager,key,now);}
        ListModelResidency.sweep(now+3*ListModelResidency.OFFSCREEN_MILLIS);
        check(!ListModelResidency.hasCapacity()&&manager.forgotten.isEmpty()&&manager.requested.size()==ListModelResidency.PENDING_LIMIT,"a loader not yet in the native map retains its slot rather than permitting duplicate late publication");
        List<Runnable> oldQueue=new ArrayList<>();List<Instance> oldInstances=new ArrayList<>();int[] completed={0};
        for(int i=0;i<ListModelResidency.PENDING_LIMIT;i++){String key="loader/"+i;Instance instance=new Instance(key);oldInstances.add(instance);oldQueue.add(ListPreviewSetup.wrap(()->completed[0]++,instance));manager.models.put(key,instance);}
        ListModelResidency.sweep(now+3*ListModelResidency.OFFSCREEN_MILLIS+1);
        check(manager.models.isEmpty()&&manager.requested.isEmpty()&&ListModelResidency.hasCapacity(),"once a late loader publishes, the next bounded sweep cancels it and frees a new visible request");
        String key="loader/0";requestList(manager,key);Instance fresh=new Instance(key);Runnable freshQueue=ListPreviewSetup.wrap(()->completed[0]++,fresh);manager.models.put(key,fresh);ListModelResidency.seen(manager,key,now+10_000);
        for(int i=0;i<oldQueue.size();i++){oldQueue.get(i).run();ListPreviewSetup.finish(oldInstances.get(i));}
        ListModelResidency.sweep(now+10_001);check(manager.models.get(key)==fresh&&!fresh.deleted&&completed[0]==0,"old queued wrappers cannot revive or delete a re-requested same-key instance");
        Instance replacement=new Instance(key);manager.models.put(key,replacement);ListPreviewSetup.cancel(fresh);
        ListModelResidency.sweep(now+20_000);
        check(manager.models.get(key)==replacement&&!replacement.deleted,"offscreen sweep also refuses a newer native map instance");freshQueue.run();ListPreviewSetup.finish(fresh);check(completed[0]==0,"replaced pending wrapper remains cancelled");
    }
    private static Instance load(Manager manager,String key){requestList(manager,key);Instance instance=new Instance(key);manager.models.put(key,instance);return instance;}
    private static void requestList(Manager manager,String key){ListModelResidency.beginList();try{ListModelResidency.requested(manager,key);manager.requested.add(key);}finally{ListModelResidency.endList();}}
    private static int number(String key){return ((Number)ListModelResidency.diagnostics().get(key)).intValue();}
    private static Map<String,Long> sourceCounters(){Map<String,Long> out=new TreeMap<>();for(var e:Performance.snapshot().entrySet())if(e.getKey().startsWith("startup.")||e.getKey().startsWith("source.")||e.getKey().startsWith("catalog.")||e.getKey().startsWith("import.")||e.getKey().startsWith("index."))out.put(e.getKey(),((Number)((Map<?,?>)e.getValue()).get("count")).longValue());return out;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
