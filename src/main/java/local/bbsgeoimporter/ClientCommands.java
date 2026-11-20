package local.bbsgeoimporter;
import java.lang.reflect.*;
import java.util.function.BiConsumer;
/** Registers only literal client commands; never forwards to a server dispatcher. */
public final class ClientCommands {
    public static void register(Object dispatcher,ScanService service,BiConsumer<Object,String> feedback)throws Exception{
        Class<?> literal=Class.forName("com.mojang.brigadier.builder.LiteralArgumentBuilder"),command=Class.forName("com.mojang.brigadier.Command");
        Object root=literal.getMethod("literal",String.class).invoke(null,"bbsgeo");
        for(String name:new String[]{"status"}){
            Object child=literal.getMethod("literal",String.class).invoke(null,name);
            Object action=Proxy.newProxyInstance(command.getClassLoader(),new Class<?>[]{command},(p,m,a)->{
                if(!m.getName().equals("run"))return null;
                Object source=a[0].getClass().getMethod("getSource").invoke(a[0]);
                feedback.accept(source,service.status());return 1;
            });
            literal.getMethod("executes",command).invoke(child,action);
            Class<?> builder=Class.forName("com.mojang.brigadier.builder.ArgumentBuilder");builder.getMethod("then",builder).invoke(root,child);
        }
        dispatcher.getClass().getMethod("register",literal).invoke(dispatcher,root);
    }
    public static void feedback(Object source,Object text){
        try{for(Method m:source.getClass().getMethods()){
            if(m.getName().equals("sendFeedback")&&m.getParameterCount()==1){m.invoke(source,text);return;}
            if(m.getParameterCount()==2&&m.getParameterTypes()[0]==java.util.function.Supplier.class&&m.getParameterTypes()[1]==boolean.class){m.invoke(source,(java.util.function.Supplier<Object>)()->text,false);return;}
        }}catch(Exception ex){System.getLogger("bbs_geo_importer").log(System.Logger.Level.WARNING,"Command feedback failed",ex);}
    }
}
