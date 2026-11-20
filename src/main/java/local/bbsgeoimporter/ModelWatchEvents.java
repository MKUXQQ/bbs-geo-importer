package local.bbsgeoimporter;

import java.io.File;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Mirrors BBS's provider/path gate before touching our list cache. Never discovers Mod sources. */
public final class ModelWatchEvents {
    private static final String MANAGER="mchorse.bbs_mod.cubic.model.ModelManager";
    private static final String SECTION="mchorse.bbs_mod.forms.sections.ModelFormSection";
    private static final ClassValue<Access> access=new ClassValue<>() {
        protected Access computeValue(Class<?> owner){
            try{return new Access(owner.getClassLoader());}
            catch(ReflectiveOperationException ex){throw new IllegalStateException(ex);}
        }
    };
    private static final class Access {
        final Method provider,manager,link,assets,listing,reloadable;
        final Field path;
        Access(ClassLoader loader)throws ReflectiveOperationException {
            Class<?> bbs=Class.forName("mchorse.bbs_mod.BBSMod",false,loader);
            Class<?> client=Class.forName("mchorse.bbs_mod.BBSModClient",false,loader);
            Class<?> links=Class.forName("mchorse.bbs_mod.resources.Link",false,loader);
            Class<?> providers=Class.forName("mchorse.bbs_mod.resources.AssetProvider",false,loader);
            Class<?> managers=Class.forName(MANAGER,false,loader);
            provider=bbs.getMethod("getProvider");manager=client.getMethod("getModels");
            link=providers.getMethod("getLink",File.class);assets=links.getMethod("assets",String.class);
            listing=providers.getMethod("getLinksFromPath",links,boolean.class);
            reloadable=managers.getMethod("isRelodable",links);path=links.getField("path");
        }
        String path(Object link)throws IllegalAccessException{return (String)path.get(link);}
        Set<String> keys(Object provider,Object manager,String prefix)throws ReflectiveOperationException {
            Set<String> found=new HashSet<>();
            Object root=assets.invoke(null,"models/"+prefix);
            for(Object value:(Collection<?>)listing.invoke(provider,root,true))
                if(Boolean.TRUE.equals(reloadable.invoke(manager,value))){
                    String path=path(value);if(path.startsWith("models/")){String key=parent(path.substring(7));if(!key.isEmpty())found.add(key);}
                }
            Performance.count("bbs.nativeSubtreeRead",found.size());return found;
        }
    }
    /** True cancels only an irrelevant/no-op native callback, or an unchanged importer write. */
    public static boolean beforeAccept(Object owner,Path path,Object event){
        if(path==null)return false;
        if(ManagedWrites.suppress(path))return true;
        boolean section=owner.getClass().getName().equals(SECTION);
        try {
            Access a=access.get(owner.getClass());Object provider=a.provider.invoke(null);
            Object link=a.link.invoke(provider,path.toFile());
            if(link==null||!a.path(link).startsWith("models/")){
                Performance.count("bbs.watchIgnoredOutsideModels",0);return true;
            }
            String relative=a.path(link).substring(7);
            while(relative.endsWith("/"))relative=relative.substring(0,relative.length()-1);
            Object manager=a.manager.invoke(null);boolean reloadable=Boolean.TRUE.equals(a.reloadable.invoke(manager,link));
            String kind=event instanceof Enum<?> e?e.name():String.valueOf(event);
            if(kind.equals("MODIFIED")){
                if(!section&&reloadable){ImportedModelGroups.nativeContentsChanged(parent(relative));Performance.count("bbs.watchModelContent",0);}
                else if(section)Performance.count("bbs.watchIgnoredListContent",0);
                // Contents do not change membership. Retain the manager's targeted native forget.
                return section;
            }
            if(!kind.equals("CREATED")&&!kind.equals("DELETED"))return false;
            List<String> cached=ImportedModelGroups.cachedAvailable(manager);
            Set<String> old=new HashSet<>();if(cached!=null)old.addAll(cached);
            String prefix=reloadable?parent(relative):relative;
            boolean directory=Files.isDirectory(path)||old.stream().anyMatch(k->below(k,prefix));
            if(!reloadable&&!directory){Performance.count("bbs.watchIgnoredListAsset",0);return section;}
            Set<String> next=a.keys(provider,manager,prefix);
            Set<String> affected=new HashSet<>(next);
            if(reloadable)affected.add(prefix);else for(String key:old)if(below(key,prefix))affected.add(key);
            for(String key:affected)ImportedModelGroups.nativeModelChanged(key,next.contains(key));
            Performance.count("bbs.watchModelStructure",affected.size());
            if(section){
                Set<String> existing=sectionKeys(owner);
                for(String key:affected){boolean visible=next.contains(key)&&!ImportedModelGroups.inactive(key)&&ImportedModelGroups.previewAllowed(key);if(existing.contains(key)!=visible)return false;}
                Performance.count("bbs.watchIgnoredListUnchanged",0);return true;
            }
            return false;
        }catch(ReflectiveOperationException|LinkageError|RuntimeException ex){
            Performance.count("bbs.watchBridgeFailure",0);
            // An unsupported BBS version retains its original native editing path.
            return false;
        }
    }
    private static Set<String> sectionKeys(Object section)throws ReflectiveOperationException {
        Set<String> result=new HashSet<>();
        for(Object category:(Collection<?>)section.getClass().getMethod("getCategories").invoke(section))
            for(Object form:(Collection<?>)category.getClass().getMethod("getDirectForms").invoke(category)){
                Object value=form.getClass().getField("model").get(form);Object key=value.getClass().getMethod("get").invoke(value);
                if(key instanceof String text)result.add(text);
            }
        return result;
    }
    private static String parent(String path){int slash=path.lastIndexOf('/');return slash<0?"":path.substring(0,slash);}
    private static boolean below(String key,String prefix){return prefix.isEmpty()||key.equals(prefix)||key.startsWith(prefix+"/");}
    private ModelWatchEvents(){}
}
