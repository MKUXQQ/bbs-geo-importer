package local.bbsgeoimporter;

import java.lang.reflect.*;
import java.util.List;

/** Restricts the native vertical form-grid loop; the original clipping/render/state code stays intact. */
public final class FormGridViewport {
    private static final String CATEGORY="mchorse.bbs_mod.ui.forms.categories.UIFormCategory";
    private static final String LAYOUT="mchorse.bbs_mod.ui.utils.GridLayout";
    private static final String FORM_LAYOUT="mchorse.bbs_mod.ui.forms.FormGridLayout";
    private static final ClassValue<Access> access=new ClassValue<>() {
        protected Access computeValue(Class<?> type){try{return new Access(type);}catch(ReflectiveOperationException ex){return new Access();}}
    };
    private static final class Access {
        Method visible,window,origin,getPerRow,getCellHeight,getGap,getY;
        Field layout,horizontal,windowY,windowH;
        boolean valid;
        Access(){}
        Access(Class<?> type)throws ReflectiveOperationException {
            boolean category=false;for(Class<?> c=type;c!=null;c=c.getSuperclass())if(c.getName().equals(CATEGORY))category=true;
            if(!category)return;
            visible=method(type,"visible");window=method(type,"visibleWindow");origin=method(type,"originY");
            layout=field(type,"layout");horizontal=field(type,"horizontal");
            Class<?> grid=layout.getType();if(!grid.getName().equals(LAYOUT))return;
            getPerRow=grid.getMethod("getPerRow");getCellHeight=grid.getMethod("getCellHeight");getGap=grid.getMethod("getGap");getY=grid.getMethod("getY",int.class);
            Class<?> area=window.getReturnType();windowY=field(area,"y");windowH=field(area,"h");valid=true;
        }
    }
    /** Called once at native renderCells HEAD. Invalid/custom layouts retain the native full loop. */
    public static long range(Object grid){
        try{
            Access api=access.get(grid.getClass());if(!api.valid||api.horizontal.getBoolean(grid))return full();
            Object layout=api.layout.get(grid);if(layout==null||!knownLayout(layout.getClass()))return full();
            Object window=api.window.invoke(grid);if(window==null)return full();
            int count=((List<?>)api.visible.invoke(grid)).size();
            return range(count,(int)api.getPerRow.invoke(layout),(int)api.getCellHeight.invoke(layout),(int)api.getGap.invoke(layout),(int)api.getY.invoke(layout,0),(int)api.origin.invoke(grid),api.windowY.getInt(window),api.windowH.getInt(window));
        }catch(ReflectiveOperationException|RuntimeException ex){return full();}
    }
    private static final ClassValue<Boolean> knownLayouts=new ClassValue<>() {
        protected Boolean computeValue(Class<?> type){try{return verifyLayout(type);}catch(ReflectiveOperationException|RuntimeException ex){return false;}}
    };
    private static boolean knownLayout(Class<?> type){return knownLayouts.get(type);}
    private static boolean verifyLayout(Class<?> type)throws NoSuchMethodException {
        String name=type.getName();if(!name.equals(LAYOUT)&&!name.equals(FORM_LAYOUT))return false;
        // FormGridLayout inherits the exact linear coordinates; unknown subclasses remain native.
        for(String method:List.of("getPerRow","getCellHeight","getGap"))if(!type.getMethod(method).getDeclaringClass().getName().equals(LAYOUT))return false;
        return type.getMethod("getY",int.class).getDeclaringClass().getName().equals(LAYOUT)&&type.getMethod("getRow",int.class).getDeclaringClass().getName().equals(LAYOUT)&&type.getMethod("getRowY",int.class).getDeclaringClass().getName().equals(LAYOUT);
    }
    /** Native inclusive overlap is y+height >= window.y && y <= window.ey(). */
    static long range(int count,int perRow,int height,int gap,int rowZero,int origin,int windowY,int windowHeight){
        if(count<0||perRow<=0||height<=0||gap<0||windowHeight<0)return full();
        long step=(long)height+gap,firstY=(long)origin+rowZero;
        long rows=((long)count+perRow-1)/perRow,lastY=firstY+Math.max(0,rows-1)*step;
        long endY=(long)windowY+windowHeight;
        // Preserve native int-overflow behavior by declining optimization for those coordinates.
        if(firstY<Integer.MIN_VALUE||lastY+height>Integer.MAX_VALUE||endY<Integer.MIN_VALUE||endY>Integer.MAX_VALUE)return full();
        long firstRow=-Math.floorDiv(-(windowY-firstY-height),step),lastRow=Math.floorDiv(endY-firstY,step);
        long start=Math.min(count,Math.max(0,firstRow)*perRow),end=Math.min(count,Math.max(0,lastRow+1)*perRow);
        return pack((int)start,(int)Math.max(start,end));
    }
    public static int start(long range){return (int)(range>>>32);}
    public static int limit(List<?> items,long range){return end(range,items);}
    public static int end(long range,List<?> items){return Math.min((int)range,items.size());}
    private static long full(){return pack(0,Integer.MAX_VALUE);}
    private static long pack(int start,int end){return ((long)start<<32)|(end&0xffffffffL);}
    private static Field field(Class<?> type,String name)throws NoSuchFieldException {for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field field=c.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static Method method(Class<?> type,String name)throws NoSuchMethodException {for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Method method=c.getDeclaredMethod(name);method.setAccessible(true);return method;}catch(NoSuchMethodException ignored){}throw new NoSuchMethodException(name);}
    private FormGridViewport(){}
}
