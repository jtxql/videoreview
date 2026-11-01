import io.tabletennisreview.input.*;
import kotlin.Unit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Standalone JVM unit checks: no Android runtime, USB, JUnit or network. */
public class TourBoxInputCheck {
 static int checks;
 static void ok(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static byte[] bytes(int... v){byte[] b=new byte[v.length];for(int i=0;i<v.length;i++)b[i]=(byte)v[i];return b;}
 static byte[] reply(){return HexFormat.of().parseHex("04E05EBBCFBCC7429EA7BF37336CF70B00000002000303010000");}
 static byte[] join(byte[] a,byte[] b){byte[] c=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,c,a.length,b.length);return c;}
 static TourBoxInputDecoder decoder(){TourBoxInputDecoder d=new TourBoxInputDecoder();d.begin(7);return d;}
 static TourBoxInputEvent event(int code){return decoder().feed(7,bytes(code),100,200).getEvents().get(0);}
 static class Manual implements Executor {
  final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
  public void execute(Runnable r){tasks.add(r);}
  void run(){while(!tasks.isEmpty())tasks.remove().run();}
 }
 static TourBoxEventStream stream(int cap,Manual x,List<String> faults){return new TourBoxEventStream(cap,s->{faults.add(s);return Unit.INSTANCE;},x,id->true);}
 public static void main(String[] args)throws Exception {
  TourBoxInputDecoder d=decoder();
  List<TourBoxInputEvent> v=d.feed(7,bytes(0x44,0x04,0x49,0x09),100,200).getEvents();
  ok(v.get(0) instanceof TourBoxInputEvent.KnobClockwise,"CW");ok(v.get(1) instanceof TourBoxInputEvent.KnobCounterClockwise,"CCW");ok(v.get(2) instanceof TourBoxInputEvent.ScrollUp,"up");ok(v.get(3) instanceof TourBoxInputEvent.ScrollDown,"down");
  int[] codes={0x37,0xB7,0x0A,0x8A,0x02,0x82,0x03,0x83,0x22,0xA2,0x23,0xA3,0x00,0x80,0x2A,0xAA};
  TourBoxButton[] buttons={TourBoxButton.KNOB,TourBoxButton.SCROLL,TourBoxButton.TOP,TourBoxButton.SHORT,TourBoxButton.C1,TourBoxButton.C2,TourBoxButton.TALL,TourBoxButton.TOURBOX};
  for(int i=0;i<codes.length;i++){
   TourBoxInputEvent e=event(codes[i]);
   ok(e.getStamp().getRawCode()==codes[i],"unsigned raw");
   if(i%2==0)ok(e instanceof TourBoxInputEvent.ButtonDown&&((TourBoxInputEvent.ButtonDown)e).getButton()==buttons[i/2],"button down");
   else ok(e instanceof TourBoxInputEvent.ButtonUp&&((TourBoxInputEvent.ButtonUp)e).getButton()==buttons[i/2],"button up");
  }
  ok(event(0xFF) instanceof TourBoxInputEvent.UnknownRawCode,"unknown FF");
  ok(event(0xFE) instanceof TourBoxInputEvent.UnknownRawCode,"unmapped byte stays unknown");
  byte[] additional=bytes(0x22,0xA2,0x23,0xA3,0x00,0x80,0x2A,0xAA);
  d=decoder();List<TourBoxInputEvent> additionalBatch=d.feed(7,additional,1,2).getEvents();
  ok(additionalBatch.size()==8,"all extra button edges in one read");
  for(int i=0;i<additional.length;i++)ok(event(additional[i]&255).getClass().isInstance(additionalBatch.get(i))&&additionalBatch.get(i).getStamp().getRawCode()==(additional[i]&255),"extra raw order");
  d=decoder();List<TourBoxInputEvent> splitButtons=new ArrayList<>();
  for(byte code:additional)splitButtons.addAll(d.feed(7,new byte[]{code},1,2).getEvents());
  ok(splitButtons.equals(additionalBatch),"extra buttons split across reads");
  for(int split=0;split<=26;split++){
   d=decoder();d.expectInitializationReply(7,0);
   TourBoxInputDecoder.Batch prefix=d.feed(7,Arrays.copyOfRange(reply(),0,split),1,2);
   TourBoxInputDecoder.Batch tail=d.feed(7,join(Arrays.copyOfRange(reply(),split,26),additional),1,2);
   ok(prefix.getEvents().isEmpty()&&prefix.getResponses()+tail.getResponses()==1,"response zero bytes never become Tall");
   ok(tail.getEvents().size()==8,"new button edges after response");
   for(int i=0;i<8;i++)ok(tail.getEvents().get(i).getStamp().getRawCode()==(additional[i]&255)&&!(tail.getEvents().get(i) instanceof TourBoxInputEvent.UnknownRawCode),"new trailing edge decoded");
  }
  d=decoder();d.expectInitializationReply(7,0);
  TourBoxInputDecoder.Batch waitingTall=d.feed(7,bytes(0x00,0x80,0x22,0xA2),1,2);
  ok(waitingTall.getEvents().stream().allMatch(e->e instanceof TourBoxInputEvent.UnknownRawCode),"known button bytes remain diagnostic while awaiting response");
  d=decoder();v=d.feed(7,bytes(0x44,0x44,0x44),111,222).getEvents();ok(v.size()==3,"no conflation");
  v=new ArrayList<>(v);v.addAll(d.feed(7,bytes(0x04,0x49,0x09),112,223).getEvents());
  for(int i=0;i<v.size();i++){ok(v.get(i).getStamp().getSequence()==i+1,"sequence");ok(v.get(i).getStamp().getSessionId()==7,"session");}
  ok(v.get(0).getStamp().getReceivedNanos()==111&&v.get(0).getStamp().getWallTimeMillis()==222,"host timestamps");
  byte[] response=reply();
  for(int split=0;split<=26;split++){
   d=decoder();d.expectInitializationReply(7,0);
   TourBoxInputDecoder.Batch a=d.feed(7,Arrays.copyOfRange(response,0,split),10,20);
   TourBoxInputDecoder.Batch b=d.feed(7,join(Arrays.copyOfRange(response,split,26),bytes(0x44,0x04,0x37,0xB7)),11,21);
   ok(a.getEvents().isEmpty(),"response prefix emits nothing");ok(a.getResponses()+b.getResponses()==1,"one response");
   ok(b.getEvents().size()==4,"coalesced trailing events");ok(b.getEvents().get(0) instanceof TourBoxInputEvent.KnobClockwise,"trailing starts CW");
  }
  d=decoder();d.expectInitializationReply(7,0);int responses=0;
  for(byte b:response){TourBoxInputDecoder.Batch batch=d.feed(7,new byte[]{b},1,1);ok(batch.getEvents().isEmpty(),"one-byte fragments");responses+=batch.getResponses();}ok(responses==1,"stream response only once");
  d=decoder();d.expectInitializationReply(7,0);
  TourBoxInputDecoder.Batch mixed=d.feed(7,join(bytes(0x37,0xFF),join(response,bytes(0x49))),1,2);
  ok(mixed.getResponses()==1&&mixed.getEvents().size()==3,"unknown prefix and valid response");
  ok(mixed.getEvents().get(0) instanceof TourBoxInputEvent.UnknownRawCode&&mixed.getEvents().get(2) instanceof TourBoxInputEvent.ScrollUp,"quarantine during response");
  d=decoder();d.expectInitializationReply(7,0);d.feed(7,Arrays.copyOfRange(response,0,5),1,2);
  TourBoxInputDecoder.Batch late=d.feed(7,bytes(0x44),3_000_000_001L,2);
  ok(late.getBoundaryFault()&&late.getEvents().size()==6,"timeout flush no lost bytes");
  ok(late.getEvents().stream().allMatch(e->e instanceof TourBoxInputEvent.UnknownRawCode),"timeout cannot produce controls");
  ok(d.feed(7,bytes(0x37),3_000_000_002L,3).getEvents().get(0) instanceof TourBoxInputEvent.UnknownRawCode,"late tail quarantined");
  d.end();ok(d.feed(7,bytes(0x44),5,5).getEvents().isEmpty(),"disconnected decoder");
  d.begin(8);ok(d.feed(7,bytes(0x44),5,5).getEvents().isEmpty(),"old decoder session rejected");

  Manual executor=new Manual();List<String> faults=new ArrayList<>();List<TourBoxInputEvent> delivered=new ArrayList<>();
  TourBoxEventStream stream=stream(16,executor,faults);stream.begin(7);
  AutoCloseable subscription=stream.subscribe(e->{delivered.add(e);return Unit.INSTANCE;});
  for(TourBoxInputEvent e:v)ok(stream.publish(e),"publish batch");ok(delivered.isEmpty(),"not on RX thread");executor.run();ok(delivered.equals(v),"repeat and order retained");
  delivered.clear();stream.publish(event(0x44));stream.end();executor.run();ok(delivered.isEmpty(),"queued stale events cancelled");ok(!stream.withCurrent(event(0x44),()->{throw new AssertionError();}),"async old actions blocked");
  stream.begin(8);ok(!stream.publish(event(0x44)),"new session rejects old");subscription.close();stream.close();
  executor=new Manual();faults=new ArrayList<>();stream=stream(2,executor,faults);stream.begin(7);stream.publish(event(0x44));stream.publish(event(0x44));ok(!stream.publish(event(0x44)),"overflow explicit");ok(faults.size()==1&&!stream.isCurrent(event(0x44)),"overflow disables session");executor.run();stream.close();
  executor=new Manual();faults=new ArrayList<>();stream=stream(4,executor,faults);stream.begin(7);stream.subscribe(e->{throw new IllegalStateException();});stream.publish(event(0x44));executor.run();ok(faults.size()==1&&!stream.isCurrent(event(0x44)),"consumer failure visible");stream.close();

  executor=new Manual();faults=new ArrayList<>();stream=stream(4,executor,faults);stream.begin(7);
  AtomicInteger calls=new AtomicInteger();AutoCloseable sub=stream.subscribe(e->{calls.incrementAndGet();return Unit.INSTANCE;});stream.publish(event(0x44));sub.close();executor.run();ok(calls.get()==0,"unsubscribe cancels queued delivery");stream.close();

  executor=new Manual();faults=new ArrayList<>();AtomicLong currentEpoch=new AtomicLong(7);List<String> finalFaults=faults;
  stream=new TourBoxEventStream(4,s->{finalFaults.add(s);return Unit.INSTANCE;},executor,id->id.longValue()==currentEpoch.get());stream.begin(7);stream.publish(event(0x44));currentEpoch.set(8);ok(!stream.withCurrent(event(0x44),()->{throw new AssertionError();}),"external epoch immediately invalidates");executor.run();stream.close();ok(!stream.isCurrent(event(0x44)),"closed stream invalid");

  ExecutorService worker=Executors.newSingleThreadExecutor();List<String> asyncFaults=Collections.synchronizedList(new ArrayList<>());
  TourBoxEventStream live=new TourBoxEventStream(8,s->{asyncFaults.add(s);return Unit.INSTANCE;},worker,id->true);
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean callbackDone=new AtomicBoolean(),endDone=new AtomicBoolean();
  live.begin(7);live.subscribe(e->{entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException ex){throw new RuntimeException(ex);}callbackDone.set(true);return Unit.INSTANCE;});live.publish(event(0x44));ok(entered.await(3,TimeUnit.SECONDS),"callback started");
  // A slow consumer does not hold the producer queue lock.
  ok(live.publish(event(0x44)),"producer continues while consumer blocked");
  Thread stop=new Thread(()->{live.end();ok(callbackDone.get(),"invalidate waits entered callback");endDone.set(true);});stop.start();release.countDown();stop.join(3000);ok(endDone.get(),"invalidate completed");live.close();ok(worker.awaitTermination(3,TimeUnit.SECONDS),"worker shutdown");
  System.out.println("PASS TourBox input: "+checks+" assertions, no USB or Android runtime");
 }
}
