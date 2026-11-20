package local.bbsgeoimporter;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.jar.JarFile;
import javax.tools.ToolProvider;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Executes actual transformed BBS 2.7 ModelLoader.work bytecode against CPU-only manager facades. */
public final class NativeModelLoadingAcceptance {
    private static final String LOADER="mchorse/bbs_mod/cubic/model/ModelLoader";
    private static final String MANAGER="mchorse/bbs_mod/cubic/model/ModelManager";
    private static final String INSTANCE="mchorse/bbs_mod/cubic/ModelInstance";
    private static final String BRIDGE="local/bbsgeoimporter/NativeModelLoading";

    public static void main(String[] args)throws Exception {
        Path jar=Path.of(args.length==0?"E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.7-1.20.1.jar":args[0]);
        Path base=Path.of("build/bbs-native-loader-cpu").toAbsolutePath();Files.createDirectories(base);
        compileFacades(base);Map<String,Long> sourceBefore=sourceCounters();
        try(JarFile archive=new JarFile(jar.toFile())){
            ClassNode raw=read(archive),transformed=read(archive);
            check(calls(raw,MANAGER,"loadModel")==1,"actual BBS work contains one native model load");
            check(NativeModelLoading.instrument(transformed),"actual private native work accepts terminal hook");
            verify(transformed);check(calls(transformed,MANAGER,"loadModel")==0&&calls(transformed,BRIDGE,"load")==1,"only original native call is replaced");
            byte[] once=bytes(transformed);check(NativeModelLoading.instrument(transformed)&&Arrays.equals(once,bytes(transformed)),"validated terminal hook is idempotent");
            Path output=base.resolve(LOADER+".class");Files.createDirectories(output.getParent());Files.write(output,once);
            ClassNode bad=read(archive);work(bad).name="unknownWork";reject(bad,"unknown native method");
            bad=read(archive);nativeCall(bad).desc="(Ljava/lang/Object;)L"+INSTANCE+";";reject(bad,"unknown native load signature");
            bad=read(archive);nativeCall(bad).setOpcode(Opcodes.INVOKESTATIC);reject(bad,"unexpected native opcode");
            bad=read(archive);MethodInsnNode call=nativeCall(bad);work(bad).instructions.insertBefore(call,new MethodInsnNode(call.getOpcode(),call.owner,call.name,call.desc,false));reject(bad,"two native load calls");
            bad=fromBytes(once);for(AbstractInsnNode instruction:work(bad).instructions)if(instruction instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.CHECKCAST&&type.desc.equals(INSTANCE)){type.desc="java/lang/Object";break;}reject(bad,"malformed existing hook cast");
        }
        PrintStream stderr=System.err;ByteArrayOutputStream expectedErrors=new ByteArrayOutputStream();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{base.toUri().toURL()},NativeModelLoadingAcceptance.class.getClassLoader());PrintStream capture=new PrintStream(expectedErrors,true,StandardCharsets.UTF_8)){
            System.setErr(capture);
            failure(loader,"null/first");failure(loader,"throw/first");
            retryRace(loader);publishedError(loader);scenePin(loader);originalCauses(loader);
        }finally{System.setErr(stderr);ListModelResidency.resetForTest();}
        check(expectedErrors.toString(StandardCharsets.UTF_8).contains("native fixture failure"),"real work retains its original Exception handling");
        check(sourceCounters().equals(sourceBefore),"terminal handling adds no source/hash/import/index work");
        System.out.println("NATIVE MODEL LOADER CPU PASS: actual BBS2.7 work bytecode executed; null/throw release one pending slot only on client; next good model loads; failed keys do not retry each frame; explicit retry/binding token/content-race work; scene pins and already-published instances survive; original runtime/error causes preserved. No game/GL execution.");
    }

    private static void failure(ClassLoader loader,String key)throws Exception {
        ListModelResidency.resetForTest();
        try(Worker worker=new Worker(loader)){
            worker.request(key);worker.awaitCalls(1);
            check(worker.requested().contains(key)&&!ListModelResidency.hasCapacity(),"worker records terminal failure without mutating client native requested set");
            ListModelResidency.sweep();
            check(!worker.requested().contains(key)&&ListModelResidency.hasCapacity(),"client sweep releases failed native request and single pending slot");
            check(number("loaderFailures")==1&&number("failedRequests")==1,"terminal failure is separately counted");
            for(int i=0;i<20;i++)check(ListModelResidency.failedRequest(worker.manager,key),"render guard retains failure without repeated worker calls");
            check(worker.calls()==1,"twenty failed render probes enqueue no new native work");
            worker.request("good/after-failure");worker.awaitCalls(2);
            check(worker.models().containsKey("good/after-failure"),"actual work loop continues to next valid model after null/exception");
            ListModelResidency.completed(worker.manager,"good/after-failure");check(ListModelResidency.hasCapacity(),"valid completion returns pending capacity");
            ListModelResidency.retry(key);check(!ListModelResidency.failedRequest(worker.manager,key),"explicit content invalidation permits a new attempt");
            worker.request(key);worker.awaitCalls(3);ListModelResidency.sweep();
            check(ListModelResidency.failedRequest(worker.manager,key),"a later actual failure can be cached again");
            bindingChange(key,()->check(!ListModelResidency.failedRequest(worker.manager,key),"new binding token invalidates stale load failure"));
        }
    }

    private static void retryRace(ClassLoader loader)throws Exception {
        ListModelResidency.resetForTest();
        try(Worker worker=new Worker(loader)){
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            worker.type.getField("entered").set(worker.manager,entered);worker.type.getField("release").set(worker.manager,release);
            String key="null/changed-while-loading";worker.request(key);
            check(entered.await(3,TimeUnit.SECONDS),"actual work reached controlled native loading point");
            ListModelResidency.retry(key);release.countDown();worker.awaitCalls(1);ListModelResidency.sweep();
            check(worker.requested().isEmpty()&&ListModelResidency.hasCapacity()&&!ListModelResidency.failedRequest(worker.manager,key),"old terminal result cannot poison content invalidated while its worker was running");
        }
    }

    private static void publishedError(ClassLoader loader)throws Exception {
        ListModelResidency.resetForTest();
        try(Worker worker=new Worker(loader)){
            String key="published-error/current";worker.request(key);worker.awaitCalls(1);Object published=worker.models().get(key);
            check(published!=null,"fixture publishes native model before throwing");
            ListModelResidency.seen(worker.manager,key);ListModelResidency.sweep();
            check(worker.models().get(key)==published&&worker.requested().contains(key)&&!ListModelResidency.failedRequest(worker.manager,key),"failure observer never clears a model that native work actually published");
            ListModelResidency.completed(worker.manager,key);
        }
    }

    private static void scenePin(ClassLoader loader)throws Exception {
        ListModelResidency.resetForTest();
        try(Worker worker=new Worker(loader)){
            String key="null/scene";ListModelResidency.requested(worker.manager,key);worker.requested().add(key);worker.queue.add(key);worker.awaitCalls(1);
            ListModelResidency.sweep(Long.MAX_VALUE);
            check(worker.requested().contains(key)&&!ListModelResidency.failedRequest(worker.manager,key)&&number("pinned")==1,"scene-owned native request is never changed by UI failure cleanup");
        }
    }

    private static void originalCauses(ClassLoader loader)throws Exception {
        try(Worker worker=new Worker(loader)){
            Throwable expected=(Throwable)worker.type.getField("failure").get(worker.manager);
            try{NativeModelLoading.load(worker.manager,"throw/direct");throw new AssertionError("native runtime expected");}catch(RuntimeException actual){check(actual==expected,"original native runtime exception identity retained");}
            Error fatal=(Error)worker.type.getField("fatal").get(worker.manager);
            try{NativeModelLoading.load(worker.manager,"error/direct");throw new AssertionError("native error expected");}catch(Error actual){check(actual==fatal,"original native Error identity retained");}
        }
    }

    private static final class Worker implements AutoCloseable {
        final Object manager;final Class<?> type;final Thread thread;final LinkedBlockingDeque<String> queue;
        volatile Throwable escaped;
        @SuppressWarnings("unchecked") Worker(ClassLoader loader)throws Exception {
            type=loader.loadClass(MANAGER.replace('/','.'));manager=type.getConstructor().newInstance();
            Class<?> nativeLoader=loader.loadClass(LOADER.replace('/','.'));Object instance=nativeLoader.getConstructor(type).newInstance(manager);
            Field field=nativeLoader.getDeclaredField("queue");field.setAccessible(true);queue=(LinkedBlockingDeque<String>)field.get(instance);
            Method work=nativeLoader.getDeclaredMethod("work");work.setAccessible(true);
            thread=new Thread(()->{try{work.invoke(instance);}catch(InvocationTargetException ex){escaped=ex.getCause();}catch(ReflectiveOperationException ex){escaped=ex;}},"BBS-real-work-cpu-fixture");thread.setDaemon(true);thread.start();
        }
        void request(String key)throws Exception {
            check(ListModelResidency.hasCapacity(),"new native list request has capacity");
            ListModelResidency.beginList();try{ListModelResidency.requested(manager,key);requested().add(key);queue.add(key);}finally{ListModelResidency.endList();}
        }
        int calls(){try{return ((java.util.concurrent.atomic.AtomicInteger)type.getField("calls").get(manager)).get();}catch(ReflectiveOperationException ex){throw new AssertionError(ex);}}
        void awaitCalls(int count)throws Exception {await(()->calls()>=count&&queue.isEmpty()&&thread.getState()==Thread.State.WAITING&&Arrays.stream(thread.getStackTrace()).anyMatch(frame->frame.getClassName().equals("java.util.concurrent.LinkedBlockingDeque")&&frame.getMethodName().equals("takeFirst")),"actual native work returns to its next blocking queue take");check(escaped==null,"actual native work did not escape: "+escaped);}
        @SuppressWarnings("unchecked") Map<String,Object> models()throws Exception {return (Map<String,Object>)type.getField("models").get(manager);}
        @SuppressWarnings("unchecked") Set<String> requested()throws Exception {return (Set<String>)type.getField("requested").get(manager);}
        public void close()throws Exception {thread.interrupt();thread.join(3_000);check(!thread.isAlive(),"bounded native work fixture shuts down via its original interrupt branch");check(escaped==null,"native work escaped: "+escaped);}
    }

    private static void bindingChange(String key,Runnable assertion)throws Exception {
        Field states=SharedModelAssets.class.getDeclaredField("previewStates");states.setAccessible(true);Object previous=states.get(null);
        Class<?> stateType=Class.forName("local.bbsgeoimporter.SharedModelAssets$PreviewState");Constructor<?> constructor=stateType.getDeclaredConstructor(SharedModelAssets.Binding.class,String.class,Object.class);constructor.setAccessible(true);
        Object state=constructor.newInstance(new SharedModelAssets.Binding(List.of(),"memory://changed.png",Map.of(),List.of()),"changed",new Object());
        try{states.set(null,Map.of(key,state));assertion.run();}finally{states.set(null,previous);}
    }

    private static void compileFacades(Path base)throws Exception {
        Map<String,String> sources=Map.of(
            INSTANCE,"""
                package mchorse.bbs_mod.cubic;
                public final class ModelInstance {public final String id;public boolean deleted;public ModelInstance(String id){this.id=id;}public void delete(){deleted=true;}}
                """,
            MANAGER,"""
                package mchorse.bbs_mod.cubic.model;
                import java.util.*;
                import java.util.concurrent.*;
                import java.util.concurrent.atomic.AtomicInteger;
                import mchorse.bbs_mod.cubic.ModelInstance;
                public final class ModelManager {
                    public final Map<String,ModelInstance> models=new ConcurrentHashMap<>();
                    public final Set<String> requested=new ClientSet();
                    public final AtomicInteger calls=new AtomicInteger();
                    public final RuntimeException failure=new IllegalStateException("native fixture failure");
                    public final Error fatal=new AssertionError("native fixture fatal");
                    public volatile CountDownLatch entered,release;
                    public ModelInstance loadModel(String key)throws InterruptedException {
                        calls.incrementAndGet();
                        CountDownLatch signal=entered,gate=release;if(signal!=null)signal.countDown();if(gate!=null)gate.await();
                        if(key.startsWith("null/"))return null;
                        if(key.startsWith("throw/"))throw failure;
                        if(key.startsWith("error/"))throw fatal;
                        ModelInstance model=new ModelInstance(key);models.put(key,model);
                        if(key.startsWith("published-error/"))throw failure;
                        return model;
                    }
                    private void forget(String key){ModelInstance model=models.remove(key);requested.remove(key);if(model!=null)model.delete();}
                    private static final class ClientSet extends HashSet<String> {
                        final Thread client=Thread.currentThread();
                        public boolean remove(Object key){if(Thread.currentThread()!=client)throw new AssertionError("worker touched native requested HashSet");return super.remove(key);}
                    }
                }
                """);
        List<String> arguments=new ArrayList<>(List.of("-proc:none","--release","17","-encoding","UTF-8","-d",base.toString()));
        for(var entry:sources.entrySet()){Path source=base.resolve(entry.getKey()+".java");Files.createDirectories(source.getParent());Files.writeString(source,entry.getValue());arguments.add(source.toString());}
        var compiler=ToolProvider.getSystemJavaCompiler();check(compiler!=null,"JDK compiler available for CPU facades");
        check(compiler.run(null,null,null,arguments.toArray(String[]::new))==0,"minimal CPU facades compile");
    }

    private static void await(BooleanSupplier ready,String why)throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!ready.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(2);check(ready.getAsBoolean(),why);}
    private static long calls(ClassNode node,String owner,String name){long count=0;for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(name))count++;return count;}
    private static MethodNode work(ClassNode node){return node.methods.stream().filter(method->method.name.equals("work")).findFirst().orElseThrow();}
    private static MethodInsnNode nativeCall(ClassNode node){for(AbstractInsnNode instruction:work(node).instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals(MANAGER)&&call.name.equals("loadModel"))return call;throw new AssertionError("actual native load call missing");}
    private static void reject(ClassNode node,String why){byte[] before=bytes(node);check(!NativeModelLoading.instrument(node),why+" rejected");check(Arrays.equals(before,bytes(node)),why+" leaves target unchanged");}
    private static ClassNode read(JarFile jar)throws Exception {try(InputStream stream=jar.getInputStream(jar.getJarEntry(LOADER+".class"))){return fromBytes(stream.readAllBytes());}}
    private static ClassNode fromBytes(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static byte[] bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}
    private static void verify(ClassNode node)throws Exception {for(MethodNode method:node.methods)if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new Analyzer<>(new BasicVerifier()).analyze(node.name,method);}
    private static int number(String key){return ((Number)ListModelResidency.diagnostics().get(key)).intValue();}
    private static Map<String,Long> sourceCounters(){Map<String,Long> out=new TreeMap<>();for(var entry:Performance.snapshot().entrySet())if(entry.getKey().startsWith("startup.")||entry.getKey().startsWith("source.")||entry.getKey().startsWith("catalog.")||entry.getKey().startsWith("import.")||entry.getKey().startsWith("index."))out.put(entry.getKey(),((Number)((Map<?,?>)entry.getValue()).get("count")).longValue());return out;}
    private static void check(boolean condition,String why){if(!condition)throw new AssertionError(why);}
}
