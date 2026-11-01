import io.tabletennisreview.usbdiagnostics.*;
import java.util.*; import java.util.concurrent.atomic.*;import java.security.*;
public class Config94Check {
 static void ok(boolean v){if(!v)throw new AssertionError();}
 static byte[] response(){String h="04 E0 5E BB CF BC C7 42 9E A7 BF 37 33 6C F7 0B 00 00 00 02 00 03 03 01 00 00";return HexFormat.of().parseHex(h.replace(" ",""));}
 static Config94Experiment start(){Config94Experiment g=new Config94Experiment();g.record(CdcAction.A1,7);g.record(CdcAction.A0,0);g.begin(42,100);return g;}
 public static void main(String[] args)throws Exception {
  byte[] p=Config94Experiment.Companion.payload();ok(p.length==94);ok(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p)).equals(Config94Experiment.SHA256));p[0]=0;ok(Config94Experiment.Companion.payload()[0]==(byte)0xb5);
  for(int split=1;split<26;split++){Config94Experiment g=start();ok(!g.observe(42,Arrays.copyOfRange(response(),0,split),101));ok(!g.ready(42));ok(g.observe(42,Arrays.copyOfRange(response(),split,26),102));ok(!g.ready(42));g.sent(8);ok(g.ready(42));ok(!g.reserve(42,false));ok(!g.reserve(43,true));ok(g.reserve(42,true));ok(!g.reserve(42,true));ok(g.validReserved(42));g.reset();ok(!g.ready(42)&&!g.validReserved(42));}
  Config94Experiment late=start();ok(!late.observe(42,response(),3101));late.sent(8);ok(!late.ready(42));
  Config94Experiment wrong=start();byte[] bad=response();bad[4]^=1;ok(!wrong.observe(42,bad,101));wrong.sent(8);ok(!wrong.ready(42));ok(!wrong.observe(43,response(),102));
  Config94Experiment failure=start();failure.observe(42,response(),101);failure.sent(7);ok(!failure.ready(42));
  Config94Experiment mixed=new Config94Experiment();mixed.record(CdcAction.A1,7);mixed.record(CdcAction.A0,0);mixed.record(CdcAction.A2,0);mixed.begin(42,100);mixed.observe(42,response(),101);mixed.sent(8);ok(!mixed.ready(42));
  Config94Experiment reverse=new Config94Experiment();reverse.record(CdcAction.A0,0);reverse.record(CdcAction.A1,7);reverse.begin(42,100);reverse.observe(42,response(),101);reverse.sent(8);ok(!reverse.ready(42));
  Config94Experiment race=start();race.sent(8);race.observe(42,response(),101);AtomicInteger wins=new AtomicInteger();Thread[] ts=new Thread[32];for(int i=0;i<32;i++){ts[i]=new Thread(()->{if(race.reserve(42,true))wins.incrementAndGet();});ts[i].start();}for(Thread t:ts)t.join();ok(wins.get()==1);
  System.out.println("PASS: exact payload/hash, all 25 split points, early RX, missing/wrong/late reply, mixed/reversed CDC, short TX8, explicit confirmation, 32-thread one reserve, stale epoch, reset");
 }
}
