package local.bbsgeoimporter;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Executes the real BBS 2.7 settings constructor, native module icons and layout algorithms.
 * Only game actions, render primitives and the containing window are CPU seams. */
public final class ModelManagementUiAcceptance {
    private static final String UI="mchorse/bbs_mod/ui/",SETTINGS="mchorse/bbs_mod/settings/";
    public static void main(String[] args)throws Exception{run(Files.createTempDirectory(Path.of("build"),"management-ui-native-icon-"));}
    @SuppressWarnings("unchecked")
    static void run(Path base)throws Exception{
        Files.createDirectories(base);Path classes=base.resolve("classes"),sources=base.resolve("seams");Files.createDirectories(classes);
        Path bbs=Path.of(System.getProperty("bbs.fixture.jar","E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods/bbs-2.8-1.20.1.jar"));
        Map<String,String> stubs=seams();List<String> javac=new ArrayList<>(List.of("-proc:none","-encoding","UTF-8","-cp",bbs+java.io.File.pathSeparator+System.getProperty("java.class.path"),"-d",classes.toString()));
        for(var e:stubs.entrySet()){Path f=sources.resolve(e.getKey()+".java");Files.createDirectories(f.getParent());int slash=e.getKey().lastIndexOf('/');Files.writeString(f,"package "+e.getKey().substring(0,slash).replace('/','.')+";"+e.getValue());javac.add(f.toString());}
        check(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,javac.toArray(String[]::new))==0,"UI CPU seams compile");
        try(JarFile jar=new JarFile(bbs.toFile())){
            ClassNode factory=read(jar,SETTINGS+"ui/UIValueFactory");
            factory.methods.removeIf(m->!Set.of("getTitleKey","getCategoryTitleKey","getCategoryTooltipKey","getValueLabelKey","getValueCommentKey","booleanUI").contains(m.name)&&!m.name.startsWith("lambda$booleanUI$"));write(classes,factory);
            ClassNode map=read(jar,SETTINGS+"ui/UIValueMap");map.methods.removeIf(m->!Set.of("register","create","<clinit>").contains(m.name));write(classes,map);
            ClassNode panel=read(jar,SETTINGS+"ui/UISettingsOverlayPanel");
            panel.methods.removeIf(m->m.name.equals("renderBackground")||m.name.startsWith("lambda$addVideoPresets$"));
            // Unrelated video preset actions reference Minecraft UI; neither page below has this category.
            for(MethodNode m:panel.methods)if(m.name.equals("addVideoPresets")||m.name.equals("applyVideoPreset"))empty(m);
            check(local.bbsgeoimporter.mixin.CaptureMixinPlugin.instrumentSettingsManagement(panel),"production settings hook accepts real native page");
            check(local.bbsgeoimporter.mixin.CaptureMixinPlugin.instrumentSettingsManagement(panel),"settings instrumentation is idempotent");
            write(classes,panel);
            for(String inner:List.of("UISectionButton","UISectionHeader")){ClassNode n=read(jar,SETTINGS+"ui/UISettingsOverlayPanel$"+inner);n.methods.removeIf(m->m.name.startsWith("render"));write(classes,n);}
            ClassNode morph=read(jar,UI+"morphing/UIMorphingPanel");
            byte[] nativeMorph=classBytes(morph);new local.bbsgeoimporter.mixin.CaptureMixinPlugin().postApply(morph.name.replace('/','.'),morph,"local.bbsgeoimporter.mixin.MorphingManagementMixin",null);
            check(Arrays.equals(nativeMorph,classBytes(morph)),"removed B management dispatch leaves the real native morphing class unchanged");
            check(calls(panel,"beforeSettings")==1&&calls(panel,"settingsReady")==1&&calls(panel,"settingsSectionsBuilt")==0,"only the native settings constructor receives management hooks");
            check(calls(morph,"morphingReady")==0,"native B list constructor is not modified for management");

        }
        Path config=base.resolve("config");ModelManagement.configure(config);
        ModelManagement.publishManifest(List.of(new ModelManagement.SourceInfo("random_a","当前模型 A","v1",700,true),new ModelManagement.SourceInfo("random_b","当前模型 B","v2",19,true),new ModelManagement.SourceInfo("random_cold","新启用来源","v3",4,false)));
        List<String> changed=new ArrayList<>();ModelManagement.onChanged((id,on)->changed.add(id+"="+on));Performance.reset();
        // Other CPU tests provide a deliberately tiny BBSSettings on the parent classpath.
        // Use this fixture's exact field descriptors and the real JAR for all native BBS types.
        try(URLClassLoader loader=new URLClassLoader(new URL[]{classes.toUri().toURL(),bbs.toUri().toURL()},ModelManagementUiAcceptance.class.getClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(!name.startsWith("mchorse.bbs_mod."))return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)){
                    Class<?> type=findLoadedClass(name);
                    if(type==null)try{type=findClass(name);}catch(ClassNotFoundException absent){type=super.loadClass(name,false);}
                    if(resolve)resolveClass(type);return type;
                }
            }
        }){
            Class<?> mod=loader.loadClass("mchorse.bbs_mod.BBSMod"),builder=loader.loadClass(SETTINGS.replace('/','.')+"SettingsBuilder"),icon=loader.loadClass(UI.replace('/','.')+"utils.icons.Icon");
            Object manager=mod.getMethod("getSettings").invoke(null),nativeBuilder=builder.getConstructor(icon,String.class,java.io.File.class).newInstance(loader.loadClass(UI.replace('/','.')+"utils.icons.Icons").getField("FOLDER").get(null),"bbs",base.resolve("native.json").toFile());
            builder.getMethod("category",String.class).invoke(nativeBuilder,"general");builder.getMethod("getBoolean",String.class,boolean.class).invoke(nativeBuilder,"user_setting",true);
            Object nativeModule=builder.getMethod("getConfig").invoke(nativeBuilder);Map<String,Object> modules=(Map<String,Object>)manager.getClass().getField("modules").get(manager);modules.put("bbs",nativeModule);
            Class<?> bool=loader.loadClass(SETTINGS.replace('/','.')+"values.numeric.ValueBoolean"),baseValue=loader.loadClass(SETTINGS.replace('/','.')+"values.base.BaseValue"),uiFactory=loader.loadClass(SETTINGS.replace('/','.')+"ui.UIValueFactory"),mapType=loader.loadClass(SETTINGS.replace('/','.')+"ui.UIValueMap"),factoryType=loader.loadClass(SETTINGS.replace('/','.')+"ui.UIValueMap$IUIValueFactory");
            Object factory=Proxy.newProxyInstance(loader,new Class<?>[]{factoryType},(proxy,method,args)->List.of(uiFactory.getMethod("booleanUI",bool,java.util.function.Consumer.class).invoke(null,args[0],null)));
            mapType.getMethod("register",Class.class,factoryType).invoke(null,bool,factory);
            Class<?> pageType=loader.loadClass(SETTINGS.replace('/','.')+"ui.UISettingsOverlayPanel");Object page=pageType.getConstructor().newInstance();
            check(field(page,"settings")==nativeModule,"actual native constructor opens default bbs settings module");
            Object sections=field(page,"sections"),icons=field(page,"icons");
            Map<String,Object> buttons=(Map<String,Object>)field(page,"moduleButtons");
            Object entry=buttons.get(ModelManagement.MODULE_ID),nativeIcon=buttons.get("bbs");
            check(entry!=null&&buttons.size()==2&&children(icons).contains(entry)&&children(icons).contains(nativeIcon),"native module strip has exactly the existing BBS icon and one management icon");
            check(labelled(children(sections),ModelManagementUi.TITLE)==null,"default settings sections contain no extra text entry");
            check(tooltip(entry).equals(ModelManagementUi.TITLE),"existing native management icon has the exact Chinese tooltip");
            Object nativeTooltip=field(nativeIcon,"tooltipKey");
            entry.getClass().getMethod("clickItself").invoke(entry);
            Object module=modules.get(ModelManagement.MODULE_ID);check(field(page,"settings")==module,"clicking the native module icon selects management");
            List<?> rows=sourceRows(page);check(rows.size()==3&&label(rows.get(0)).contains("加载该 Mod 全部模型")&&rows.stream().anyMatch(row->labelUnchecked(row).contains("700 个 Geo")),"real native appendValues/Boolean factory constructs visible source toggle rows: "+rows.stream().map(ModelManagementUiAcceptance::labelUnchecked).toList());
            Object toggle=rows.stream().filter(row->labelUnchecked(row).contains("当前模型 A")).findFirst().orElseThrow();toggle.getClass().getMethod("clickItself").invoke(toggle);
            check(!ModelManagement.enabled("random_a")&&changed.equals(List.of("random_a=false")),"native row click invokes actual ValueBoolean persistence/visibility callback");
            module.getClass().getMethod("save").invoke(module);
            check(!com.google.gson.JsonParser.parseString(Files.readString(config.resolve("bbs/mod-model-management.json"))).getAsJsonObject().getAsJsonObject("sources").get("random_a").getAsBoolean(),"visible native toggle click persists through real BBS settings serialization");
            check((boolean)baseValue.getMethod("isVisible").invoke(module),"management module stays native-visible");
            long sourceReads=count("settings.configRead");
            List<?> originalIcons=List.copyOf(children(icons));
            for(int i=0;i<20;i++){
                nativeIcon.getClass().getMethod("clickItself").invoke(nativeIcon);
                check(field(page,"settings")==nativeModule&&labelled(children(sections),ModelManagementUi.TITLE)==null,"native module click remains functional with no text injection");
                entry.getClass().getMethod("clickItself").invoke(entry);
                check(sourceRows(page).size()==3&&children(icons).equals(originalIcons)&&field(nativeIcon,"tooltipKey")==nativeTooltip,"module switches preserve native icons and never add duplicate controls");
            }
            for(int width:List.of(800,420,320,220)){
                pageType.getMethod("w",int.class).invoke(page,width);pageType.getMethod("resize").invoke(page);
                check(positiveArea(entry)&&children(icons).equals(originalIcons)&&tooltip(entry).equals(ModelManagementUi.TITLE),"native module icon remains usable without custom layout at width="+width+" entry="+field(entry,"area")+" icons="+field(icons,"area")+" children="+children(icons).size()+" tooltip="+tooltip(entry));
            }
            Object reopened=pageType.getConstructor().newInstance();Map<String,Object> reopenedButtons=(Map<String,Object>)field(reopened,"moduleButtons");Object reopenedEntry=reopenedButtons.get(ModelManagement.MODULE_ID);
            check(reopenedButtons.size()==2&&tooltip(reopenedEntry).equals(ModelManagementUi.TITLE),"reopening settings creates one native management icon");
            reopenedEntry.getClass().getMethod("clickItself").invoke(reopenedEntry);
            check(sourceRows(reopened).size()==3&&!(boolean)toggle.getClass().getMethod("getValue").invoke(sourceRows(reopened).stream().filter(row->labelUnchecked(row).contains("当前模型 A")).findFirst().orElseThrow()),"reopened source rows preserve the disabled source");
            check(ModelManagementUi.diagnostics().get("lastError").equals(""),"native UI bridge has no reflection errors: "+ModelManagementUi.diagnostics());
            check(count("settings.configRead")==sourceReads&&count("startup.sourceEnumerate")==0&&count("startup.scan")==0&&count("source.hash")==0&&count("import.scan")==0,"UI construction and clicks never enumerate/hash/import/read settings again");
            System.out.println("MODEL MANAGEMENT UI CPU PASS: one native module icon with Chinese tooltip; 3 native persisted rows; 20 module switches/reopen; original icons preserved; native widths 800/420/320/220; no text or B toolbar entry; no source scans");
        }finally{ModelManagement.onChanged(null);}
    }
    private static Map<String,String> seams(){
        Map<String,String> s=new LinkedHashMap<>();
        s.put("mchorse/bbs_mod/BBSMod","public class BBSMod {public static final mchorse.bbs_mod.settings.SettingsManager settings=new mchorse.bbs_mod.settings.SettingsManager();public static mchorse.bbs_mod.settings.SettingsManager getSettings(){return settings;}public static mchorse.bbs_mod.resources.AssetProvider getProvider(){return new mchorse.bbs_mod.resources.AssetProvider();}}");
        s.put("mchorse/bbs_mod/BBSModClient","public class BBSModClient {public static final mchorse.bbs_mod.l10n.L10n l10n=new mchorse.bbs_mod.l10n.L10n();public static mchorse.bbs_mod.l10n.L10n getL10n(){return l10n;}}");
        s.put("mchorse/bbs_mod/BBSSettings","public class BBSSettings {public static mchorse.bbs_mod.settings.values.numeric.ValueInt editorPreviewCustomWidth,editorPreviewCustomHeight,editorPreviewSizeMode;public static mchorse.bbs_mod.settings.values.numeric.ValueFloat editorPreviewResolutionScale,editorPreviewExportBandsOpacity;}");
        s.put("mchorse/bbs_mod/resources/Link","public class Link {public static Link assets(String path){return new Link();}}");
        s.put("mchorse/bbs_mod/resources/AssetProvider","public class AssetProvider {public java.io.InputStream getAsset(Link link){return new java.io.ByteArrayInputStream(\"{}\".getBytes(java.nio.charset.StandardCharsets.UTF_8));}}");
        s.put(UI+"utils/icons/Icon","public class Icon {}");
        s.put(UI+"utils/icons/Icons","public class Icons {public static final Icon FOLDER=new Icon(),FILM=new Icon(),POSE=new Icon(),MORPH=new Icon();}");
        s.put(SETTINGS+"SettingsThread","public class SettingsThread {public static int queued;public static synchronized void add(Settings settings){queued++;}}");
        s.put("mchorse/bbs_mod/utils/IOUtils","public class IOUtils {public static String readText(java.io.InputStream in)throws java.io.IOException{return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}public static String readText(java.io.File f)throws java.io.IOException{return java.nio.file.Files.readString(f.toPath());}public static void writeText(java.io.File f,String s)throws java.io.IOException{java.nio.file.Files.writeString(f.toPath(),s);}}");
        s.put("mchorse/bbs_mod/utils/keyframes/factories/KeyframeFactories","public class KeyframeFactories {public static final BooleanKeyframeFactory BOOLEAN=new BooleanKeyframeFactory();}");
        s.put("mchorse/bbs_mod/utils/Direction","public enum Direction {TOP,LEFT,RIGHT,BOTTOM}");
        s.put(UI+"framework/UIContext","public class UIContext {public int mouseX,mouseY;}");
        s.put(UI+"framework/elements/IUIElement","public interface IUIElement {void resize();}");
        s.put(UI+"framework/elements/UIElement",ELEMENT);
        s.put(UI+"framework/elements/UIScrollView","public class UIScrollView extends UIElement {public mchorse.bbs_mod.ui.utils.Scroll scroll=new mchorse.bbs_mod.ui.utils.Scroll();public UIScrollView(mchorse.bbs_mod.ui.utils.ScrollDirection d){scroll.direction=d;area.scroll=scroll;}}");
        s.put(UI+"utils/Scroll","public class Scroll {public int scrollSpeed,scrollSize;public double scroll;public mchorse.bbs_mod.ui.utils.ScrollDirection direction;public void clamp(){}public int getScroll(int v){return 0;}}");
        s.put(UI+"utils/ScrollDirection","public enum ScrollDirection {VERTICAL,HORIZONTAL}");
        s.put(UI+"framework/elements/buttons/UIClickable","public class UIClickable<T> extends mchorse.bbs_mod.ui.framework.elements.UIElement {public java.util.function.Consumer<T> callback;public boolean hover;public UIClickable(java.util.function.Consumer<T> c){callback=c;}@SuppressWarnings(\"unchecked\") public void clickItself(){if(callback!=null)callback.accept((T)this);}}");
        s.put(UI+"framework/elements/buttons/UIButton","public class UIButton extends UIClickable<UIButton> {public mchorse.bbs_mod.l10n.keys.IKey label;public UIButton(mchorse.bbs_mod.l10n.keys.IKey k,java.util.function.Consumer<UIButton> c){super(c);label=k;h(20);}}");
        s.put(UI+"framework/elements/buttons/UIIcon","public class UIIcon extends UIClickable<UIIcon> {public UIIcon(mchorse.bbs_mod.ui.utils.icons.Icon i,java.util.function.Consumer<UIIcon> c){super(c);w(20);h(20);}public UIIcon highlight(java.util.function.BooleanSupplier s,mchorse.bbs_mod.utils.Direction d){return this;}}");
        s.put(UI+"framework/elements/buttons/UIToggle","public class UIToggle extends UIClickable<UIToggle> {public mchorse.bbs_mod.l10n.keys.IKey label;private boolean value;public UIToggle(mchorse.bbs_mod.l10n.keys.IKey k,boolean v,java.util.function.Consumer<UIToggle> c){super(c);label=k;value=v;h(20);}public boolean getValue(){return value;}public void clickItself(){value=!value;super.clickItself();}}");
        s.put(UI+"framework/elements/input/text/UITextbox","public class UITextbox extends mchorse.bbs_mod.ui.framework.elements.UIElement {public UITextbox(int n,java.util.function.Consumer<String> c){}public UITextbox placeholder(mchorse.bbs_mod.l10n.keys.IKey k){return this;}public void setText(String s){}}");
        s.put(UI+"framework/elements/overlay/UIOverlayPanel","public class UIOverlayPanel extends mchorse.bbs_mod.ui.framework.elements.UIElement {public mchorse.bbs_mod.ui.framework.elements.UIElement icons=new mchorse.bbs_mod.ui.framework.elements.UIElement(),content=new mchorse.bbs_mod.ui.framework.elements.UIElement();public UIOverlayPanel(mchorse.bbs_mod.l10n.keys.IKey k){w(800).h(600);icons.relative(this).x(1F,-25).y(0).h(20).anchor(1F,0F);icons.row(0).resize();content.relative(this).y(20).w(1F).h(1F,-20);add(icons,content);resize();}}");
        s.put(UI+"framework/elements/overlay/UIOverlay","public class UIOverlay {public static UIOverlayPanel lastPanel;public static UIOverlay addOverlay(mchorse.bbs_mod.ui.framework.UIContext c,UIOverlayPanel p){lastPanel=p;return new UIOverlay();}}");
        s.put(UI+"UIKeys","public class UIKeys {public static final mchorse.bbs_mod.l10n.keys.IKey CONFIG_TITLE=mchorse.bbs_mod.l10n.keys.IKey.constant(\"设置\"),GENERAL_SEARCH=CONFIG_TITLE,GENERAL_PRESETS=CONFIG_TITLE,MORPHING_DEMORPH=CONFIG_TITLE,MORPHING_FROM_MOB=CONFIG_TITLE;}");
        s.put(SETTINGS+"ui/UISettingsLayout","public class UISettingsLayout {public static IValueRow getRow(mchorse.bbs_mod.settings.values.base.BaseValue v){return null;}public interface IValueRow {java.util.List<mchorse.bbs_mod.ui.framework.elements.UIElement> create(mchorse.bbs_mod.ui.framework.elements.UIElement p);}}");
        s.put(UI+"utils/values/UIValues","public class UIValues {public static mchorse.bbs_mod.ui.framework.elements.UIElement resettable(mchorse.bbs_mod.ui.framework.elements.UIElement e,mchorse.bbs_mod.settings.values.base.BaseValue v,Runnable r){return e;}}");
        s.put(UI+"dashboard/UIDashboard","public class UIDashboard extends mchorse.bbs_mod.ui.framework.elements.UIElement {public mchorse.bbs_mod.ui.framework.UIBaseMenu.UIRootElement getRoot(){return new mchorse.bbs_mod.ui.framework.UIBaseMenu.UIRootElement();}}");
        s.put(UI+"framework/UIBaseMenu","public class UIBaseMenu {public static class UIRootElement extends mchorse.bbs_mod.ui.framework.elements.UIElement{}}");
        s.put(UI+"dashboard/panels/UIDashboardPanel","public class UIDashboardPanel extends mchorse.bbs_mod.ui.framework.elements.UIElement {public UIDashboardPanel(mchorse.bbs_mod.ui.dashboard.UIDashboard d){}public void onAppear(Runnable r){}public void onDisappear(Runnable r){}}");
        s.put(UI+"forms/UIFormPalette","public class UIFormPalette extends mchorse.bbs_mod.ui.framework.elements.UIElement {public UIFormList list=new UIFormList();public mchorse.bbs_mod.ui.forms.editors.UIFormEditor editor=new mchorse.bbs_mod.ui.forms.editors.UIFormEditor();public UIFormPalette(java.util.function.Consumer<mchorse.bbs_mod.forms.forms.Form> c){}public UIFormPalette updatable(){return this;}public void cantExit(){}public void immersive(){}public void noBackground(){}public void canModify(){}}");
        s.put(UI+"forms/UIFormList","public class UIFormList extends mchorse.bbs_mod.ui.framework.elements.UIElement {public mchorse.bbs_mod.ui.utils.UIStrip bar=new mchorse.bbs_mod.ui.utils.UIStrip(20);public mchorse.bbs_mod.ui.framework.elements.UIScrollView forms=new mchorse.bbs_mod.ui.framework.elements.UIScrollView(mchorse.bbs_mod.ui.utils.ScrollDirection.VERTICAL);public mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox search=new mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox(100,null);public UIFormList(){bar.w(420).h(20);bar.add(new mchorse.bbs_mod.ui.framework.elements.UIElement().w(20),new mchorse.bbs_mod.ui.framework.elements.UIElement().w(20),new mchorse.bbs_mod.ui.framework.elements.UIElement().w(20),search,new mchorse.bbs_mod.ui.framework.elements.UIElement().w(20),new mchorse.bbs_mod.ui.framework.elements.UIElement().w(20));}}");
        s.put(UI+"forms/editors/UIFormEditor","public class UIFormEditor {public mchorse.bbs_mod.ui.forms.editors.utils.UIPickableFormRenderer renderer=new mchorse.bbs_mod.ui.forms.editors.utils.UIPickableFormRenderer();}");
        s.put(UI+"forms/editors/utils/UIPickableFormRenderer","public class UIPickableFormRenderer extends mchorse.bbs_mod.ui.framework.elements.UIElement{}");
        s.put(UI+"framework/elements/utils/UIModelRenderer","public class UIModelRenderer extends mchorse.bbs_mod.ui.framework.elements.UIElement{}");
        s.put(UI+"morphing/camera/ImmersiveMorphingCameraController","public class ImmersiveMorphingCameraController {public ImmersiveMorphingCameraController(java.util.function.Supplier<?> s){}}");
        s.put(UI+"onboarding/TourAnchors","public class TourAnchors {@SafeVarargs public static void register(String k,java.util.function.Supplier<?>... s){}}");
        s.put("mchorse/bbs_mod/forms/forms/Form","public class Form{}");
        return s;
    }
    private static final String ELEMENT="""
        public class UIElement implements IUIElement {
          public mchorse.bbs_mod.ui.utils.Area area=new mchorse.bbs_mod.ui.utils.Area();
          public final mchorse.bbs_mod.ui.utils.resizers.Margin margin=new mchorse.bbs_mod.ui.utils.resizers.Margin();
          protected mchorse.bbs_mod.ui.utils.resizers.Flex flex=new mchorse.bbs_mod.ui.utils.resizers.Flex();
          protected mchorse.bbs_mod.ui.utils.resizers.IResizer resizer=flex;
          private final java.util.List<IUIElement> children=new java.util.ArrayList<>();public int resizeCount;
          public java.util.List<IUIElement> getChildren(){return children;}
          public void add(IUIElement e){if(e==null)return;children.add(e);resizer.add(this,(UIElement)e);}
          public void add(IUIElement... es){for(IUIElement e:es)add(e);}
          public void prepend(IUIElement e){children.add(0,e);resizer.add(this,(UIElement)e);}
          public void addBefore(IUIElement before,IUIElement e){int i=children.indexOf(before);if(i>=0){children.add(i,e);resizer.add(this,(UIElement)e);}}
          public void removeAll(){children.clear();}
          public UIElement x(int n){flex.x.set(0,n);return this;}public UIElement y(int n){flex.y.set(0,n);return this;}
          public UIElement x(float f,int n){flex.x.set(f,n);return this;}public UIElement y(float f,int n){flex.y.set(f,n);return this;}
          public UIElement w(int n){flex.w.set(0,n);return this;}public UIElement h(int n){flex.h.set(0,n);return this;}
          public UIElement w(float f,int n){flex.w.set(f,n);return this;}public UIElement h(float f,int n){flex.h.set(f,n);return this;}
          public UIElement w(float f){flex.w.set(f);return this;}public UIElement h(float f){flex.h.set(f);return this;}
          public UIElement wh(int w,int h){return w(w).h(h);}public UIElement anchor(float x,float y){flex.x.anchor=x;flex.y.anchor=y;return this;}
          public UIElement relative(UIElement e){flex.relative=e.area;return this;}public UIElement full(UIElement e){return relative(e).w(1F).h(1F);}
          public mchorse.bbs_mod.l10n.keys.IKey tooltipKey;public UIElement tooltip(mchorse.bbs_mod.l10n.keys.IKey k){tooltipKey=k;return this;}public UIElement tooltip(mchorse.bbs_mod.l10n.keys.IKey k,mchorse.bbs_mod.utils.Direction d){tooltipKey=k;return this;}
          public UIElement markContainer(){return this;}public boolean isVisible(){return true;}public boolean isExpanding(){return false;}
          public mchorse.bbs_mod.ui.utils.resizers.Flex getFlex(){return flex;}public mchorse.bbs_mod.ui.utils.resizers.IResizer resizer(){return resizer;}
          public UIElement resizer(mchorse.bbs_mod.ui.utils.resizers.IResizer r){resizer=r;return this;}public UIElement post(mchorse.bbs_mod.ui.utils.resizers.IResizer r){flex.post=r;return this;}
          public mchorse.bbs_mod.ui.utils.resizers.layout.ColumnResizer column(){return column(0);}public mchorse.bbs_mod.ui.utils.resizers.layout.ColumnResizer column(int m){return mchorse.bbs_mod.ui.utils.resizers.layout.ColumnResizer.apply(this,m);}
          public mchorse.bbs_mod.ui.utils.resizers.layout.RowResizer row(int m){return mchorse.bbs_mod.ui.utils.resizers.layout.RowResizer.apply(this,m);}
          public void resize(){resizeCount++;resizer.apply(area);for(IUIElement e:children)e.resize();resizer.postApply(area);}
          public mchorse.bbs_mod.ui.framework.UIContext getContext(){return new mchorse.bbs_mod.ui.framework.UIContext();}
        }
        """;
    private static ClassNode read(JarFile jar,String name)throws Exception{ClassNode n=new ClassNode();new ClassReader(jar.getInputStream(jar.getJarEntry(name+".class"))).accept(n,0);return n;}
    private static byte[] classBytes(ClassNode node){ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();}
    private static void write(Path base,ClassNode n)throws Exception{ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);n.accept(writer);Path f=base.resolve(n.name+".class");Files.createDirectories(f.getParent());Files.write(f,writer.toByteArray());}
    private static void empty(MethodNode m){m.instructions.clear();m.tryCatchBlocks.clear();if(m.localVariables!=null)m.localVariables.clear();Type ret=Type.getReturnType(m.desc);switch(ret.getSort()){case Type.VOID->m.instructions.add(new InsnNode(Opcodes.RETURN));case Type.BOOLEAN,Type.CHAR,Type.BYTE,Type.SHORT,Type.INT->{m.instructions.add(new InsnNode(Opcodes.ICONST_0));m.instructions.add(new InsnNode(Opcodes.IRETURN));}default->{m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));m.instructions.add(new InsnNode(Opcodes.ARETURN));}}}
    private static long calls(ClassNode node,String name){long count=0;for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.owner.equals("local/bbsgeoimporter/ModelManagementUi")&&call.name.equals(name))count++;return count;}
    private static String tooltip(Object element)throws Exception{Object key=field(element,"tooltipKey");return key==null?"":(String)key.getClass().getMethod("get").invoke(key);}
    private static Object field(Object owner,String name)throws Exception{for(Class<?> c=owner.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static List<?> children(Object o)throws Exception{return (List<?>)o.getClass().getMethod("getChildren").invoke(o);}
    private static String label(Object o)throws Exception{Object key=field(o,"label");return (String)key.getClass().getMethod("get").invoke(key);}
    private static String labelUnchecked(Object o){try{return label(o);}catch(Exception ignored){return "";}}
    private static Object labelled(List<?> list,String label){return list.stream().filter(o->labelUnchecked(o).equals(label)).findFirst().orElse(null);}
    private static List<?> sourceRows(Object page)throws Exception{return children(field(page,"options")).stream().filter(o->o.getClass().getSimpleName().equals("UIToggle")).toList();}
    private static int resizes(Object o)throws Exception{return (int)field(o,"resizeCount");}
    private static int dim(Object o,String d)throws Exception{return (int)field(field(o,"area"),d);}
    private static boolean positiveArea(Object o)throws Exception{return dim(o,"w")>0&&dim(o,"h")>0;}
    private static boolean inside(Object child,Object parent)throws Exception{return dim(child,"x")>=dim(parent,"x")&&dim(child,"x")+dim(child,"w")<=dim(parent,"x")+dim(parent,"w")&&dim(child,"y")>=dim(parent,"y")&&dim(child,"y")+dim(child,"h")<=dim(parent,"y")+dim(parent,"h");}
    private static boolean overlap(Object a,Object b)throws Exception{return dim(a,"x")<dim(b,"x")+dim(b,"w")&&dim(b,"x")<dim(a,"x")+dim(a,"w")&&dim(a,"y")<dim(b,"y")+dim(b,"h")&&dim(b,"y")<dim(a,"y")+dim(a,"h");}
    private static long count(String key){Object o=Performance.snapshot().get(key);return o instanceof Map<?,?> m?((Number)m.get("count")).longValue():0;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
