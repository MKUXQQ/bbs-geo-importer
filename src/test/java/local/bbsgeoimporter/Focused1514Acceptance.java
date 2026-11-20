package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class Focused1514Acceptance {
    static void check(boolean ok,String message){ImporterAcceptance.check(ok,message);}
    public static final class ListUI {
        int setups;
        public void setupForms(Categories c){setups++;}
    }
    public static final class Categories {
        long version;
        public long getLastUpdate(){return version;}
    }
    public static void main(String[] args)throws Exception {
        Path mods=Path.of("E:/我的世界/.minecraft/versions/bbs1.20.1动画/mods");
        try(var jar=new JarFile(mods.resolve("bbs-2.7-1.20.1.jar").toFile())){
            var entry=jar.getJarEntry("mchorse/bbs_mod/ui/morphing/UIMorphingPanel.class");
            ClassNode node=new ClassNode();try(var in=jar.getInputStream(entry)){new ClassReader(in).accept(node,0);}
            check(local.bbsgeoimporter.mixin.CaptureMixinPlugin.instrumentMorphing(node),"BBS 2.7 B-key path accepts exact hook");
            var enter=node.methods.stream().filter(m->m.name.equals("enterMorphing")).findFirst().orElseThrow();
            new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name,enter);
        }
        var ui=new ListUI();var categories=new Categories();
        for(int i=0;i<20;i++)MorphingListCache.setupForms(ui,categories);
        check(ui.setups==1,"20 unchanged opens reuse the list");
        categories.version++;MorphingListCache.setupForms(ui,categories);
        mchorse.bbs_mod.BBSSettings.disabledMorphFormCategories.values.add("hidden");MorphingListCache.setupForms(ui,categories);
        MorphingListCache.setupForms(ui,new Categories());check(ui.setups==4,"version, filter, and category identity each invalidate");
        Object nativeManager=new Object();
        var nativeOne=ImportedModelGroups.filterAvailable(nativeManager,new ArrayList<>(List.of("native/a")));
        nativeOne.sort(String::compareTo);
        var nativeSame=ImportedModelGroups.filterAvailable(nativeManager,new ArrayList<>(List.of("native/a")));
        check(nativeSame.equals(List.of("native/a")),"cached merged view remains independently sortable");
        var nativeChanged=ImportedModelGroups.filterAvailable(nativeManager,new ArrayList<>(List.of("native/a","native/b")));
        check(nativeChanged.contains("native/b"),"native model additions invalidate the cached merge");
        var base=Files.createTempDirectory(Path.of("build"),"focused-1514-");
        FactoryUvAcceptance.run(base.resolve("random-factory"));
        var config=base.resolve("startup");var root=base.resolve("novel");var reads=new AtomicInteger();
        var original=ImporterAcceptance.source("unseen_sample","Unseen",root);
        ImporterAcceptance.put(root.resolve("assets/unseen_sample/geo/odd.geo.json"),ImporterAcceptance.GEO);
        var service=new ScanService(config,()->{reads.incrementAndGet();return List.of(original);},s->{});
        try{
            service.startup();for(int i=0;i<20;i++)service.reload(null).get(15,TimeUnit.SECONDS);
            service.request().get(15,TimeUnit.SECONDS);check(reads.get()==1,"repeated reloads use the first startup enumeration");
        }finally{PerformanceAcceptance.stop(service);}
        var addedRoot=base.resolve("random-factory/source");
        var added=ImporterAcceptance.source("another_new_mod","Another New Mod",addedRoot);
        LiveStartupAcceptance.boot(config,List.of(original,added));
        var addedBindings=new TreeMap<String,SharedModelAssets.Binding>();
        SharedModelAssets.bindingsForTest().forEach((k,v)->{if(k.startsWith("Another New Mod/")&&v.texture()!=null)addedBindings.put(k,v);});
        check(!addedBindings.isEmpty(),"new Mod gets a renderer-proven texture on the next startup");
        BbsCpuAcceptance.run(config.resolve("bbs/assets"),addedBindings);
        if(args.length>0){
            Path dinosaur;try(var files=Files.list(mods)){dinosaur=files.filter(p->p.getFileName().toString().contains("jurassicreborn-")).findFirst().orElseThrow();}
            try(var fs=FileSystems.newFileSystem(dinosaur,Map.of())){
                var source=new GeoImporter.ModSource("jurassicreborn","Jurassic Reborn","1.4.2",List.of(fs.getPath("/")));
                config=base.resolve("jurassic");new GeoImporter(config,s->{}).scan(List.of(source));
                System.out.println("JURASSIC_ALL_BLOCK="+SharedModelAssets.bindingsForTest().keySet().stream().filter(k->k.contains("security_fence_base")).toList());
                SharedModelAssets.bindingsForTest().forEach((k,v)->{if(k.contains("security_fence_base")&&!k.contains("/skins/"))System.out.println("FENCE_BIND "+k+" => "+v.texture());});
                var plans=new TreeMap<String,SharedModelAssets.Binding>();SharedModelAssets.bindingsForTest().forEach((k,v)->{if(k.contains("security_fence_base")&&v.texture()!=null)plans.put(k,v);});
                long canonical=plans.keySet().stream().filter(k->!k.contains("/skins/")).count();
                long blockCopies=plans.keySet().stream().filter(k->k.contains("/models/block/")&&!k.contains("/skins/")).count();
                check(canonical>=14,"real Jurassic fence renderer yields the expected canonical bindings");
                System.out.println("JURASSIC_BLOCK_KEYS="+plans.keySet().stream().filter(k->k.contains("/models/block/")).toList());
                check(blockCopies>=5,"exact Geo copies under models/block inherit the unique renderer texture");
                for(var binding:plans.values()){
                    String path=binding.texture();String originalPng=path.substring(path.indexOf("/assets/jurassicreborn/")+1);
                    check(Arrays.equals(Files.readAllBytes(fs.getPath("/"+originalPng)),Files.readAllBytes(config.resolve("bbs/assets").resolve(path))),"external BBS texture bytes equal original renderer resource");
                }
                System.out.println("JURASSIC_FENCE: "+canonical+" canonical bindings verified against original PNGs");
                BbsCpuAcceptance.run(config.resolve("bbs/assets"),plans);
            }
        }
        System.out.println("FOCUSED PASS: 20 warm opens / 1 setup; 20 reloads / 1 enumeration; isolated binding and real BBS CPU Link");
    }
}
