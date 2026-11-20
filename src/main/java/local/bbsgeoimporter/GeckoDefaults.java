package local.bbsgeoimporter;
import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
/** Static adapter for GeckoLib 4's documented Defaulted*GeoModel constructor paths. */
final class GeckoDefaults {
    static void analyze(ResourceCatalog catalog) {
        for(var files:catalog.entries.values()) for(var entry:files.values()) if(entry.path().endsWith(".class")) try {
            String[] subtype={null}; List<String> literals=new ArrayList<>(); boolean[] reject={false}; int[] resourceConstructors={0}, defaultConstructors={0};
            new ClassReader(AnalysisInputs.bytes(entry.file())).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visit(int v,int a,String n,String sig,String parent,String[] interfaces) {
                    for(String type:List.of("Entity","Block","Item")) if(parent.equals("software/bernie/geckolib/model/Defaulted"+type+"GeoModel")) subtype[0]=type.toLowerCase(Locale.ROOT);
                }
                @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                    if(subtype[0]==null)return null;
                    if(n.equals("getModelResource")||n.equals("getTextureResource")||n.equals("getAnimationResource")||n.startsWith("buildFormatted"))reject[0]=true;
                    if(!n.equals("<init>"))return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitLdcInsn(Object o){if(o instanceof String x)literals.add(x);else reject[0]=true;}
                        @Override public void visitVarInsn(int op,int var){if(op!=Opcodes.ALOAD||var!=0)reject[0]=true;}
                        @Override public void visitTypeInsn(int op,String type){if(op!=Opcodes.NEW||!(type.equals("net/minecraft/resources/ResourceLocation")||type.equals("net/minecraft/class_2960")))reject[0]=true;}
                        @Override public void visitInsn(int op){if(op!=Opcodes.DUP&&op!=Opcodes.ICONST_0&&op!=Opcodes.ICONST_1&&op!=Opcodes.RETURN)reject[0]=true;}
                        @Override public void visitMethodInsn(int op,String owner,String method,String desc,boolean itf){
                            if(op==Opcodes.INVOKESPECIAL && method.equals("<init>") && (owner.equals("net/minecraft/resources/ResourceLocation")||owner.equals("net/minecraft/class_2960")) && desc.equals("(Ljava/lang/String;Ljava/lang/String;)V") && literals.size()==2) resourceConstructors[0]++;
                            else if(op==Opcodes.INVOKESTATIC && owner.equals("net/minecraft/resources/ResourceLocation") && method.equals("fromNamespaceAndPath") && desc.equals("(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;") && literals.size()==2) resourceConstructors[0]++;
                            else if(op==Opcodes.INVOKESPECIAL && method.equals("<init>") && owner.equals("software/bernie/geckolib/model/Defaulted"+Character.toUpperCase(subtype[0].charAt(0))+subtype[0].substring(1)+"GeoModel") && (desc.equals("(Lnet/minecraft/resources/ResourceLocation;)V") || desc.equals("(Lnet/minecraft/resources/ResourceLocation;Z)V") || desc.equals("(Lnet/minecraft/class_2960;)V") || desc.equals("(Lnet/minecraft/class_2960;Z)V")) && resourceConstructors[0]==1) defaultConstructors[0]++;
                            else reject[0]=true;
                        }
                        @Override public void visitTableSwitchInsn(int min,int max,Label d,Label... labels){reject[0]=true;}
                        @Override public void visitLookupSwitchInsn(Label d,int[] keys,Label[] labels){reject[0]=true;}
                        @Override public void visitJumpInsn(int op,Label l){reject[0]=true;}
                        @Override public void visitFieldInsn(int op,String o,String n,String d){reject[0]=true;}
                        @Override public void visitInvokeDynamicInsn(String n,String d,Handle h,Object... a){reject[0]=true;}
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            if(subtype[0]==null||reject[0]||literals.size()!=2||resourceConstructors[0]!=1||defaultConstructors[0]!=1)continue;
            String root="assets/"+literals.get(0)+"/", path=subtype[0]+"/"+literals.get(1);
            var model=unique(catalog,root+"geo/"+path+".geo.json"); if(model==null)continue;
            for(String p:List.of(root+"textures/"+path+".png",root+"animations/"+path+".animation.json")) {
                var dep=unique(catalog,p);
                if(dep!=null)catalog.bundles.get(ResourceCatalog.key(model)).add(new ResourceCatalog.Edge(ResourceCatalog.key(model),ResourceCatalog.key(dep),"geckolib4_default_constructor:"+ResourceCatalog.key(entry)));
                else {
                    String kind=p.endsWith(".png")?"texture":"animation";
                    catalog.requirements.add(new GenericResourceResolver.Requirement(ResourceCatalog.key(model),kind,p,"MISSING",List.of(),"geckolib4_default_constructor:"+ResourceCatalog.key(entry),"SINGLE"));
                    catalog.event(model,"missing_default_reference",p);
                }
            }
        } catch(Exception e){catalog.event(entry,"default_adapter_failed",e.toString());}
    }
    private static ResourceCatalog.Entry unique(ResourceCatalog c,String path){var found=new ArrayList<ResourceCatalog.Entry>();for(var files:c.entries.values()){var e=files.get(path);if(e!=null)found.add(e);}return found.size()==1?found.get(0):null;}
}
