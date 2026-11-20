package local.bbsgeoimporter;
import java.nio.file.*;import java.util.*;import org.objectweb.asm.*;
final class FactoryUvAcceptance {
 static void run(Path base)throws Exception {
  String ns="novel_"+UUID.randomUUID().toString().replace("-","");Path root=base.resolve("source");
  String uv="{\"uv\":[0,0],\"uv_size\":[1,1]}";String faces="{\"north\":"+uv+",\"south\":"+uv+",\"east\":"+uv+",\"west\":"+uv+",\"up\":"+uv+",\"down\":"+uv+"}";
  String geo="{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.x\",\"texture_width\":2,\"texture_height\":2},\"bones\":[{\"name\":\"root\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":"+faces+"}]}]}]}";
  ImporterAcceptance.put(root.resolve("assets/"+ns+"/geo/a.geo.json"),geo);ImporterAcceptance.put(root.resolve("assets/"+ns+"/models/a.geo.json"),geo);
  ImporterAcceptance.put(root.resolve("assets/"+ns+"/models/unrelated.geo.json"),geo);
  ImporterAcceptance.put(root.resolve("assets/"+ns+"/models/different.geo.json"),geo.replace("[0,0]","[1,0]"));
  ImporterAcceptance.png(root.resolve("assets/"+ns+"/textures/unrelated.png"),0xff123456);
  ClassWriter cw=new ClassWriter(0);cw.visit(61,1,"random/ResourceModel",null,"java/lang/Object",null);
  for(String method:List.of("getModelResource","getTextureResource")){var mv=cw.visitMethod(1,method,"()Lnet/minecraft/resources/ResourceLocation;",null,null);mv.visitCode();mv.visitLdcInsn(ns);mv.visitLdcInsn(method.equals("getModelResource")?"geo/a.geo.json":"textures/unrelated.png");mv.visitMethodInsn(Opcodes.INVOKESTATIC,"net/minecraft/resources/ResourceLocation","fromNamespaceAndPath","(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;",false);mv.visitInsn(Opcodes.ARETURN);mv.visitMaxs(2,1);mv.visitEnd();}cw.visitEnd();
  ImporterAcceptance.put(root.resolve("random/ResourceModel.class"),"");Files.write(root.resolve("random/ResourceModel.class"),cw.toByteArray());
  var sources=List.of(ImporterAcceptance.source(ns,"Random Factory",root));var c=new ResourceCatalog(sources);
  ImporterAcceptance.check(c.defaultTextures.size()==2,"static factory resolves canonical export alias, never unrelated identical geometry or different UV");
  new GeoImporter(base.resolve("config"),s->{}).scan(sources);var plans=Map.copyOf(SharedModelAssets.bindingsForTest());
  ImporterAcceptance.check(plans.entrySet().stream().filter(e->!e.getKey().contains("/skins/")&&e.getValue().texture()!=null).count()==2,"factory and shared UV plans published");
  new GeoImporter(base.resolve("config"),s->{}).bootstrap(sources);ImporterAcceptance.check(plans.equals(SharedModelAssets.bindingsForTest()),"factory plans survive restart");
  BbsCpuAcceptance.run(base.resolve("config/bbs/assets"),plans);
  ImporterAcceptance.put(root.resolve("assets/"+ns+"/models/a.geo.json"),geo.substring(0,geo.length()-1)+",\"texture\":\""+ns+":textures/missing.png\"}");
  c=new ResourceCatalog(sources);ImporterAcceptance.check(c.defaultTextures.size()==1,"explicit missing texture prevents UV fallback");
 }
}
