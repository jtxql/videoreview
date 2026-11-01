import io.tabletennisreview.usbdiagnostics.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Production generation ledger + production gate + request lifecycle. No Android/USB. */
public class UsbGenerationCheck {
 static int assertions, scenarios;
 static void ok(boolean condition,String m){assertions++;if(!condition)throw new AssertionError(m);}
 static void rejects(Runnable r){boolean caught=false;try{r.run();}catch(IllegalStateException e){caught=true;}ok(caught,"reject stale ownership");}
 static void scenario(){scenarios++;}
 static final Function1<String,Unit> log=s->Unit.INSTANCE;
 static class Harness {
  final UsbGenerationTracker<Object> tracker=new UsbGenerationTracker<>();
  final ExclusiveUsbLease<Object> leases=new ExclusiveUsbLease<>();
  long now=10;
  Harness(){tracker.observing(1,true);}
  UsbOpenGeneration<Object> open(String name,long owner){Long t=leases.acquire();if(t==null)return null;return tracker.opened(t,owner,new Object(),name,7,now++);}
  void failOpen(){Long t=leases.acquire();ok(t!=null,"reserve before native open");leases.release(t);}
  void close(UsbOpenGeneration<Object> r,boolean normal){if(normal)tracker.stopping(r,true);tracker.closed(r,r.getConnection(),now++,normal);leases.release(r.getToken());}
  DetachObservation<Object> snapshot(UsbOpenGeneration<Object> r,DevicePresence presence){return tracker.capture(r,10,r.getName(),r.getId(),ObservationSource.DetachBroadcast,now++,presence,AsyncCleanupState.Quarantined);}
  AssociationDecision classify(DetachObservation<Object> o,UsbOpenGeneration<Object> r,DetachPolicy p){return tracker.association(o,r.getToken(),r.getConnection(),p);}
  void io(UsbOpenGeneration<Object> r){tracker.ioFailed(r,r.getConnection(),now++);}
 }
 static SupplementReadiness ready(){return new SupplementReadiness(true,true,true,true,true,true,true);}
 static class Clock implements CleanupClock {long now;public long millis(){return now;}public void pause(long n){now+=n;}}
 static class Io implements RequestIo {
  final Object connection;final Clock clock=new Clock();int waits,cancels,closes;boolean complete,throwClose;
  Io(Object c){connection=c;}
  public boolean cancel(){cancels++;return false;}
  public WaitResult waitFor(long timeout){waits++;return complete?WaitResult.Original:WaitResult.Null;}
  public void closeRequest(){closes++;if(throwClose)throw new IllegalStateException("close");}
 }
 static UsbAsyncCleanupState quarantined(Io io){UsbAsyncCleanupState s=new UsbAsyncCleanupState();s.queued();ok(!s.drain(io,io.clock,false,false,log),"initial unconfirmed");s.readerFinished();s.takeOver(true,true);return s;}
 public static void main(String[] args)throws Exception {
  scenario();Harness h=new Harness();UsbOpenGeneration<Object> a=h.open("A",1);
  ok(a!=null&&a.getOrigin()==OpenOrigin.FirstObservedOpen&&h.leases.owns(a.getToken()),"first open unique owner");
  scenario();long old=a.getToken();Object oldConnection=a.getConnection();h.close(a,true);a=h.open("A",1);
  ok(a.getToken()>old&&a.getConnection()!=oldConnection&&a.getOrigin()==OpenOrigin.NormalReopen,"same address clean reopen not physical reuse");
  scenario();h.close(a,true);a=h.open("B",1);ok(a!=null&&a.getToken()>old,"different address opens");
  scenario();h.close(a,true);h.failOpen();h.failOpen();a=h.open("B",1);ok(a.getOrigin()==OpenOrigin.NormalReopen,"failed native opens do not poison history");
  scenario();h.close(a,true);h.tracker.observing(1,false);h.tracker.observing(2,true);a=h.open("B",2);
  ok(a!=null&&a.getOrigin()==OpenOrigin.NormalReopen,"new Activity does not imply address reuse");
  scenario();DetachObservation<Object> late=h.snapshot(a,DevicePresence.Absent);UsbOpenGeneration<Object> retired=a;h.close(a,true);a=h.open("B",2);
  ok(h.classify(late,a,DetachPolicy.CorroboratedApplicationEvidence).getAssociation()==DetachAssociation.Unrelated,"old captured detach cannot target new open");
  ok(!h.tracker.disconnect(late,a.getToken(),a.getConnection())&&!a.getLogicallyDisconnected(),"old detach cannot disconnect new");
  scenario();ok(!h.tracker.association(h.snapshot(a,DevicePresence.Absent),retired.getToken(),retired.getConnection(),DetachPolicy.CorroboratedApplicationEvidence).getAssociation().equals(DetachAssociation.Correlated),"same name not identity");
  ok(h.tracker.association(h.snapshot(a,DevicePresence.Absent),a.getToken(),new Object(),DetachPolicy.CorroboratedApplicationEvidence).getAssociation()==DetachAssociation.Unrelated,"same token wrong connection rejected");
  scenario();h.tracker.quarantine(a);h.leases.retain(a.getToken(),a.getConnection());
  ok(h.open("C",3)==null&&h.leases.getRetained()==a.getConnection(),"quarantine blocks new Manager");
  final Harness isolated=h;final UsbOpenGeneration<Object> held=a;rejects(()->isolated.leases.release(held.getToken()+1));
  scenario();ExclusiveUsbLease<Object> shared=new ExclusiveUsbLease<>();CountDownLatch start=new CountDownLatch(1);AtomicInteger nativeOpens=new AtomicInteger();List<Thread> threads=new ArrayList<>();
  for(int i=0;i<20;i++){Thread t=new Thread(()->{try{start.await();}catch(Exception e){throw new RuntimeException(e);}if(shared.acquire()!=null)nativeOpens.incrementAndGet();});threads.add(t);t.start();}start.countDown();for(Thread t:threads)t.join();ok(nativeOpens.get()==1,"20 Managers only one reaches open");
  scenario();h=new Harness();a=h.open("A",1);h.io(a);DetachObservation<Object> first=h.snapshot(a,DevicePresence.Absent),second=h.snapshot(a,DevicePresence.Absent);
  ok(h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence).getAssociation()==DetachAssociation.Correlated,"bound application association");
  ok(h.tracker.disconnect(first,a.getToken(),a.getConnection())&&h.tracker.disconnect(second,a.getToken(),a.getConnection()),"duplicate detach same record");
  scenario();ok(h.classify(first,a,DetachPolicy.Strict).getAssociation()==DetachAssociation.Unknown,"raw broadcast cannot prove physical generation");
  ok(!ready().eligible(h.classify(first,a,DetachPolicy.Strict)),"unknown performs no extra wait");
  scenario();AssociationDecision correlated=h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence);ok(ready().eligible(correlated),"all supplement conditions");
  scenario();for(int missing=0;missing<7;missing++){boolean[] flags={true,true,true,true,true,true,true};flags[missing]=false;SupplementReadiness r=new SupplementReadiness(flags[0],flags[1],flags[2],flags[3],flags[4],flags[5],flags[6]);ok(!r.eligible(correlated),"each missing prerequisite denies supplement");}
  scenario();Io io=new Io(a.getConnection());UsbAsyncCleanupState life=quarantined(io);int baseline=io.waits;
  ok(!life.drain(io,io.clock,true,true,log)&&io.waits==baseline+5&&io.cancels==1&&!life.close(io,log)&&io.closes==0,"supplement Null remains isolated no recancel/free");
  int total=io.waits;ok(!life.drain(io,io.clock,true,true,log)&&io.waits==total,"duplicate detach cannot repeat supplemental waits");
  scenario();io=new Io(a.getConnection());life=quarantined(io);io.complete=true;
  ok(io.connection==a.getConnection()&&life.drain(io,io.clock,true,true,log)&&life.close(io,log)&&io.closes==1&&io.cancels==1,"supplement completion returned on original connection");
  scenario();io=new Io(new Object());life=new UsbAsyncCleanupState();io.throwClose=true;
  ok(!life.close(io,log)&&life.getState()==AsyncCleanupState.Quarantined&&!life.close(io,log)&&io.closes==1,"request close exception no retry");
  ConnectionCloseOnce once=new ConnectionCloseOnce();AtomicInteger releases=new AtomicInteger(),closes=new AtomicInteger();
  ConnectionCleanupIo cleanup=new ConnectionCleanupIo(){public void releaseInterfaces(Function1<? super String,Unit> f){releases.incrementAndGet();throw new IllegalStateException("release");}public void closeConnection(){closes.incrementAndGet();throw new IllegalStateException("close");}};
  ok(!once.finish(cleanup,log)&&!once.finish(cleanup,log)&&releases.get()==1&&closes.get()==1,"cleanup exceptions single attempt");
  scenario();h=new Harness();a=h.open("A",1);DetachObservation<Object> beforeIo=h.snapshot(a,DevicePresence.Absent);h.io(a);
  ok(h.classify(beforeIo,a,DetachPolicy.CorroboratedApplicationEvidence).getAssociation()==DetachAssociation.Unknown,"detach before IO cannot retroactively change immutable snapshot");
  scenario();h=new Harness();a=h.open("A",1);h.io(a);first=h.snapshot(a,DevicePresence.Absent);
  ok(first.getIoFailureNanos()!=null&&h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence).getAssociation()==DetachAssociation.Correlated,"IO before detach snapshot binds original connection");
  scenario();h.tracker.attached("A");ok(h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence).getAssociation()==DetachAssociation.Unknown,"same address reattach conflict never waits");
  scenario();h=new Harness();a=h.open("A",1);h.io(a);first=h.snapshot(a,DevicePresence.Absent);h.tracker.observing(1,false);h.tracker.observing(2,true);
  ok(h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence).getAssociation()==DetachAssociation.Unknown,"coverage gap invalidates original evidence");
  scenario();h=new Harness();a=h.open("A",1);h.io(a);first=h.snapshot(a,DevicePresence.Absent);
  h.tracker.capture(a,10,"A",7,ObservationSource.DetachBroadcast,100,DevicePresence.QueryFailed,AsyncCleanupState.Quarantined);
  ok(h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence).getAssociation()==DetachAssociation.Unknown,"query failure invalidates earlier evidence");
  scenario();h=new Harness();a=h.open("A",1);h.io(a);first=h.snapshot(a,DevicePresence.Present);
  ok(h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence).getAssociation()==DetachAssociation.Unrelated&&!h.tracker.disconnect(first,a.getToken(),a.getConnection()),"old broadcast cannot stop still present new session");
  scenario();h=new Harness();a=h.open("A",1);first=h.snapshot(a,DevicePresence.Absent);h.tracker.disconnect(first,a.getToken(),a.getConnection());h.close(a,false);h.tracker.attached("A");a=h.open("A",1);
  ok(a.getOrigin()==OpenOrigin.ObservationUnknown,"physical reattach not normal reopen");
  scenario();h=new Harness();a=h.open("A",1);first=h.snapshot(a,DevicePresence.Absent);h.tracker.disconnect(first,a.getToken(),a.getConnection());h.tracker.capture(a,10,"A",7,ObservationSource.Enumeration,100,DevicePresence.Present,null);
  ok(a.getObservationConflict(),"presence contradiction latched not overwritten");
  scenario();h=new Harness();a=h.open("A",1);Object reusedObject=a.getConnection();first=h.snapshot(a,DevicePresence.Absent);long oldToken=a.getToken();h.close(a,true);
  Long reserved=h.leases.acquire();a=h.tracker.opened(reserved,1,reusedObject,"A",7,100);
  ok(a.getToken()!=oldToken&&h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence).getAssociation()==DetachAssociation.Unrelated,"even same Java connection object cannot reuse token identity");
  scenario();h=new Harness();a=h.open("A",1);h.io(a);first=h.snapshot(a,DevicePresence.Absent);io=new Io(a.getConnection());life=quarantined(io);int initialWaits=io.waits;
  ok(!ready().observe(h.classify(first,a,DetachPolicy.Strict),life,io,io.clock,log)&&io.waits==initialWaits&&io.cancels==1,"production gate Unknown performs zero supplemental native operations");
  io.complete=true;ok(ready().observe(h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence),life,io,io.clock,log)&&io.waits==initialWaits+1,"production gate qualified fixture waits original request");
  ok(life.close(io,log)&&!ready().observe(h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence),life,io,io.clock,log)&&io.closes==1,"closed request never observed again");
  scenario();h=new Harness();a=h.open("A",1);h.io(a);first=h.snapshot(a,DevicePresence.Absent);io=new Io(a.getConnection());life=quarantined(io);initialWaits=io.waits;
  ok(!ready().observe(h.classify(first,a,DetachPolicy.CorroboratedApplicationEvidence),life,io,io.clock,log)&&io.waits==initialWaits+5&&io.cancels==1,"production gate Null remains quarantined after bounded observation");
  System.out.println("PASS USB generations: "+scenarios+" scenarios, "+assertions+" assertions; no Android/USB");
 }
}
