import io.tabletennisreview.usbdiagnostics.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Scripted device-free lifecycle/ownership tests. No Android or USB objects are used. */
public class UsbAsyncCleanupCheck {
    static int assertions, scenarios;
    static final List<String> logs = new ArrayList<>();
    static final Function1<String, Unit> report = s -> { logs.add(s); return Unit.INSTANCE; };
    static void ok(boolean b, String text) { assertions++; if (!b) throw new AssertionError(text); }
    static void rejects(Runnable r) { boolean caught=false; try { r.run(); } catch (IllegalStateException e) {caught=true;} ok(caught,"must reject"); }
    static class Clock implements CleanupClock {
        long time; public long millis(){ return time; } public void pause(long n){time+=n;}
    }
    static class Io implements RequestIo {
        final Clock clock; final Queue<WaitResult> results = new ArrayDeque<>();
        int waits, cancels, closes; boolean cancelResult=true, throwClose, throwWait, throwCancel;
        long waitCost; Runnable duringWait;
        Io(Clock c, WaitResult... rs) {clock=c; results.addAll(Arrays.asList(rs));}
        public boolean cancel(){ cancels++; if(throwCancel)throw new IllegalStateException("cancel"); return cancelResult; }
        public WaitResult waitFor(long n) { waits++; ok(n>0&&n<=200,"finite wait"); clock.time+=Math.min(n,waitCost); if(duringWait!=null)duringWait.run(); if(throwWait)throw new IllegalStateException("wait"); return results.isEmpty()?WaitResult.Null:results.remove(); }
        public void closeRequest(){ closes++; if(throwClose)throw new IllegalStateException("close"); }
    }
    static UsbAsyncCleanupState pending(){ UsbAsyncCleanupState s=new UsbAsyncCleanupState(); s.queued(); return s; }
    static boolean drain(UsbAsyncCleanupState s,Io io,boolean detach,boolean extra){return s.drain(io,io.clock,detach,extra,report);}
    static void scenario(){scenarios++; logs.clear();}
    public static void main(String[] args)throws Exception {
        scenario(); // 1 normal stop; close only once, never cancel/wait after close
        Clock c=new Clock(); Io io=new Io(c,WaitResult.Original); UsbAsyncCleanupState s=pending();
        ok(drain(s,io,false,false),"normal retrieved"); ok(s.close(io,report),"normal close"); ok(s.close(io,report),"idempotent");
        ok(drain(s,io,true,true),"closed no I/O"); ok(io.cancels==1&&io.waits==1&&io.closes==1,"single calls");
        scenario(); // 2 I/O failure before broadcast; supplemental completion only after handoff
        c=new Clock(); io=new Io(c); s=pending(); s.stopped(UsbStopReason.WaitNull);
        ok(!drain(s,io,false,false),"first round unknown"); ok(!drain(s,io,false,true),"no evidence no supplement");
        s.readerFinished(); s.takeOver(true,true); io.results.add(WaitResult.Original);
        ok(drain(s,io,true,true),"late detach completion"); ok(s.close(io,report),"late close"); ok(io.cancels==1,"no recancel");
        scenario(); // 3 broadcast before I/O failure; reason independent of last drain result
        c=new Clock(); io=new Io(c); s=pending(); s.stopped(UsbStopReason.PhysicalDetachConfirmed); drain(s,io,false,false);
        ok(s.getReason()==UsbStopReason.PhysicalDetachConfirmed&&s.getLastWaitReason()==UsbStopReason.WaitNull,"separate axes");
        scenario(); // 4 null never completion
        c=new Clock(); io=new Io(c); s=pending(); ok(!drain(s,io,false,false),"null unknown"); ok(!s.close(io,report)&&io.closes==0,"pending not freed");
        scenario(); // 5 foreign completion never completion of our request
        c=new Clock(); io=new Io(c,WaitResult.OtherRequest,WaitResult.OtherRequest,WaitResult.OtherRequest,WaitResult.OtherRequest,WaitResult.OtherRequest); s=pending(); drain(s,io,false,false);
        ok(s.getState()==AsyncCleanupState.Quarantined&&s.getLastWaitReason()==UsbStopReason.WaitOtherRequest,"foreign distinct");
        scenario(); // 6 cancel=false then original retrieved
        c=new Clock(); io=new Io(c,WaitResult.Original); io.cancelResult=false; s=pending(); ok(drain(s,io,false,false)&&s.close(io,report),"false still completes");
        scenario(); // 7 cancel=true but completion never retrieved
        c=new Clock(); io=new Io(c); s=pending(); ok(!drain(s,io,false,false)&&!s.close(io,report),"true not proof");
        scenario(); // 8 bounded immediate-null rounds, no busyloop, supplemental once only
        s.readerFinished(); s.takeOver(true,true); ok(!drain(s,io,true,true),"still unknown"); int count=io.waits; long time=c.time;
        ok(count==10&&time==200,"two 5 attempt rounds with backoff"); ok(!drain(s,io,true,true)&&io.waits==count,"supplement once");
        ok(!drain(s,io,false,false)&&io.waits==count,"initial once");
        scenario(); // 9 stop/completion concurrent on another thread; wait itself remains owned
        c=new Clock(); io=new Io(c,WaitResult.Original); AtomicBoolean stop=new AtomicBoolean(); io.duringWait=()->{Thread t=new Thread(()->stop.set(true));t.start();try{t.join();}catch(Exception e){throw new RuntimeException(e);}};
        s=pending(); ok(drain(s,io,false,false)&&stop.get()&&s.close(io,report),"raced completion preserved");
        scenario(); // 10 duplicate queue rejected, completed request may queue only when actually retrieved
        s=new UsbAsyncCleanupState(); s.queued(); final UsbAsyncCleanupState duplicate=s; rejects(duplicate::queued); s.completed(); s.queued(); s.completed();
        io=new Io(new Clock()); ok(s.close(io,report)&&s.close(io,report)&&io.closes==1,"repeat close"); rejects(s::queued);
        scenario(); // 11 old session / address reuse / still enumerated must not qualify
        UsbDetachIdentity key=new UsbDetachIdentity(12,"address",7,false);
        ok(key.matches(12,"address",7,false),"exact absent"); ok(!key.matches(11,"address",7,false),"old token"); ok(!key.matches(12,"address",7,true),"present");
        ok(!new UsbDetachIdentity(13,"address",7,true).matches(13,"address",7,false),"reused address ambiguous");
        scenario(); // 12 request.close failure => quarantine; no subsequent cancel/wait/free
        s=new UsbAsyncCleanupState(); io=new Io(new Clock()); io.throwClose=true;
        ok(!s.close(io,report)&&s.getState()==AsyncCleanupState.Quarantined,"close exception"); ok(!s.close(io,report)&&!drain(s,io,true,true)&&io.closes==1&&io.waits==0,"no close retry");
        AtomicInteger releases=new AtomicInteger(), closes=new AtomicInteger(); ConnectionCloseOnce closer=new ConnectionCloseOnce();
        ConnectionCleanupIo ci=new ConnectionCleanupIo(){ public void releaseInterfaces(Function1<? super String,Unit> r){releases.incrementAndGet();throw new IllegalStateException("release");} public void closeConnection(){closes.incrementAndGet();throw new IllegalStateException("connection");}};
        ok(!closer.finish(ci,report)&&!closer.finish(ci,report)&&releases.get()==1&&closes.get()==1,"cleanup exceptions single attempt");
        scenario(); // 13 reader must EXIT before coordinator gets ownership; no two wait owners
        AtomicReference<UsbAsyncCleanupState> ref=new AtomicReference<>(); CountDownLatch created=new CountDownLatch(1), finish=new CountDownLatch(1);
        Thread reader=new Thread(()->{UsbAsyncCleanupState st=pending();ref.set(st);created.countDown();try{finish.await();}catch(Exception e){throw new RuntimeException(e);}st.readerFinished();});
        reader.start(); created.await(); s=ref.get(); final UsbAsyncCleanupState handoff=s;
        rejects(()->handoff.takeOver(false,true)); rejects(()->handoff.stopped(UsbStopReason.UserStop)); finish.countDown(); reader.join();
        rejects(()->handoff.takeOver(true,false)); s.takeOver(true,true); rejects(()->handoff.takeOver(true,true));
        io=new Io(new Clock(),WaitResult.Original); ok(drain(s,io,true,true)&&s.close(io,report),"coordinator after join");
        scenario(); // 14 monotonic budget and timeout; all waits receive remaining finite budget
        c=new Clock(); io=new Io(c,WaitResult.Timeout,WaitResult.Timeout,WaitResult.Timeout,WaitResult.Timeout,WaitResult.Timeout); io.waitCost=200;s=pending();drain(s,io,false,false);
        ok(io.waits==5&&c.time==1000&&s.getLastWaitReason()==UsbStopReason.Timeout,"time budget bounded");
        scenario(); // 15 process registry prevents overwrite by multiple Managers
        ExclusiveUsbLease<Object> registry=new ExclusiveUsbLease<>(); Long token=registry.acquire();Object held=new Object();registry.retain(token,held);
        ok(registry.acquire()==null&&registry.getRetained()==held,"cannot bypass quarantine"); rejects(()->registry.retain(token,new Object())); rejects(()->registry.release(token+1));
        registry.release(token); Long next=registry.acquire();ok(next>token&&registry.getRetained()==null&&!registry.owns(token),"new owner distinct");
        scenario(); // 16 cancel/wait exceptions still bounded; no pending free
        c=new Clock();io=new Io(c);io.throwCancel=true;io.throwWait=true;s=pending();ok(!drain(s,io,false,false)&&io.waits==5&&io.cancels==1&&c.time==100,"exception bounded"); ok(!s.close(io,report),"no forcefree");
        scenario(); // 17 unqueued path and successful connection close are idempotent
        s=new UsbAsyncCleanupState();io=new Io(new Clock());ok(s.close(io,report)&&io.waits==0&&io.cancels==0,"unqueued close");
        AtomicInteger cc=new AtomicInteger();ConnectionCloseOnce done=new ConnectionCloseOnce(); ConnectionCleanupIo success=new ConnectionCleanupIo(){public void releaseInterfaces(Function1<? super String,Unit> r){} public void closeConnection(){cc.incrementAndGet();}};
        ok(done.finish(success,report)&&done.finish(success,report)&&cc.get()==1,"connection close once");
        scenario(); // 18 second thread cannot wait/close while original owner exists
        final UsbAsyncCleanupState owned=pending(); final Io ownerIo=new Io(new Clock(),WaitResult.Original);
        AtomicReference<Throwable> failure=new AtomicReference<>(); Thread intruder=new Thread(()->{try{drain(owned,ownerIo,false,false);}catch(Throwable e){failure.set(e);}});
        intruder.start();intruder.join();ok(failure.get() instanceof IllegalStateException&&ownerIo.waits==0&&ownerIo.cancels==0,"no concurrent wait owner");
        ok(drain(owned,ownerIo,false,false)&&owned.close(ownerIo,report),"original owner still cleans");
        scenario(); // 19 exact close ordering and no connection close when pending unresolved
        List<String> order=new ArrayList<>();Clock oc=new Clock();Io ordered=new Io(oc,WaitResult.Original){public void closeRequest(){super.closeRequest();order.add("request");}};
        UsbAsyncCleanupState orderedState=pending();ConnectionCloseOnce orderedClose=new ConnectionCloseOnce();
        ConnectionCleanupIo orderedConnection=new ConnectionCleanupIo(){public void releaseInterfaces(Function1<? super String,Unit> r){order.add("release");}public void closeConnection(){order.add("connection");}};
        ok(drain(orderedState,ordered,false,false),"retrieved before closing");orderedState.readerFinished();orderedState.takeOver(true,true);
        ok(orderedState.close(ordered,report)&&orderedClose.finish(orderedConnection,report),"joined owner closes");
        ok(order.equals(Arrays.asList("request","release","connection")),"request then release then connection");
        scenario(); // 20 explicit false queue vs exception during native queue
        s=pending();s.queueRejected();io=new Io(new Clock());ok(s.close(io,report)&&io.cancels==0,"queue false is unqueued");
        s=pending();s.stopped(UsbStopReason.IoException);io=new Io(new Clock());ok(!s.close(io,report),"queue exception conservatively pending");
        ok(!drain(s,io,false,false)&&s.getState()==AsyncCleanupState.Quarantined&&io.closes==0,"unknown native submission retained");
        System.out.println("UsbAsyncCleanupCheck: "+scenarios+" scenarios, "+assertions+" assertions passed");
    }
}
