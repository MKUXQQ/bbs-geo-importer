package local.bbsgeoimporter;
import java.util.*;
/** Only explicit, cross-namespace references may leave an external archive's private domain. */
final class ExternalDependencies {
    static ResourceCatalog resolve(ResourceCatalog catalog){
        Map<String,Set<String>> namespaces=new HashMap<>();Map<String,List<ResourceCatalog.Entry>> resources=new HashMap<>();
        for(var files:catalog.entries.values())for(var entry:files.values()){
            String address=ResourceEvidence.address(entry);if(address==null)continue;
            namespaces.computeIfAbsent(catalog.domain(entry.owner()),k->new HashSet<>()).add(address.substring(0,address.indexOf(':')));
            if(!entry.kind().equals("other"))resources.computeIfAbsent(entry.kind()+"|"+address,k->new ArrayList<>()).add(entry);
        }
        boolean changed=false;
        for(int i=0;i<catalog.requirements.size();i++){
            var requirement=catalog.requirements.get(i);int colon=requirement.model().indexOf(':');if(colon<1)continue;
            String domain=catalog.domain(requirement.model().substring(0,colon));
            if(!domain.startsWith("external:")||!Set.of("MISSING","UNRESOLVED").contains(requirement.status()))continue;
            String address;try{address=ResourceEvidence.address(requirement.reference());}catch(RuntimeException ignored){continue;}
            if(namespaces.getOrDefault(domain,Set.of()).contains(address.substring(0,address.indexOf(':'))))continue;
            var candidates=resources.getOrDefault(requirement.kind()+"|"+address,List.of()).stream().filter(e->!catalog.domain(e.owner()).equals(domain)).toList();
            if(candidates.size()!=1)continue;
            var resource=candidates.get(0);String key=ResourceCatalog.key(resource),evidence=requirement.evidence()+"; explicit_unique_external_dependency:"+key;
            catalog.requirements.set(i,new GenericResourceResolver.Requirement(requirement.model(),requirement.kind(),requirement.reference(),"FOUND",List.of(key),evidence,requirement.role()));
            var edges=catalog.bundles.get(requirement.model());if(edges!=null)edges.add(new ResourceCatalog.Edge(requirement.model(),key,evidence));changed=true;
        }
        if(changed)catalog.invalidateRequirements();return catalog;
    }
}
