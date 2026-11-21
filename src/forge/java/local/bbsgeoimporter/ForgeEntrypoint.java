package local.bbsgeoimporter;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.FMLEnvironment;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
@Mod("bbs_geo_importer")
public final class ForgeEntrypoint {
    private static final Queue<Runnable> CLIENT_REFRESHES=new ConcurrentLinkedQueue<>();
    public ForgeEntrypoint() {
        if(!FMLEnvironment.dist.isClient()||!ClientBootstrap.claim(true,getClass().getClassLoader())) return;
        var service=new ScanService(FMLPaths.CONFIGDIR.get(),()->{
            try {return (java.util.List<GeoImporter.ModSource>)Class.forName("local.bbsgeoimporter.FabricSources").getMethod("collect").invoke(null);}
            catch(ReflectiveOperationException | LinkageError absent) {return LoaderSources.collect(List.of());}
        },s->System.getLogger("bbs_geo_importer").log(System.Logger.Level.INFO,s));
        service.bbsRefresh(CLIENT_REFRESHES::add);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ClientTickEvent event)->{
            if(event.phase!=net.minecraftforge.event.TickEvent.Phase.END)return;
            Runnable task=CLIENT_REFRESHES.poll();if(task!=null)task.run();
        });
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener((net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event)->event.enqueueWork(()->{service.startup();}));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.RegisterClientCommandsEvent event)->{
            try{ClientCommands.register(event.getDispatcher(),service,(source,message)->{try{
                Class<?> text=Class.forName("net.minecraft.network.chat.Component");ClientCommands.feedback(source,text.getMethod("m_237113_",String.class).invoke(null,message));
            }catch(Exception ex){throw new IllegalStateException(ex);}});}catch(Exception ex){System.getLogger("bbs_geo_importer").log(System.Logger.Level.WARNING,"Client commands unavailable",ex);}
        });
        var bus=net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener((net.minecraftforge.client.event.RegisterClientReloadListenersEvent e)->e.registerReloadListener(
            (net.minecraft.server.packs.resources.PreparableReloadListener) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{net.minecraft.server.packs.resources.PreparableReloadListener.class},(proxy,method,args)->{
                if(method.isDefault())return java.lang.reflect.InvocationHandler.invokeDefault(proxy,method,args);
                if(method.getName().equals("toString"))return "BBS Geo reload";
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return proxy==args[0];
                if(args==null||args.length!=6)throw new UnsupportedOperationException(method.toString());
                Object manager=args[1];
                try {
                    return StartupReload.await(service,ResourceReaders.platform(manager,net.minecraft.server.packs.resources.ResourceProvider.class,net.minecraft.resources.ResourceLocation.class,net.minecraft.server.packs.resources.Resource.class,net.minecraft.server.packs.resources.ResourceManager.class,net.minecraft.server.packs.PackResources.class,
                        "m_213713_","m_215507_","m_215506_","m_247173_","m_214159_","m_7536_","m_5542_"),(java.util.concurrent.Executor)args[4],value->{try{return (java.util.concurrent.CompletableFuture<ReloadSnapshot>)net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier.class.getMethod("m_6769_",Object.class).invoke(args[0],value);}catch(ReflectiveOperationException ex){throw new java.util.concurrent.CompletionException(ex);}},(java.util.concurrent.Executor)args[5]);
                }catch(Exception | LinkageError ex){return java.util.concurrent.CompletableFuture.failedFuture(ex);}
            })));
    }
}
