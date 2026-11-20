package local.bbsgeoimporter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Startup manifest and the real BBS settings module. Opening it never reads sources or files. */
public final class ModelManagement {
    public static final String MODULE_ID="bbs_geo_models";
    private static final String CATEGORY="sources", PREFIX=MODULE_ID+".config.";
    public record SourceInfo(String id,String name,String version,int geoCount,boolean ready) {
        public SourceInfo {
            Objects.requireNonNull(id,"source id");
            if(id.isBlank())throw new IllegalArgumentException("empty source id");
            name=name==null||name.isBlank()?id:name;
            version=version==null?"":version;
            geoCount=Math.max(0,geoCount);
        }
    }
    private static volatile Map<String,Boolean> preferences=Map.of();
    private static volatile List<SourceInfo> manifest=List.of();
    private static volatile BiConsumer<String,Boolean> changed=(id,enabled)->{};
    private static Path settingsFile;
    private static long configuration;
    private static Object registeredManager,registeredModule,registeredL10n;
    private static Set<String> registeredIds=Set.of();
    private static volatile String lastError="";

    private ModelManagement(){}

    /** config is the instance config directory, not a source/container directory. */
    public static synchronized void configure(Path config){
        Path file=Objects.requireNonNull(config).resolve("bbs/mod-model-management.json").toAbsolutePath().normalize();
        if(file.equals(settingsFile))return;
        settingsFile=file;configuration++;preferences=Map.of();manifest=List.of();
        registeredManager=null;registeredModule=null;registeredL10n=null;registeredIds=Set.of();lastError="";
        long start=Performance.start();
        try{
            if(Files.isRegularFile(file)){
                String json=Files.readString(file,StandardCharsets.UTF_8);
                JsonElement parsed=JsonParser.parseString(json);
                JsonObject sources=parsed.isJsonObject()&&parsed.getAsJsonObject().has(CATEGORY)
                    &&parsed.getAsJsonObject().get(CATEGORY).isJsonObject()?parsed.getAsJsonObject().getAsJsonObject(CATEGORY):null;
                if(sources!=null){
                    Map<String,Boolean> values=new LinkedHashMap<>();
                    for(var entry:sources.entrySet())if(entry.getValue().isJsonPrimitive()){
                        var value=entry.getValue().getAsJsonPrimitive();
                        if(value.isBoolean())values.put(entry.getKey(),value.getAsBoolean());
                        else if(value.isNumber())values.put(entry.getKey(),value.getAsInt()!=0);
                    }
                    preferences=Map.copyOf(values);
                }
                Performance.count("settings.configBytes",json.getBytes(StandardCharsets.UTF_8).length);
            }
        }catch(Exception ex){lastError="Settings read: "+ex;}
        finally{Performance.end("settings.configRead",start);}
    }

    public static boolean enabled(String id){return preferences.getOrDefault(id,true);}
    public static List<SourceInfo> manifest(){return manifest;}
    public static boolean pendingRestart(String id){
        if(!enabled(id))return false;
        for(SourceInfo source:manifest)if(source.id().equals(id))return !source.ready();
        return false;
    }
    public static void onChanged(BiConsumer<String,Boolean> listener){changed=listener==null?(id,on)->{}:listener;}

    /** Called only by startup publication. All UI rows come from this immutable manifest. */
    public static synchronized void publishManifest(List<SourceInfo> sources){
        LinkedHashMap<String,SourceInfo> unique=new LinkedHashMap<>();
        for(SourceInfo source:sources)unique.put(source.id(),source);
        manifest=List.copyOf(unique.values());
    }

    /** Must be called on the client thread after BBS settings and l10n are initialized. */
    public static boolean registerOnClient(){
        ClassLoader context=Thread.currentThread().getContextClassLoader(),own=ModelManagement.class.getClassLoader();
        if(registerOnClient(context==null?own:context))return true;
        return context!=own&&registerOnClient(own);
    }

