package local.bbsgeoimporter;

import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import javax.tools.ToolProvider;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Executes the production exact-shape transform on actual BBS classes, with CPU-only UI seams. */
public final class FormGridViewportAcceptance {
    private static final String GRID="mchorse/bbs_mod/ui/framework/elements/input/items/UIItemGrid";
    private static final String LAYOUT="mchorse/bbs_mod/ui/utils/GridLayout";
    private static final String FORM_LAYOUT="mchorse/bbs_mod/ui/forms/FormGridLayout";
    private static final String CATEGORY="mchorse/bbs_mod/ui/forms/categories/UIFormCategory";
    private static final String RANGE="bbsGeoImporter$viewportRange";
    public static void main(String[] args)throws Exception {
        if(args.length==0)throw new IllegalArgumentException("Pass a read-only BBS JAR");
        Path jar=Path.of(args[0]),base=Path.of("build/form-grid-viewport-cpu").toAbsolutePath();
        Path old=base.resolve("native"),fast=base.resolve("bounded");Files.createDirectories(old);Files.createDirectories(fast);
        try(JarFile archive=new JarFile(jar.toFile())){
            for(String layout:List.of(LAYOUT,FORM_LAYOUT)){byte[] bytes=archive.getInputStream(archive.getJarEntry(layout+".class")).readAllBytes();write(old,layout,bytes);write(fast,layout,bytes);}
            ClassNode nativeGrid=read(archive,GRID),category=read(archive,CATEGORY);
            MethodNode constructor=category.methods.stream().filter(m->m.name.equals("<init>")).findFirst().orElseThrow();
            check(Arrays.stream(constructor.instructions.toArray()).anyMatch(i->i instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.NEW&&type.desc.equals(FORM_LAYOUT)),"actual category constructor creates FormGridLayout");
            ClassNode layout=read(archive,FORM_LAYOUT);
            check(layout.superName.equals(LAYOUT)&&layout.methods.stream().allMatch(m->m.name.equals("<init>")||m.name.equals("cellHeightFor")),"actual native FormGridLayout inherits linear grid coordinates");
            ClassNode transformed=read(archive,GRID);
            check(FormGridViewportTransforms.instrument(transformed),"production transform accepts actual BBS loop");
            byte[] once=bytes(transformed);check(!FormGridViewportTransforms.instrument(transformed)&&Arrays.equals(once,bytes(transformed)),"second production transform is no-op");
            malformedShapes(archive);
            compile(old);compile(fast);install(old,nativeGrid,false);install(fast,transformed,true);
            installConstructor(old,constructor);installConstructor(fast,constructor);
        }
        try(Fixture nativeGrid=new Fixture(old);Fixture boundedGrid=new Fixture(fast)){
            check(boundedGrid.layout().getClass().getName().equals(FORM_LAYOUT.replace('/' ,'.')),"CPU object uses actual FormGridLayout through native category constructor prefix");
            boundedGrid.configure(12_000,484,40,0,20_000,320,false,false);
            long live=FormGridViewport.range(boundedGrid.grid);
            check(FormGridViewport.start(live)>0&&FormGridViewport.end(live,Collections.nCopies(12_000,0))<12_000,"production range is narrow on real form layout, not silent full fallback");
            Random random=new Random(0x271520L);int comparisons=0;
            for(int i=0;i<550;i++){
                int count=i<4?i:random.nextInt(12_000),width=80+random.nextInt(700),cell=20+random.nextInt(75);
                int origin=-200+random.nextInt(400),windowY=-300+random.nextInt(120_000),windowHeight=random.nextInt(650);
                boolean horizontal=i%17==0,noWindow=i%19==0;
                compare(nativeGrid,boundedGrid,count,width,cell,origin,windowY,windowHeight,horizontal,noWindow,"random "+i);comparisons++;
            }
            // A filtered list keeps original ids, but layout indexes/selection state use its filtered order.
            nativeGrid.configure(12_000,484,40,0,23,1,false,false);boundedGrid.configure(12_000,484,40,0,23,1,false,false);
            nativeGrid.filter(7);boundedGrid.filter(7);nativeGrid.render();boundedGrid.render();
            check(nativeGrid.output().equals(boundedGrid.output()),"search-filtered list order/state retained");comparisons++;
            live=FormGridViewport.range(boundedGrid.grid);check(FormGridViewport.end(live,Collections.nCopies(1715,0))<=20,"one-pixel filtered window only visits its row");
            // Last visible row and inclusive clipping exactly follow native boundaries, including partial final rows.
            for(int count:List.of(1,9,10,11,12_003)){
                nativeGrid.configure(count,484,40,0,0,100,false,false);boundedGrid.configure(count,484,40,0,0,100,false,false);
                int lastY=(int)boundedGrid.layout().getClass().getMethod("getY",int.class).invoke(boundedGrid.layout(),Math.max(0,count-1));
                for(int offset:List.of(-1,0,1,52,53,54)){
                    compare(nativeGrid,boundedGrid,count,484,40,0,lastY+offset,1,false,false,"last row "+count+"/"+offset);comparisons++;
                }
            }
            nativeGrid.configure(12_000,484,40,0,20_000,320,false,false);boundedGrid.configure(12_000,484,40,0,20_000,320,false,false);
            for(int i=0;i<80;i++){nativeGrid.render();boundedGrid.render();}
            nativeGrid.resetIterations();boundedGrid.resetIterations();int frames=800;
            long nativeNs=measure(nativeGrid,frames),boundedNs=measure(boundedGrid,frames),before=nativeGrid.iterations(),after=boundedGrid.iterations();
            check(before==(long)frames*12_000&&after<before/30,"production bounded loop actually skips offscreen original iterations");
            check(nativeGrid.output().equals(boundedGrid.output()),"timed paths retain identical final native output");
            // Unrecognized layout subclasses retain their original full traversal.
            boundedGrid.customLayout();boundedGrid.configure(12_000,484,40,0,20_000,320,false,false);
            check(FormGridViewport.start(FormGridViewport.range(boundedGrid.grid))==0&&FormGridViewport.end(FormGridViewport.range(boundedGrid.grid),Collections.nCopies(12_000,0))==12_000,"unknown layout retains original loop");
            System.out.printf(Locale.ROOT,"FORM GRID PRODUCTION TRANSFORM CPU PASS: %s, actual native category constructor prefix + actual FormGridLayout; %d equivalent cases; 12000 cells x %d frames, visits %d -> %d, %.3f -> %.3f ms. CPU seams only; no game/FPS/GL claim.%n",jar.getFileName(),comparisons,frames,before,after,nativeNs/1e6,boundedNs/1e6);
        }
        check(FormGridViewport.end(FormGridViewport.range(10,2,40,4,0,Integer.MAX_VALUE,0,10),Collections.nCopies(10,"x"))==10,"overflow native fallback");
    }
    private static void compare(Fixture a,Fixture b,int count,int width,int cell,int origin,int y,int h,boolean horizontal,boolean noWindow,String why)throws Exception {
        a.configure(count,width,cell,origin,y,h,horizontal,noWindow);b.configure(count,width,cell,origin,y,h,horizontal,noWindow);a.render();b.render();
        check(a.output().equals(b.output()),why+": exact native ids/order/coordinates/selection/drag state");
    }
    private static void malformedShapes(JarFile archive)throws Exception {
        ClassNode changed=read(archive,GRID);MethodNode method=render(changed);
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof IincInsnNode increment){increment.incr=2;break;}reject(changed,"changed loop increment");
        changed=read(archive,GRID);for(AbstractInsnNode instruction:render(changed).instructions)if(instruction instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.IF_ICMPLT){jump.setOpcode(Opcodes.IF_ICMPLE);break;}reject(changed,"changed clip predicate");
        changed=read(archive,GRID);render(changed).instructions.insert(new InsnNode(Opcodes.NOP));reject(changed,"unexpected extra code");
        changed=read(archive,GRID);render(changed).name="differentRender";reject(changed,"missing target");
    }
    private static void reject(ClassNode node,String why){byte[] before=bytes(node);check(!FormGridViewportTransforms.instrument(node),why+" declined");check(Arrays.equals(before,bytes(node)),why+" unchanged");}
    private static long measure(Fixture grid,int frames)throws Exception {long start=System.nanoTime();for(int i=0;i<frames;i++)grid.render();return System.nanoTime()-start;}
    private static final class Fixture implements AutoCloseable {
        final URLClassLoader loader;final Object grid;final Class<?> type;final Method run,configure;
        Fixture(Path base)throws Exception {
            loader=new URLClassLoader(new URL[]{base.toUri().toURL()},FormGridViewportAcceptance.class.getClassLoader());type=loader.loadClass(CATEGORY.replace('/','.'));
            Class<?> category=loader.loadClass("mchorse.bbs_mod.forms.categories.FormCategory"),list=loader.loadClass("mchorse.bbs_mod.ui.forms.UIFormList");
            grid=type.getConstructor(category,list).newInstance(category.getConstructor().newInstance(),list.getConstructor().newInstance());
            run=type.getMethod("run");configure=type.getMethod("configure",int.class,int.class,int.class,int.class,int.class,int.class,boolean.class,boolean.class);
        }
        Object layout()throws Exception {Field layout=type.getSuperclass().getDeclaredField("layout");layout.setAccessible(true);return layout.get(grid);}
        void customLayout()throws Exception {type.getMethod("customLayout").invoke(grid);}
        void filter(int divisor)throws Exception {type.getMethod("filter",int.class).invoke(grid,divisor);}
        void configure(int count,int width,int cell,int origin,int y,int height,boolean horizontal,boolean noWindow)throws Exception {configure.invoke(grid,count,width,cell,origin,y,height,horizontal,noWindow);}
        void render()throws Exception {run.invoke(grid);}
        Object output()throws Exception {return type.getField("rendered").get(grid);}
        long iterations()throws Exception {return type.getField("iterations").getLong(grid);}
        void resetIterations()throws Exception {type.getField("iterations").setLong(grid,0);}
        public void close()throws Exception {loader.close();}
    }
    private static void install(Path base,ClassNode actual,boolean optimized)throws Exception {
        ClassNode facade=fromBytes(Files.readAllBytes(base.resolve(GRID+".class")));facade.methods.removeIf(m->m.name.equals("renderCells"));
        MethodNode source=render(actual),method=new MethodNode(source.access,source.name,source.desc,source.signature,source.exceptions.toArray(String[]::new));source.accept(method);facade.methods.add(method);
        if(optimized)facade.fields.add(actual.fields.stream().filter(f->f.name.equals(RANGE)).findFirst().orElseThrow());
        for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.owner.equals(LAYOUT)&&call.name.equals("getX")){
            InsnList count=new InsnList();count.add(new VarInsnNode(Opcodes.ALOAD,0));count.add(new InsnNode(Opcodes.DUP));count.add(new FieldInsnNode(Opcodes.GETFIELD,GRID,"iterations","J"));count.add(new InsnNode(Opcodes.LCONST_1));count.add(new InsnNode(Opcodes.LADD));count.add(new FieldInsnNode(Opcodes.PUTFIELD,GRID,"iterations","J"));method.instructions.insertBefore(call,count);
        }
        method.maxStack+=8;new Analyzer<>(new BasicVerifier()).analyze(facade.name,method);write(base,GRID,bytes(facade));
    }
    /** Keep the real allocation/configuration/super-call prefix. Only unused native comparator is a CPU seam. */
    private static void installConstructor(Path base,MethodNode actual)throws Exception {
        ClassNode category=fromBytes(Files.readAllBytes(base.resolve(CATEGORY+".class")));category.methods.removeIf(m->m.name.equals("<init>"));
        MethodNode constructor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>",actual.desc,null,null);Map<LabelNode,LabelNode> labels=new IdentityHashMap<>();
        for(AbstractInsnNode instruction:actual.instructions)if(instruction instanceof LabelNode label)labels.put(label,new LabelNode());
        boolean superCall=false;
        for(AbstractInsnNode instruction:actual.instructions){
            if(instruction instanceof FrameNode||instruction instanceof LineNumberNode)continue;
            constructor.instructions.add(instruction instanceof InvokeDynamicInsnNode?new InsnNode(Opcodes.ACONST_NULL):instruction.clone(labels));
            if(instruction instanceof MethodInsnNode call&&call.owner.equals(GRID)&&call.name.equals("<init>")){superCall=true;break;}
        }
        check(superCall,"native category prefix reaches original grid super constructor");constructor.instructions.add(new InsnNode(Opcodes.RETURN));constructor.maxLocals=3;constructor.maxStack=8;category.methods.add(constructor);
        new Analyzer<>(new BasicVerifier()).analyze(category.name,constructor);write(base,CATEGORY,bytes(category));
    }
    private static void compile(Path base)throws Exception {
        Map<String,String> sources=new LinkedHashMap<>();
        sources.put("mchorse/bbs_mod/ui/framework/UIContext","package mchorse.bbs_mod.ui.framework; public class UIContext {}");
        sources.put("mchorse/bbs_mod/ui/utils/Area","package mchorse.bbs_mod.ui.utils; public class Area {public int x,y,w,h;public int ex(){return x+w;}public int ey(){return y+h;}}");
        sources.put("mchorse/bbs_mod/ui/utils/cells/CellState","package mchorse.bbs_mod.ui.utils.cells; public class CellState {public boolean hover,picked,selected,dragged,dropTarget;public CellState reset(){hover=picked=selected=dragged=dropTarget=false;return this;}}");
        sources.put("mchorse/bbs_mod/ui/framework/elements/input/items/Selection","package mchorse.bbs_mod.ui.framework.elements.input.items; public class Selection {public boolean contains(Object item){return ((Integer)item)%11==0;}public boolean isGroup(){return true;}}");
        sources.put("mchorse/bbs_mod/ui/framework/elements/input/items/ItemDrag","package mchorse.bbs_mod.ui.framework.elements.input.items; public class ItemDrag {public boolean isDragging(Object item){return ((Integer)item)%13==0;}public boolean isTarget(Object item){return ((Integer)item)%17==0;}}");
        sources.put("mchorse/bbs_mod/forms/categories/FormCategory","package mchorse.bbs_mod.forms.categories;public class FormCategory {}");
        sources.put("mchorse/bbs_mod/ui/forms/UIFormList","package mchorse.bbs_mod.ui.forms;import mchorse.bbs_mod.ui.framework.elements.input.items.*;public class UIFormList {public Selection selection=new Selection();public ItemDrag drag=new ItemDrag();}");
        sources.put(GRID,"""
            package mchorse.bbs_mod.ui.framework.elements.input.items;
            import java.util.*;import java.util.function.*;import mchorse.bbs_mod.ui.utils.*;import mchorse.bbs_mod.ui.utils.cells.*;import mchorse.bbs_mod.ui.framework.UIContext;
            public class UIItemGrid {
                protected GridLayout layout;
                protected final CellState state=new CellState();protected Selection selection;protected ItemDrag drag;
                protected int hoverIndex=37;private boolean horizontal;private int origin;
                private final Area window=new Area();private boolean noWindow;
                private final List<Integer> values=new ArrayList<>();public final List<String> rendered=new ArrayList<>();public long iterations;
                public UIItemGrid(Consumer callback,BiPredicate predicate,Selection selection,ItemDrag drag,GridLayout layout){this.selection=selection;this.drag=drag;this.layout=layout;}
                public void configure(int count,int width,int cell,int origin,int y,int h,boolean horizontal,boolean noWindow){values.clear();for(int i=0;i<count;i++)values.add(i);layout.set(width,cell,count);this.origin=origin;window.x=y;window.y=y;window.w=h;window.h=h;this.horizontal=horizontal;this.noWindow=noWindow;}
                public void filter(int divisor){values.removeIf(i->i%divisor!=0);layout.set(484,40,values.size());}
                public void customLayout(){layout=new CustomGridLayout();}
                private static class CustomGridLayout extends GridLayout {CustomGridLayout(){super(20,6,3,3,7,4f/3f);}}
                protected List<Integer> visible(){return values;}protected Area visibleWindow(){return noWindow?null:window;}
                protected int originX(){return origin;}protected int originY(){return origin;}
                protected void renderCell(UIContext context,Object item,int x,int y,int w,int h,CellState state){rendered.add(item+":"+x+":"+y+":"+w+":"+h+":"+state.hover+":"+state.picked+":"+state.selected+":"+state.dragged+":"+state.dropTarget);}
                protected void renderCells(UIContext context){}
                public void run(){rendered.clear();renderCells(new UIContext());}
            }
            """);
        sources.put(CATEGORY,"package mchorse.bbs_mod.ui.forms.categories;public class UIFormCategory extends mchorse.bbs_mod.ui.framework.elements.input.items.UIItemGrid {public UIFormCategory(){super(null,null,null,null,null);}}");
        List<String> command=new ArrayList<>(List.of("-proc:none","--release","17","-encoding","UTF-8","-classpath",base.toString(),"-d",base.toString()));
        for(var entry:sources.entrySet()){Path source=base.resolve(entry.getKey()+".java");Files.createDirectories(source.getParent());Files.writeString(source,entry.getValue());command.add(source.toString());}
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,command.toArray(String[]::new))==0,"CPU UI seams compile against actual FormGridLayout");
    }
    private static MethodNode render(ClassNode node){return node.methods.stream().filter(m->m.name.equals("renderCells")&&m.desc.equals("(Lmchorse/bbs_mod/ui/framework/UIContext;)V")).findFirst().orElseThrow();}
    private static ClassNode read(JarFile archive,String path)throws Exception {return fromBytes(archive.getInputStream(archive.getJarEntry(path+".class")).readAllBytes());}
    private static ClassNode fromBytes(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static byte[] bytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}
    private static void write(Path base,String name,byte[] bytes)throws Exception {Path target=base.resolve(name+".class");Files.createDirectories(target.getParent());Files.write(target,bytes);}
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private FormGridViewportAcceptance(){}
}
