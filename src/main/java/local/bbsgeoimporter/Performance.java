package local.bbsgeoimporter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
/** Diagnostic counters; no disk IO on render/client hooks. */
public final class Performance {
    private static final Map<String,Stat> stats=new ConcurrentHashMap<>();
    private static final class Stat {final LongAdder count=new LongAdder(),nanos=new LongAdder(),bytes=new LongAdder();volatile long max;volatile String thread; synchronized void add(long ns,long size){count.increment();nanos.add(ns);bytes.add(size);max=Math.max(max,ns);thread=Thread.currentThread().getName();}}
    static long start(){return System.nanoTime();}
    static void end(String name,long start){event(name,System.nanoTime()-start,0);}
    static void event(String name,long ns,long bytes){stats.computeIfAbsent(name,k->new Stat()).add(ns,bytes);}
    static void count(String name,long bytes){event(name,0,bytes);}
    public static Map<String,Object> snapshot(){Map<String,Object> result=new TreeMap<>();stats.forEach((name,s)->result.put(name,Map.of("count",s.count.sum(),"millis",s.nanos.sum()/1e6,"maxMillis",s.max/1e6,"bytes",s.bytes.sum(),"thread",s.thread)));return result;}
    static void reset(){stats.clear();}
}