    @SuppressWarnings("unchecked")
    static synchronized boolean registerOnClient(ClassLoader loader){
        if(settingsFile==null){lastError="Settings config directory not configured";return false;}
        try{
            Class<?> bbs=Class.forName("mchorse.bbs_mod.BBSMod",true,loader);
            Object manager=bbs.getMethod("getSettings").invoke(null);
            Class<?> client=Class.forName("mchorse.bbs_mod.BBSModClient",true,loader);
            Object l10n=client.getMethod("getL10n").invoke(null);
            if(manager==null||l10n==null)return false;
            Map<String,Object> modules=(Map<String,Object>)manager.getClass().getField("modules").get(manager);
            Set<String> ids=new LinkedHashSet<>();for(SourceInfo source:manifest)ids.add(source.id());
            if(manager==registeredManager&&registeredModule!=null&&registeredIds.equals(ids)
                    &&modules.get(MODULE_ID)==registeredModule){
                registeredL10n=l10n;updateLabels(l10n);lastError="";return true;
            }
            Class<?> iconType=Class.forName("mchorse.bbs_mod.ui.utils.icons.Icon",true,loader);
            Object icon=Class.forName("mchorse.bbs_mod.ui.utils.icons.Icons",true,loader).getField("FOLDER").get(null);
            Class<?> builderType=Class.forName("mchorse.bbs_mod.settings.SettingsBuilder",true,loader);
            Object builder=builderType.getConstructor(iconType,String.class,File.class).newInstance(icon,MODULE_ID,settingsFile.toFile());
            builderType.getMethod("category",String.class).invoke(builder,CATEGORY);
            Class<?> baseType=Class.forName("mchorse.bbs_mod.settings.values.base.BaseValue",true,loader);
            Class<?> dataType=Class.forName("mchorse.bbs_mod.data.types.BaseType",true,loader);
            Constructor<?> boolData=Class.forName("mchorse.bbs_mod.data.types.ByteType",true,loader).getConstructor(boolean.class);
            Class<?> listenerType=Class.forName("mchorse.bbs_mod.settings.values.IValueListener",true,loader);
            // Historical switches remain hidden but serialized, so temporarily removed sources retain their choice.
            Set<String> allIds=new LinkedHashSet<>(ids);allIds.addAll(preferences.keySet());
            long token=configuration;
            for(String id:allIds){
                Object value=builderType.getMethod("getBoolean",String.class,boolean.class).invoke(builder,id,true);
                value.getClass().getMethod("fromData",dataType).invoke(value,boolData.newInstance(enabled(id)));
                if(!ids.contains(id))baseType.getMethod("invisible").invoke(value);
                Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{listenerType},(proxy,method,args)->{
                    if(method.getDeclaringClass()==Object.class)return switch(method.getName()){
                        case "toString"->"BBS Geo source switch "+id;
                        case "hashCode"->System.identityHashCode(proxy);
                        case "equals"->proxy==args[0];
                        default->null;
                    };
                    if(method.getName().equals("accept")&&args!=null&&args.length>0){
                        boolean on=Boolean.TRUE.equals(args[0].getClass().getMethod("get").invoke(args[0]));
                        sourceChanged(id,on,token);
                    }
                    return null;
                });
                baseType.getMethod("postCallback",listenerType).invoke(value,callback);
            }
            Object module=builderType.getMethod("getConfig").invoke(builder);
            updateLabels(l10n);
            modules.put(MODULE_ID,module);
            registeredManager=manager;registeredModule=module;registeredL10n=l10n;registeredIds=Set.copyOf(ids);
            lastError="";Performance.count("settings.register",manifest.size());return true;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){
            lastError="BBS settings registration: "+rootCause(ex);return false;
        }
    }

    private static void sourceChanged(String id,boolean on,long token){
        BiConsumer<String,Boolean> listener;
        synchronized(ModelManagement.class){
            if(token!=configuration||enabled(id)==on)return;
            Map<String,Boolean> values=new LinkedHashMap<>(preferences);values.put(id,on);preferences=Map.copyOf(values);
            listener=changed;
            try{if(registeredL10n!=null)updateLabels(registeredL10n);}
            catch(ReflectiveOperationException ex){lastError="Settings labels: "+rootCause(ex);}
        }
        Performance.count("settings.sourceToggle",0);
        try{listener.accept(id,on);}catch(RuntimeException ex){lastError="Settings visibility callback: "+ex;}
        // BaseValue.postNotify already queues this module through Settings.saveLater().
    }

    private static void updateLabels(Object l10n)throws ReflectiveOperationException{
        label(l10n,PREFIX+"title","Mod 模型管理");
        label(l10n,PREFIX+CATEGORY+".title","Mod 模型管理");
        label(l10n,PREFIX+CATEGORY+".tooltip","管理自动导入模型列表；每个来源的开关跨重启保留。");
        for(SourceInfo source:manifest){
            String version=source.version().isBlank()?"":" · "+source.version();
            String pending=pendingRestart(source.id())?"（下次启动加载）":"";
            String text=source.name()+" ["+source.id()+"]"+version+" · "+source.geoCount()+" 个 Geo · 加载该 Mod 全部模型"+pending;
            label(l10n,PREFIX+CATEGORY+"."+source.id(),text);
            label(l10n,PREFIX+CATEGORY+"."+source.id()+"-comment",pendingRestart(source.id())
                ?"此来源本次启动未准备可用缓存，开关已保存，下次启动加载。"
                :"关闭后从自动模型分类、搜索与预览中隐藏；不删除文件，不影响已有场景。");
        }
    }
    private static void label(Object l10n,String key,String text)throws ReflectiveOperationException{
        Object lang=l10n.getClass().getMethod("getKey",String.class,String.class).invoke(l10n,key,text);
        lang.getClass().getField("content").set(lang,text);
    }
    private static Throwable rootCause(Throwable error){
        while(error instanceof InvocationTargetException&&error.getCause()!=null)error=error.getCause();
        return error;
    }
    public static Map<String,Object> diagnostics(){
        return Map.of("registered",registeredModule!=null,"sources",manifest.size(),"lastError",lastError,"ui",ModelManagementUi.diagnostics());
    }
}
