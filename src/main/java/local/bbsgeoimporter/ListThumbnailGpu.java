package local.bbsgeoimporter;

import java.lang.reflect.*;
import java.util.*;

/** Native BBS transparent FBOs, used only from the existing client list-cell render call. */
final class ListThumbnailGpu implements ListPreviewRequests.Backend {
    private final Method getFramebuffers,getPool,poolGet,poolRelease,bind,clear,texture,draw,flush,offscreen;
    private final Method integer,integers,floats,enabled,viewport,bindFramebuffer,scissor,enableScissor,disableScissor,clearColor,depthMask,getBoolean;
    private final Field batcher,menu,menuWidth,menuHeight,mouseX;
    private final Method globalX,globalY,localX,localY;
    private final Method drawingContext,drawingStack,modelViewStack,applyModelView,depthFunc;
    private final Field stackEntries;
    private final Map<Object,Geometry> geometry=new WeakHashMap<>();
    private record Geometry(long frame,int[] viewport,double sx,double sy) {}
    private record Surface(Object pool,Object framebuffer) {}
    ListThumbnailGpu(ClassLoader loader)throws ReflectiveOperationException {
        Class<?> client=Class.forName("mchorse.bbs_mod.BBSModClient",false,loader),context=Class.forName("mchorse.bbs_mod.ui.framework.UIContext",false,loader);
        Class<?> fb=Class.forName("mchorse.bbs_mod.graphics.Framebuffer",false,loader),pool=Class.forName("mchorse.bbs_mod.graphics.FramebufferPool",false,loader),tex=Class.forName("mchorse.bbs_mod.graphics.texture.Texture",false,loader);
        Class<?> batches=Class.forName("mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D",false,loader),gl=Class.forName("org.lwjgl.opengl.GL11",false,loader),gl30=Class.forName("org.lwjgl.opengl.GL30",false,loader),rs=Class.forName("com.mojang.blaze3d.systems.RenderSystem",false,loader);
        getFramebuffers=client.getMethod("getFramebuffers");getPool=getFramebuffers.getReturnType().getMethod("getFormFramebuffers");poolGet=pool.getMethod("get",int.class,int.class);poolRelease=pool.getMethod("release",fb);
        bind=fb.getMethod("bind");clear=fb.getMethod("clear");texture=fb.getMethod("getMainTexture");flush=batches.getMethod("flush");
        draw=batches.getMethod("texturedBox",tex,int.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class);
        offscreen=Class.forName("mchorse.bbs_mod.client.BBSRendering",false,loader).getMethod("renderOffscreen",Runnable.class);
        batcher=context.getField("batcher");menu=context.getField("menu");menuWidth=menu.getType().getField("width");menuHeight=menu.getType().getField("height");mouseX=context.getField("mouseX");globalX=context.getMethod("globalX",int.class);globalY=context.getMethod("globalY",int.class);localX=context.getMethod("localX",int.class);localY=context.getMethod("localY",int.class);
        integer=gl.getMethod("glGetInteger",int.class);integers=gl.getMethod("glGetIntegerv",int.class,int[].class);floats=gl.getMethod("glGetFloatv",int.class,float[].class);enabled=gl.getMethod("glIsEnabled",int.class);getBoolean=gl.getMethod("glGetBoolean",int.class);
        viewport=gl.getMethod("glViewport",int.class,int.class,int.class,int.class);bindFramebuffer=gl30.getMethod("glBindFramebuffer",int.class,int.class);
        scissor=rs.getMethod("enableScissor",int.class,int.class,int.class,int.class);enableScissor=scissor;disableScissor=rs.getMethod("disableScissor");clearColor=rs.getMethod("clearColor",float.class,float.class,float.class,float.class);depthMask=rs.getMethod("depthMask",boolean.class);
        modelViewStack=rs.getMethod("getModelViewStack");applyModelView=rs.getMethod("applyModelViewMatrix");depthFunc=rs.getMethod("depthFunc",int.class);drawingContext=batches.getMethod("getContext");
        Class<?> matrixStack=modelViewStack.getReturnType();drawingStack=Arrays.stream(drawingContext.getReturnType().getMethods()).filter(m->m.getParameterCount()==0&&m.getReturnType()==matrixStack).findFirst().orElseThrow(()->new NoSuchMethodException("DrawContext matrix stack"));
        stackEntries=Arrays.stream(matrixStack.getDeclaredFields()).filter(f->Deque.class.isAssignableFrom(f.getType())).findFirst().orElseThrow(()->new NoSuchFieldException("PoseStack entries"));stackEntries.setAccessible(true);
    }
    private int[] vector(int token)throws ReflectiveOperationException {int[] result=new int[4];integers.invoke(null,token,result);return result;}
    private Geometry geometry(Object context)throws ReflectiveOperationException {
        Geometry old=geometry.get(context);long frame=ListPreviewRequests.frameNumber();
        if(old!=null&&old.frame()==frame)return old;
        int[] viewport=vector(2978);Object m=menu.get(context);int width=menuWidth.getInt(m),height=menuHeight.getInt(m);
        Geometry next=new Geometry(frame,viewport,viewport[2]/(double)Math.max(1,width),viewport[3]/(double)Math.max(1,height));geometry.put(context,next);return next;
    }
    public int[] size(Object context,int x,int y,int right,int bottom)throws ReflectiveOperationException {
        Geometry g=geometry(context);int width=(int)Math.round((right-x)*g.sx()),height=(int)Math.round((bottom-y)*g.sy());
        double scale=Math.min(1,512.0/Math.max(width,height));return new int[]{Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale))};
    }
    public Object capture(ListPreviewRequests.Access access,Object form,Object context,int x,int y,int right,int bottom,int width,int height)throws ReflectiveOperationException {
        Object batches=batcher.get(context);flush.invoke(batches);
        int[] oldViewport=vector(2978),oldScissor=vector(3088);int oldDraw=(int)integer.invoke(null,36006),oldRead=(int)integer.invoke(null,36010);
        boolean wasScissor=(boolean)enabled.invoke(null,3089),oldDepth=(boolean)getBoolean.invoke(null,2930);float[] oldClear=new float[4];floats.invoke(null,3106,oldClear);
        int oldMouse=mouseX.getInt(context);Object pool=getPool.invoke(getFramebuffers.invoke(null)),fb=null;
        int oldDepthFunc=(int)integer.invoke(null,2932);
        StackSnapshot drawMatrices=new StackSnapshot(stackEntries,drawingStack.invoke(drawingContext.invoke(batches))),modelMatrices=new StackSnapshot(stackEntries,modelViewStack.invoke(null));
        boolean completed=false;
        try {
            Object m=menu.get(context);int mw=menuWidth.getInt(m),mh=menuHeight.getInt(m);
            int[] area=canonical(right-x,bottom-y,mw,mh,(int)localX.invoke(context,0),(int)localY.invoke(context,0));
            if(area==null)return null;
            int cx=area[0],cy=area[1],cr=area[2],cb=area[3];
            fb=poolGet.invoke(pool,width,height);bind.invoke(fb);disableScissor.invoke(null);clearColor.invoke(null,0f,0f,0f,0f);depthMask.invoke(null,true);clear.invoke(fb);
            double sx=oldViewport[2]/(double)Math.max(1,mw),sy=oldViewport[3]/(double)Math.max(1,mh);
            int gx=(int)globalX.invoke(context,cx),gy=(int)globalY.invoke(context,cy),gr=(int)globalX.invoke(context,cr),gb=(int)globalY.invoke(context,cb);
            int left=(int)Math.round(gx*sx),lower=(int)Math.round((menuHeight.getInt(m)-gb)*sy);
            int rawWidth=Math.max(1,(int)Math.round(gr*sx)-left),rawHeight=Math.max(1,(int)Math.round((menuHeight.getInt(m)-gy)*sy)-lower);
            // Preserve the original GUI projection/matrix; shift its full viewport into this cell-sized FBO.
            double rx=width/(double)rawWidth,ry=height/(double)rawHeight;
            viewport.invoke(null,-(int)Math.round(left*rx),-(int)Math.round(lower*ry),(int)Math.round(oldViewport[2]*rx),(int)Math.round(oldViewport[3]*ry));
            mouseX.setInt(context,(cx+cr)/2+22); // Stable list angle, without changing the global freezeModels preference.
            offscreen.invoke(null,(Runnable)()->{try{access.nativeRender(form,context,cx,cy,cr,cb);flush.invoke(batches);}catch(ReflectiveOperationException ex){throw new CaptureFailure(ex);}});
            completed=true;return new Surface(pool,fb);
        }finally {
            try{flush.invoke(batches);}finally{
            mouseX.setInt(context,oldMouse);bindFramebuffer.invoke(null,36009,oldDraw);bindFramebuffer.invoke(null,36008,oldRead);
            drawMatrices.restore();modelMatrices.restore();applyModelView.invoke(null);depthFunc.invoke(null,oldDepthFunc);
            viewport.invoke(null,oldViewport[0],oldViewport[1],oldViewport[2],oldViewport[3]);clearColor.invoke(null,oldClear[0],oldClear[1],oldClear[2],oldClear[3]);depthMask.invoke(null,oldDepth);
            if(wasScissor)enableScissor.invoke(null,oldScissor[0],oldScissor[1],oldScissor[2],oldScissor[3]);else disableScissor.invoke(null);
            if(!completed&&fb!=null)poolRelease.invoke(pool,fb);
            }
        }
    }
    private static final class StackSnapshot {
        final Deque<Object> entries;final List<Object> saved;
        @SuppressWarnings("unchecked") StackSnapshot(Field field,Object stack)throws IllegalAccessException {entries=(Deque<Object>)field.get(stack);saved=new ArrayList<>(entries);}
        void restore(){entries.clear();entries.addAll(saved);}
    }
    /** Center a full logical cell inside the clip volume, compensating native scroll translations. */
    static int[] canonical(int width,int height,int windowWidth,int windowHeight,int shiftX,int shiftY){
        if(width>windowWidth||height>windowHeight)return null;
        int x=(windowWidth-width)/2+shiftX,y=(windowHeight-height)/2+shiftY;
        return new int[]{x,y,x+width,y+height};
    }
    public void draw(Object image,Object context,int x,int y,int right,int bottom)throws ReflectiveOperationException {
        Surface surface=(Surface)image;Object tex=texture.invoke(surface.framebuffer());Class<?> type=tex.getClass();
        draw.invoke(batcher.get(context),tex,-1,(float)x,(float)y,(float)(right-x),(float)(bottom-y),0f,(float)type.getField("height").getInt(tex),(float)type.getField("width").getInt(tex),0f);
    }
    public void release(Object image)throws ReflectiveOperationException {Surface surface=(Surface)image;poolRelease.invoke(surface.pool(),surface.framebuffer());}
    private static final class CaptureFailure extends RuntimeException {CaptureFailure(Throwable cause){super(cause);}}
}
