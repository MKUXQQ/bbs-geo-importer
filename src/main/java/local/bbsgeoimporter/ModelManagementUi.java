package local.bbsgeoimporter;

import java.lang.reflect.*;
import java.util.*;

/** Uses only the native settings module icon. Opening settings consumes the startup manifest, never sources. */
public final class ModelManagementUi {
    public static final String TITLE="Mod 模型管理";
    private static volatile String lastError="";
    private ModelManagementUi(){}

    /** Register before the real constructor snapshots SettingsManager.modules into its existing icon strip. */
    public static void beforeSettings(Object panel){
        if(panel==null)return;
        if(!ModelManagement.registerOnClient(panel.getClass().getClassLoader()))lastError=ModelManagement.diagnostics().get("lastError").toString();
    }

    /** Label the already-created native module icon; do not add buttons, sections or another toolbar. */
    public static void settingsReady(Object panel){
        if(panel==null)return;
        try{
            Object value=field(panel,"moduleButtons");
            Object button=value instanceof Map<?,?> modules?modules.get(ModelManagement.MODULE_ID):null;
            if(button==null)throw new NoSuchFieldException("Native management module icon missing");
            ClassLoader loader=panel.getClass().getClassLoader();
            Class<?> key=Class.forName("mchorse.bbs_mod.l10n.keys.IKey",true,loader);
            button.getClass().getMethod("tooltip",key).invoke(button,key.getMethod("constant",String.class).invoke(null,TITLE));
            lastError="";
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){Throwable cause=ex;while(cause instanceof InvocationTargetException&&cause.getCause()!=null)cause=cause.getCause();lastError="native settings icon: "+cause;Performance.count("settings.uiFailure",0);}
    }
    private static Object field(Object owner,String name)throws ReflectiveOperationException{
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(owner.getClass().getName()+"."+name);
    }
    public static Map<String,Object> diagnostics(){return Map.of("lastError",lastError);}
}
