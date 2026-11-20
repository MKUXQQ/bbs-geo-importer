package local.bbsgeoimporter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/** Coalesces importer-owned WatchDog events; external writes keep BBS's original handling. */
public final class ManagedWrites {
    private record Receipt(long size,long modified,long expires,boolean directory){}
    private static final Map<Path,Receipt> receipts=new ConcurrentHashMap<>();
    static void published(Path path){try{long until=System.nanoTime()+30_000_000_000L;Path p=path.toAbsolutePath().normalize();receipts.put(p,new Receipt(Files.size(p),Files.getLastModifiedTime(p).toMillis(),until,false));for(p=p.getParent();p!=null&&p.toString().contains("models");p=p.getParent())receipts.put(p,new Receipt(0,Files.getLastModifiedTime(p).toMillis(),until,true));if(receipts.size()>32768)receipts.entrySet().removeIf(e->e.getValue().expires()<System.nanoTime());}catch(Exception ignored){}}
    public static boolean suppress(Path path){try{Path p=path.toAbsolutePath().normalize();Receipt r=receipts.get(p);if(r==null)return false;if(r.expires()<System.nanoTime()){receipts.remove(p,r);return false;}boolean same=r.directory()?Files.isDirectory(p)&&Files.getLastModifiedTime(p).toMillis()==r.modified():Files.isRegularFile(p)&&Files.size(p)==r.size()&&Files.getLastModifiedTime(p).toMillis()==r.modified();if(same)Performance.count("bbs.managedEventCoalesced",0);return same;}catch(Exception ex){return false;}}
    private ManagedWrites(){}
}
