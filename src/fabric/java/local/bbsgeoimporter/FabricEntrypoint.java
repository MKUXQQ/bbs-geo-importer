package local.bbsgeoimporter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.*;
import java.util.concurrent.CompletableFuture;
public final class FabricEntrypoint implements ClientModInitializer {
    private static final ScanService SERVICE=new ScanService(FabricLoader.getInstance().getConfigDir(),FabricSources::collect,
            message -> System.getLogger("bbs_geo_importer").log(System.Logger.Level.INFO,message));
    @Override public void onInitializeClient() {
        registerReload();
        registerCommands();
        // Initial import waits for the effective client resource reload.
    }
    private static Class<?> mappedClass(String intermediary) throws ClassNotFoundException {
        return Class.forName(FabricLoader.getInstance().getMappingResolver().mapClassName("intermediary",intermediary));
    }
    private static void registerCommands(){
        try{
            Class<?> callback=Class.forName("net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback");Object event=callback.getField("EVENT").get(null);
            Object listener=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(p,m,a)->{
                if(m.getName().equals("register")){ClientCommands.register(a[0],SERVICE,(source,message)->{try{
                    var mapping=FabricLoader.getInstance().getMappingResolver();Class<?> text=mappedClass("net.minecraft.class_2561");String name=mapping.mapMethodName("intermediary","net.minecraft.class_2561","method_43470","(Ljava/lang/String;)Lnet/minecraft/class_5250;");ClientCommands.feedback(source,text.getMethod(name,String.class).invoke(null,message));
                }catch(Exception ex){throw new IllegalStateException(ex);}});return null;}return null;
            });
            Class.forName("net.fabricmc.fabric.api.event.Event").getMethod("register",Object.class).invoke(event,listener);
        }catch(Exception ex){System.getLogger("bbs_geo_importer").log(System.Logger.Level.WARNING,"Client commands unavailable; use F3+T and status report",ex);}
    }
    private static void registerReload() {
        try {
            // Use Fabric's public reload API; reflection keeps Minecraft names out of the shared/build classpath.
            Class<?> type=mappedClass("net.minecraft.class_3264");
            String clientName=FabricLoader.getInstance().getMappingResolver().mapFieldName("intermediary","net.minecraft.class_3264","field_14188","Lnet/minecraft/class_3264;");
            Object client=type.getField(clientName).get(null);
            Class<?> identifier=mappedClass("net.minecraft.class_2960");
            Object id=identifier.getConstructor(String.class,String.class).newInstance("bbs_geo_importer","scan");
            Class<?> listener=Class.forName("net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener");
            Object proxy=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(p,m,a) -> {
                if (m.getName().equals("getFabricId")) return id;
                if (m.getName().equals("toString")) return "BBS Geo importer reload listener";
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p==a[0];
                if (m.isDefault()) return InvocationHandler.invokeDefault(p,m,a);
                if (m.getReturnType()==CompletableFuture.class && a!=null && a.length==6) {
                    var mapping=FabricLoader.getInstance().getMappingResolver();
                    String get=mapping.mapMethodName("intermediary","net.minecraft.class_5912","method_14486","(Lnet/minecraft/class_2960;)Ljava/util/Optional;");
                    String open=mapping.mapMethodName("intermediary","net.minecraft.class_3298","method_14482","()Ljava/io/InputStream;");
                    String pack=mapping.mapMethodName("intermediary","net.minecraft.class_3298","method_14480","()Ljava/lang/String;");
                    String list=mapping.mapMethodName("intermediary","net.minecraft.class_3300","method_14488","(Ljava/lang/String;Ljava/util/function/Predicate;)Ljava/util/Map;");
                    return StartupReload.await(SERVICE,ResourceReaders.enumerate(ResourceReaders.create(a[1],mappedClass("net.minecraft.class_5912"),identifier,mappedClass("net.minecraft.class_3298"),get,open,pack),a[1],mappedClass("net.minecraft.class_3300"),list),(java.util.concurrent.Executor)a[4],value->{try{
                        String wait=mapping.mapMethodName("intermediary","net.minecraft.class_3302$class_4045","method_18352","(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;");
                        return (CompletableFuture<ReloadSnapshot>)mappedClass("net.minecraft.class_3302$class_4045").getMethod(wait,Object.class).invoke(a[0],value);
                    }catch(Exception ex){throw new java.util.concurrent.CompletionException(ex);}},(java.util.concurrent.Executor)a[5]);
                }
                throw new UnsupportedOperationException(m.toString());
            });
            Class<?> helper=Class.forName("net.fabricmc.fabric.api.resource.ResourceManagerHelper");
            Object instance=helper.getMethod("get",type).invoke(null,client);
            helper.getMethod("registerReloadListener",Class.forName("net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener")).invoke(instance,proxy);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Fabric resource loader API registration failed",e); }
    }
}
