package net.peercraft.client.theme;

import net.peercraft.network.diagnostics.*;
import java.util.*;

/** Shared diagnostic interpretation and translated text for every supported client. */
public final class SessionPresentation {
    public interface Text { String get(String key,Object... args); }
    private static String number(double n) { return n<0?"—":String.format(Locale.ROOT,"%.1f",n); }
    public static double worldTick(SessionMonitor m,double local) {
        if(local>=0) return local;
        for(SessionMonitor.View v:m.views()) if("host".equals(v.id)&&v.supported) return v.tick;
        return -1;
    }
    public static int files(SessionMonitor m) {
        int n=m.local().transfers;
        for(SessionMonitor.View v:m.views()) if("host".equals(v.id)) return v.transfers;
        return n;
    }
    public static String diagnosis(SessionMonitor m,double tick) {
        if(tick>50) return "diagnosis.cpu";
        boolean measured=tick>=0;
        for(SessionMonitor.View v:m.views()) {
            if(v.supported && v.samples>=5) measured=true;
            if(!v.supported) continue;
            if(v.samples>=5 && (v.loss>=5 || v.jitter>30)) return files(m)>0?"diagnosis.files_suspected":"diagnosis.network";
            if(v.queue>65536) return files(m)>0?"diagnosis.files_suspected":"diagnosis.queue";
            if(v.samples>=5 && v.rtt>150) return "diagnosis.latency";
        }
        return measured?"diagnosis.ok":"diagnosis.wait";
    }
    public static List<String> lines(String page,SessionMonitor m,double localTick,Text tr) {
        List<String> out=new ArrayList<String>();
        if("NETWORK".equals(page)) {
            out.add(tr.get("metric.probes"));out.add(tr.get("metric.host_view"));
            for(SessionMonitor.View v:m.views()) {
                if("host".equals(v.id)&&localTick>=0) continue;
                out.add("");out.add(v.name);
                if(!v.supported) {out.add(tr.get("metric.unsupported"));continue;}
                out.add(tr.get("metric.latency",number(v.rtt),number(v.jitter)));
                out.add(tr.get("metric.loss",number(v.loss),v.samples));
                out.add(tr.get("metric.traffic",number(v.upload/1024),number(v.download/1024)));
                out.add(tr.get("metric.queue",v.queue,v.retries));
            }
            if(m.views().size()<=1 && localTick>=0) out.add(tr.get("metric.no_peers"));
            out.add("");out.add(tr.get("metric.queue_note"));
            if(localTick<0 && !"idle".equals(m.stage())) out.add(tr.get("metric.stage",tr.get(m.stage().startsWith("peercraft.")?m.stage():"stage."+m.stage()),m.stageSeconds()));
            out.add(tr.get("network.advice"));
        } else if("WORLD".equals(page)) {
            double tick=worldTick(m,localTick);
            out.add(tr.get("world.value",number(tick)));
            out.add(tr.get(localTick>=0?"metric.world_local":"metric.world_remote"));
            out.add("");out.add(tr.get(diagnosis(m,tick)));
            if(tick>50) out.add(tr.get("world.advice"));
            if(tick<0) out.add(tr.get("metric.unsupported"));
        } else if("FILES".equals(page)) {
            out.add(tr.get("metric.local_files",m.local().transfers,number(m.local().remaining/1048576.0)));
            out.addAll(Arrays.asList(m.local().files));
            out.add(TransferTraffic.limitKiB()==0?tr.get("metric.limit_off"):tr.get("metric.limit",TransferTraffic.limitKiB()));
            out.add("");
            for(SessionMonitor.View v:m.views()) if(v.supported && v.transfers>0 && !(localTick>=0 && "host".equals(v.id))) {
                out.add(tr.get("metric.peer_files",v.name,v.transfers,number(v.remaining/1048576.0)));
                if(v.files!=null) out.addAll(Arrays.asList(v.files));
            }
            out.add(tr.get("metric.file_advice"));
        } else if("HOSTS".equals(page)) {
            out.add(tr.get(m.testing()?"metric.test_running":"metric.test_intro"));
            out.add(tr.get("metric.estimate_note"));out.add("");
            List<SessionMonitor.View> all=m.views();
            SessionMonitor.View host=null;for(SessionMonitor.View v:all) if("host".equals(v.id)) host=v;
            List<Rank> ranks=new ArrayList<Rank>();
            for(SessionMonitor.View v:all) {
                double worst=0;boolean complete=m.comparisonCurrent() && !m.testing() && v.supported && v.benchmark>0;
                for(SessionMonitor.View other:all) if(other!=v) {
                    double rtt=-1;
                    if("host".equals(v.id)) rtt=other.samples>=5&&other.loss<5?other.rtt:-1;
                    else if("host".equals(other.id)) rtt=v.samples>=5&&v.loss<5?v.rtt:-1;
                    else for(SessionMonitor.Pair p:m.pairs()) if((v.id.equals(p.a)&&other.id.equals(p.b))||(v.id.equals(p.b)&&other.id.equals(p.a)))
                        if(p.samples>=5 && p.loss<5) rtt=p.rtt;
                    if(rtt<0) complete=false;else worst=Math.max(worst,rtt);
                }
                double estimate=host!=null && host.tick>=0 && host.benchmark>0 && v.benchmark>0?host.tick*v.benchmark/host.benchmark:-1;
                ranks.add(new Rank(v,complete&&estimate>=0&&v.eligible,worst,estimate));
            }
            Collections.sort(ranks,(a,b)-> {int ready=Boolean.compare(b.ready,a.ready);return ready!=0?ready:Double.compare(a.score(),b.score());});
            for(Rank r:ranks) {
                out.add(r.view.name + ("host".equals(r.view.id)?" · "+tr.get("metric.current_host"):""));
                if(!r.view.eligible) out.add(tr.get("metric.ineligible"));
                if(r.ready) out.add(tr.get("metric.rank",number(r.worst),number(r.estimate)));
                else out.add(tr.get("metric.incomplete"));
                out.add("");
            }
            for(SessionMonitor.Pair p:m.pairs()) out.add(tr.get("metric.pair",name(all,p.a),name(all,p.b),number(p.rtt),number(p.loss)));
            out.add(tr.get("metric.no_auto_handoff"));
        }
        return out;
    }
    private static String name(List<SessionMonitor.View> all,String id) {for(SessionMonitor.View v:all)if(id.equals(v.id))return v.name;return id;}
    private static final class Rank {
        final SessionMonitor.View view;final boolean ready;final double worst,estimate;
        Rank(SessionMonitor.View v,boolean ready,double worst,double estimate){this.view=v;this.ready=ready;this.worst=worst;this.estimate=estimate;}
        double score(){return Math.max(worst/150,estimate/50);}
    }
    private SessionPresentation() { }
}
