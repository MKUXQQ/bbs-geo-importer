package local.bbsgeoimporter;
import java.util.*;
/** Known resource API spellings, independent of any content Mod or model name. */
final class ResourceMethods {
    static List<String> names(String kind){return switch(kind){
        case "geo" -> List.of("getModelResource","getModelLocation");
        case "texture" -> List.of("getTextureResource","getTextureLocation");
        case "animation" -> List.of("getAnimationResource","getAnimationFileLocation");
        default -> List.of();
    };}
    static String kind(String name){for(String kind:List.of("geo","texture","animation"))if(names(kind).contains(name))return kind;return null;}
    static boolean location(String type){return Set.of("net/minecraft/resources/ResourceLocation","net/minecraft/util/ResourceLocation","net/minecraft/util/Identifier","net/minecraft/class_2960").contains(type);}
}
