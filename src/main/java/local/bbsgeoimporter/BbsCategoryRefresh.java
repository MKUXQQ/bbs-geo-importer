package local.bbsgeoimporter;
import java.util.*;
/** Client-only atomic category publication. No disk, source discovery or reset callback queue. */
public final class BbsCategoryRefresh {
    public static Object progressTitle(Object original){
        if(original==null)return null;
        try{Class<?> type=Class.forName("mchorse.bbs_mod.l10n.keys.IKey",false,original.getClass().getClassLoader());
            return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
                if(method.getName().equals("get")&&method.getParameterCount()==0){String base=(String)method.invoke(original);String progress=GeoImporter.uiProgress;return progress.isEmpty()?base:base+" ["+progress+"]";}
                if(method.getName().equals("equals"))return proxy==args[0];
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                return method.invoke(original,args);
            });
        }catch(ReflectiveOperationException|LinkageError ex){return original;}
    }
    private static final Map<Object,Long> attached=Collections.synchronizedMap(new WeakHashMap<>());
    static void nativeAssetsChanged(){attached.clear();}
    private static final BbsCategoryRefresh hook=new BbsCategoryRefresh();
    public static void rebuilt(){ /* compatibility: never schedules work */ }
    static void onRebuilt(Runnable ignored){}
    public static boolean beforeInitiate(Object section){
        if(!section.getClass().getName().equals("mchorse.bbs_mod.forms.sections.ModelFormSection"))return false;
        Performance.count("bbs.sectionInitiate",0);
        return Objects.equals(attached.get(section),ImportedModelGroups.version());
    }
    public static void afterInitiate(Object section){
        if(!section.getClass().getName().equals("mchorse.bbs_mod.forms.sections.ModelFormSection"))return;
        if(Objects.equals(attached.get(section),ImportedModelGroups.version()))return;
        try{var f=section.getClass().getSuperclass().getDeclaredField("parent");f.setAccessible(true);hook.refreshSection(f.get(section),section,ImportedModelGroups.modelKeys());}
        catch(NoSuchFieldException e){try{var f=section.getClass().getSuperclass().getSuperclass().getDeclaredField("parent");f.setAccessible(true);hook.refreshSection(f.get(section),section,ImportedModelGroups.modelKeys());}catch(ReflectiveOperationException ex){SharedModelAssets.status="category_hook:"+ex.getClass().getSimpleName();}}
        catch(ReflectiveOperationException e){SharedModelAssets.status="category_hook:"+e.getClass().getSimpleName();}
    }
    private Object section;private Set<String> target=Set.of();
    boolean pending(){return false;}
    boolean refresh(Object categories)throws ReflectiveOperationException{
        // Prime the native enumeration exactly once at startup, then its hook serves an immutable cache.
        Class<?> client=Class.forName("mchorse.bbs_mod.BBSModClient");Object manager=client.getMethod("getModels").invoke(null);@SuppressWarnings("unchecked") List<String> all=(List<String>)manager.getClass().getMethod("getAvailableKeys").invoke(manager);
        var field=categories.getClass().getDeclaredField("sections");field.setAccessible(true);for(Object s:(List<?>)field.get(categories))if(s.getClass().getName().equals("mchorse.bbs_mod.forms.sections.ModelFormSection"))return refreshSection(categories,s,new HashSet<>(all));return false;
    }
    @SuppressWarnings("unchecked")
    boolean refreshSection(Object categories,Object current,Set<String> keys)throws ReflectiveOperationException{
        long version=ImportedModelGroups.version();
        long start=Performance.start();Class<?> parent=current.getClass().getSuperclass();var field=parent.getDeclaredField("categories");field.setAccessible(true);Map<String,Object> original=(Map<String,Object>)field.get(current);
        Set<String> existing=new HashSet<>();boolean hidden=false;for(Object c:original.values())for(Object form:(List<?>)c.getClass().getMethod("getDirectForms").invoke(c)){String key=key(form);if(key!=null){existing.add(key);hidden|=ImportedModelGroups.inactive(key);}}
        Set<String> missing=new TreeSet<>(keys);missing.removeAll(existing);
        if(missing.isEmpty()&&!hidden){attached.put(current,version);return true;}
        Object staging=current.getClass().getConstructor(categories.getClass()).newInstance(categories);Map<String,Object> staged=(Map<String,Object>)field.get(staging);
        var get=parent.getDeclaredMethod("getCategory",String.class);get.setAccessible(true);var create=current.getClass().getDeclaredMethod("create",String.class);create.setAccessible(true);
        // Clone only affected categories, retaining all untouched objects and their UI preferences.
        Set<String> changed=new HashSet<>();for(String key:missing){String group=ImportedModelGroups.category(key);if(group==null){int slash=key.lastIndexOf(47);group=slash<0?"":key.substring(0,slash);}changed.add(group);}
        for(var row:original.entrySet()){List<?> forms=(List<?>)row.getValue().getClass().getMethod("getDirectForms").invoke(row.getValue());boolean remove=forms.stream().anyMatch(f->ImportedModelGroups.inactive(key(f)));if(!remove&&!changed.contains(row.getKey())){staged.put(row.getKey(),row.getValue());continue;}
            for(Object form:forms){String key=key(form);if(ImportedModelGroups.inactive(key))continue;Object clone=get.invoke(staging,key);((List<Object>)clone.getClass().getMethod("getDirectForms").invoke(clone)).add(form);}
        }
        for(String key:missing){Object group=get.invoke(staging,key);((List<Object>)group.getClass().getMethod("getDirectForms").invoke(group)).add(create.invoke(staging,key));}
        if(version!=ImportedModelGroups.version())return false;
        field.set(current,staged);
        try{categories.getClass().getMethod("markDirty").invoke(categories);}catch(ReflectiveOperationException ex){field.set(current,original);attached.remove(current);throw ex;}
        attached.put(current,version);section=current;target=Set.copyOf(keys);Performance.count("bbs.markDirty",0);Performance.count("bbs.categoryAttach",keys.size());Performance.end("bbs.categorySwap",start);return true;
    }
    private static String key(Object form){try{Object model=form.getClass().getField("model").get(form);return (String)model.getClass().getMethod("get").invoke(model);}catch(ReflectiveOperationException e){return null;}}
}
