package local.bbsgeoimporter;

import java.util.*;
import java.lang.ref.WeakReference;

/** Loader CPU preparation, followed by client-only material commits. */
public final class ListPreviewSetup {
    private static final Map<Object,List<Task>> pending=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Task> active=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final class Task implements Runnable {
        private enum State {PENDING,READY,FAILED,CANCELLED}
        private Runnable work;private CubicMeshPreparation.Plan plan;private final WeakReference<Object> owner;private final String id;private final boolean prepared;private State state=State.PENDING;
        Task(Runnable work,Object instance){owner=new WeakReference<>(instance);String key=null;try{key=(String)instance.getClass().getField("id").get(instance);}catch(ReflectiveOperationException ignored){}id=key;plan=CubicMeshPreparation.prepare(instance);prepared=plan!=null;this.work=prepared?null:work;}
        boolean listOnly(){return id!=null&&ListModelResidency.isListOnly(id);}
        // Native world drain must never steal UI-only work or run an entire prepared model.
        public synchronized void run(){if(plan==null&&!listOnly())step();}
        synchronized void step(){
            if(state!=State.PENDING)return;Object instance=owner.get();if(instance==null){cancel();return;}
            long start=Performance.start();try{
                if(plan!=null){if(plan.step(instance)){state=State.READY;plan=null;}}
                else if(work!=null){Runnable current=work;work=null;current.run();state=State.READY;}else state=State.READY;
            }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){CubicMeshPreparation.failure(ex);Performance.count("bbs.meshCommitFailure",0);try{if(plan!=null)plan.cancel();}catch(ReflectiveOperationException cancel){ex.addSuppressed(cancel);}plan=null;work=null;state=State.FAILED;}
            finally{Performance.end("bbs.modelGpuSetup",start);if(state!=State.PENDING)active.remove(this);}
        }
        synchronized boolean done(){return state!=State.PENDING;}
        synchronized boolean ready(){return state==State.READY;}
        synchronized boolean failed(){return state==State.FAILED;}
        synchronized boolean gated(){return prepared&&state!=State.READY;}
        synchronized void cancel(){try{if(plan!=null)plan.cancel();}catch(ReflectiveOperationException ex){CubicMeshPreparation.failure(ex);Performance.count("bbs.meshCancelFailure",0);}finally{plan=null;work=null;state=State.CANCELLED;active.remove(this);}}
    }
    public static Runnable wrap(Runnable work,Object instance){Task task=new Task(work,instance);synchronized(pending){pending.computeIfAbsent(instance,k->new ArrayList<>()).add(task);}active.add(task);return task;}
    static boolean ready(Object instance){synchronized(pending){List<Task> tasks=pending.get(instance);return tasks==null||tasks.stream().allMatch(Task::ready);}}
    static boolean failed(Object instance){synchronized(pending){List<Task> tasks=pending.get(instance);return tasks!=null&&tasks.stream().anyMatch(Task::failed);}}
    /** Only the intercepted cubic plans have a new readiness contract; BOBJ/onCpu remain native. */
    public static Object readyModel(Object instance){if(instance==null)return null;synchronized(pending){List<Task> tasks=pending.get(instance);return tasks!=null&&tasks.stream().anyMatch(Task::gated)?null:instance;}}
    static void finish(Object instance){List<Task> tasks;synchronized(pending){tasks=pending.get(instance);if(tasks!=null)tasks=List.copyOf(tasks);}if(tasks!=null)for(Task task:tasks)if(!task.done()){task.step();break;}}
    /** Executed before the native world drain. UI-only jobs are advanced exclusively by settled visible cells. */
    public static void worldFrame(){ListModelResidency.sweep();long start=System.nanoTime();for(Task task:active){if(!task.listOnly())task.step();if(System.nanoTime()-start>=2_000_000L)break;}}
    public static void cancel(Object instance){List<Task> tasks;synchronized(pending){tasks=pending.remove(instance);}if(tasks!=null)tasks.forEach(Task::cancel);}
    private ListPreviewSetup(){}
}
