package local.bbsgeoimporter;

import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** One visible-list PNG decode at a time. Native TextureManager still owns upload/animation semantics. */
public final class ListTextureWarmup {
    private static final int MAX_ENCODED=8*1024*1024,MAX_DECODED=16*1024*1024;
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"BBS list PNG decoder");t.setDaemon(true);return t;});
    private record Resource(Object manager,Object link) {}
    private record Decoded(Access access,Object pixels,int bytes) {}
    private static final LinkedHashMap<Resource,Decoded> decoded=new LinkedHashMap<>();
    private static final LinkedHashMap<Resource,Set<String>> consumers=new LinkedHashMap<>();
    private static final Set<Resource> bypassed=new HashSet<>();
    private static int bytes;
    private static Job running;
    private static final class Job {final Resource resource;final Access access;volatile boolean done,cancelled;volatile Decoded result;Job(Resource resource,Access access){this.resource=resource;this.access=access;}}
    private static final ClassValue<Access> access=new ClassValue<>() {protected Access computeValue(Class<?> instance){try{return new Access(instance.getClassLoader());}catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}}};
    private static final class Access {
        final Method textures,modelTexture,getTexture,assets,decode,delete,value;
        final Field materialTextures,formTexture,provider,path,source,textureCache,animatedCache,failed;
        Access(ClassLoader loader)throws ReflectiveOperationException {
            Class<?> client=Class.forName("mchorse.bbs_mod.BBSModClient",false,loader),instance=Class.forName("mchorse.bbs_mod.cubic.ModelInstance",false,loader),form=Class.forName("mchorse.bbs_mod.forms.forms.ModelForm",false,loader),link=Class.forName("mchorse.bbs_mod.resources.Link",false,loader),pixels=Class.forName("mchorse.bbs_mod.utils.resources.Pixels",false,loader);
            textures=client.getMethod("getTextures");Class<?> manager=textures.getReturnType();getTexture=manager.getMethod("getTexture",link);provider=manager.getField("provider");assets=provider.getType().getMethod("getAsset",link);
            textureCache=manager.getField("textures");animatedCache=manager.getField("animatedTextures");failed=manager.getDeclaredField("failed");failed.setAccessible(true);
            decode=pixels.getMethod("fromPNGStream",InputStream.class);delete=pixels.getMethod("delete");modelTexture=instance.getMethod("getTexture");materialTextures=instance.getField("materialTextures");formTexture=form.getField("texture");value=formTexture.getType().getMethod("get");path=link.getField("path");source=link.getField("source");
        }
        boolean resident(Object manager,Object link)throws IllegalAccessException {return ((Map<?,?>)textureCache.get(manager)).get(link)!=null||((Map<?,?>)animatedCache.get(manager)).get(link)!=null;}
    }
    static synchronized boolean prepare(Object instance,Object form,Object context,String key)throws ReflectiveOperationException {
        drain();Access a=access.get(instance.getClass());Object manager=a.textures.invoke(null);
        Set<Object> links=new LinkedHashSet<>();Object override=a.value.invoke(a.formTexture.get(form));Object base=override==null?a.modelTexture.invoke(instance):override;if(base!=null)links.add(base);
        Object materials=a.materialTextures.get(instance);if(materials instanceof Map<?,?> map)links.addAll(map.values());
        for(Object link:links){
            if(link==null||link.getClass()!=a.path.getDeclaringClass())continue;
            String path=(String)a.path.get(link),source=(String)a.source.get(link);
            if(!path.toLowerCase(Locale.ROOT).endsWith(".png")||!source.equals("assets"))continue;
            Resource resource=new Resource(manager,link);consumers.computeIfAbsent(resource,r->new HashSet<>()).add(key);
            while(consumers.size()>4096)consumers.remove(consumers.keySet().iterator().next());
            if(a.resident(manager,link)||((Set<?>)a.failed.get(manager)).contains(link)||bypassed.contains(resource))continue;
            // Consume/upload one ready image before preparing another. A model's materials need not all fit in the CPU decode budget.
            if(decoded.containsKey(resource)){
                long start=Performance.start();try{a.getTexture.invoke(manager,link);}finally{Performance.end("bbs.previewTextureUpload",start);}
                if(!a.resident(manager,link))bypassed.add(resource);
                return false;
            }
            if(running==null){running=new Job(resource,a);Job job=running;worker.execute(()->decode(job));}
            Performance.count("bbs.previewTextureWaiting",0);return false;
        }
        return true;
    }
    private static void decode(Job job){
        long start=Performance.start();
        try {
            byte[] data;
            try(InputStream stream=(InputStream)job.access.assets.invoke(job.access.provider.get(job.resource.manager()),job.resource.link())){if(stream==null)return;data=stream.readNBytes(MAX_ENCODED+1);}
            if(data.length<24||data.length>MAX_ENCODED||ByteBuffer.wrap(data).getLong()!=0x89504e470d0a1a0aL)return;
            ByteBuffer header=ByteBuffer.wrap(data);int width=header.getInt(16),height=header.getInt(20);
            if(width<=0||height<=0||(long)width*height*4>MAX_DECODED)return;
            Object pixels=job.access.decode.invoke(null,new ByteArrayInputStream(data));
            if(pixels!=null)job.result=new Decoded(job.access,pixels,width*height*4);
        }catch(ReflectiveOperationException|IOException|RuntimeException ex){Performance.count("bbs.previewTextureDecodeFailure",0);}
        finally{Performance.end("bbs.previewTextureDecode",start);job.done=true;}
    }
    private static void drain()throws ReflectiveOperationException {
        Job job=running;if(job==null||!job.done)return;running=null;
        if(job.cancelled){if(job.result!=null)job.result.access().delete.invoke(job.result.pixels());return;}
        if(job.result==null){if(bypassed.size()>=512)bypassed.clear();bypassed.add(job.resource);Performance.count("bbs.previewTextureNativeFallback",0);return;}
        while(bytes+job.result.bytes()>MAX_DECODED&&!decoded.isEmpty()){Resource first=decoded.keySet().iterator().next();Decoded old=decoded.remove(first);bytes-=old.bytes();old.access().delete.invoke(old.pixels());}
        decoded.put(job.resource,job.result);bytes+=job.result.bytes();
    }
    /** Called at native getPixels HEAD; normal TextureManager executes the rest of its existing getTexture path. */
    public static synchronized Object take(Object manager,Object link){
        Decoded result=decoded.remove(new Resource(manager,link));if(result==null)return null;bytes-=result.bytes();Performance.count("bbs.previewTextureDecodeReused",result.bytes());return result.pixels();
    }
    public static synchronized void changed(Object manager,java.nio.file.Path path){
        try {
            Object provider=manager.getClass().getField("provider").get(manager);Object link=provider.getClass().getMethod("getLink",File.class).invoke(provider,path.toFile());
            if(link==null)return;Resource resource=new Resource(manager,link);Decoded old=decoded.remove(resource);
            if(old!=null){bytes-=old.bytes();old.access().delete.invoke(old.pixels());}
            if(running!=null&&running.resource.equals(resource))running.cancelled=true;bypassed.remove(resource);
            for(String key:consumers.getOrDefault(resource,Set.of()))ListPreviewRequests.invalidate(key);
        }catch(ReflectiveOperationException|RuntimeException ex){Performance.count("bbs.previewTextureChangeBridgeFailure",0);}
    }
    static synchronized void invalidateAllForTest()throws ReflectiveOperationException {for(Decoded d:decoded.values())d.access().delete.invoke(d.pixels());decoded.clear();bytes=0;if(running!=null)running.cancelled=true;consumers.clear();bypassed.clear();}
    private ListTextureWarmup(){}
}
