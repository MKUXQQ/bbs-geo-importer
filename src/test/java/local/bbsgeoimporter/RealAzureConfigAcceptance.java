package local.bbsgeoimporter;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;

/** Read-only real external data: no third-party class initialization or GL context. */
public final class RealAzureConfigAcceptance {
    public static void main(String[] args)throws Exception {
        Path source=Path.of(args[0]),bbs=Path.of(args[1]),out=Files.createTempDirectory(Path.of("build"),"real-azure-config-");
        GeoImporter.ModSource external;
        try(var discovered=ExternalSources.discover(out.resolve("explicit"),out.resolve("cache"),source.getParent(),List.of(),s->{})){
            external=discovered.sources().stream().filter(s->s.roots().contains(source)).findFirst().orElseThrow();
            System.out.println("Visible loose pack directories via startup discovery: "+discovered.sources().stream().map(GeoImporter.ModSource::displayModId).toList());
        }
        var result=new GeoImporter(out,s->{}).scan(List.of(external));
        JsonObject mod=ImporterAcceptance.json(out.resolve("bbs/model-import-index.json")).getAsJsonObject("mods").getAsJsonObject(external.id());
        JsonObject geo=mod.getAsJsonObject("files").getAsJsonObject("assets/asterismarcanum/geo/celestial_staff.geo.json");
        var plan=geo.getAsJsonObject("bindingPlan");
        String target=geo.get("target").getAsString(),key=mod.get("directory").getAsString()+"/"+target.substring(0,target.lastIndexOf('/'));
        var binding=SharedModelAssets.bindingsForTest().get(key);
        System.out.println("Real Asterism Arcanum external Geos="+result.discovered()+"; staff base="+(binding==null?null:binding.texture())+"; layer declarations="+plan.getAsJsonArray("layerDeclarations"));
        ImporterAcceptance.check(binding!=null&&binding.texture()!=null,"same Azure config builder binds the staff's explicit PNG");
        Path assets=out.resolve("bbs/assets"),png=source.resolve("assets/asterismarcanum/textures/item/celestial_staff.png");
        ImporterAcceptance.check(Files.mismatch(png,assets.resolve(binding.texture()))==-1,"final Link points to bytes from renderer-declared PNG");
        ImporterAcceptance.check(plan.getAsJsonArray("layerDeclarations").toString().contains("AzAutoGlowingLayer"),"unsupported glow retained independently of base");
        BbsCpuAcceptance.run(assets,Map.of(key,binding),bbs);
        System.out.println("Real Azure staff: BBS final baseTexture retained original PNG; auto-glow unsupported. Offline source.");
    }
}
