package local.bbsgeoimporter;

/** A native Forge installation owns its startup, even if Connector exposes Fabric's API. */
public final class ClientBootstrap {
    private static boolean claimed;
    public static synchronized boolean claim(boolean forge,ClassLoader loader){
        if(claimed)return false;
        if(!forge)try{Class.forName("net.minecraftforge.fml.loading.FMLLoader",false,loader);return false;}
        catch(ClassNotFoundException absent){/* Native Fabric. */}
        claimed=true;
        return true;
    }
    private ClientBootstrap(){}
}
