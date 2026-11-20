package local.bbsgeoimporter;

import java.lang.reflect.*;
import java.util.*;

/** Used only by BBS's morphing entry call, on the client thread. */
public final class MorphingListCache {
    private record Stamp(Object categories,long version,long visibleVersion,Set<?> disabled) {}
    private static final Map<Object,Stamp> stamps=new WeakHashMap<>();
    public static void setupForms(Object list,Object categories){
        Performance.count("bbs.morphingEnter",0);
        Method setup;
        try{setup=list.getClass().getMethod("setupForms",categories.getClass());}
        catch(NoSuchMethodException ex){
            setup=Arrays.stream(list.getClass().getMethods()).filter(m->m.getName().equals("setupForms")&&m.getParameterCount()==1&&m.getParameterTypes()[0].isInstance(categories)).findFirst().orElseThrow(()->new IllegalStateException("BBS setupForms unavailable",ex));
        }
        Stamp next=null;
        try{
            Class<?> settings=Class.forName("mchorse.bbs_mod.BBSSettings",false,list.getClass().getClassLoader());
            Object setting=settings.getField("disabledMorphFormCategories").get(null);
            next=new Stamp(categories,(long)categories.getClass().getMethod("getLastUpdate").invoke(categories),ImportedModelGroups.version(),Set.copyOf((Set<?>)setting.getClass().getMethod("get").invoke(setting)));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){/* Keep native setup when the stamp cannot be read. */}
        invoke(list,categories,setup,next);
    }
    private static void invoke(Object list,Object categories,Method setup,Stamp next){
        Stamp old=stamps.get(list);
        if(next!=null&&old!=null&&old.categories()==next.categories()&&old.version()==next.version()&&old.visibleVersion()==next.visibleVersion()&&old.disabled().equals(next.disabled())){
            Performance.count("bbs.morphingReuse",0);return;
        }
        stamps.remove(list);long start=System.nanoTime();
        try{setup.invoke(list,categories);if(next!=null)stamps.put(list,next);}
        catch(InvocationTargetException ex){if(ex.getCause() instanceof RuntimeException e)throw e;if(ex.getCause() instanceof Error e)throw e;throw new IllegalStateException(ex.getCause());}
        catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
        finally{Performance.end("bbs.morphingSetup",start);}
    }
}
