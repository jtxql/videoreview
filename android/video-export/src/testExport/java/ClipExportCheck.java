import io.tabletennisreview.export.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ClipExportCheck {
    static int checks;
    static void ok(boolean value){checks++;if(!value)throw new AssertionError("export check "+checks);}
    interface Action {void run() throws Exception;}
    static void rejects(Class<? extends Throwable> type,Action action){boolean found=false;try{action.run();}catch(Throwable e){if(!type.isInstance(e))throw new AssertionError(e);found=true;}ok(found);}
    static ClipExportRequest request(double a,double b,double speed){return ClipExportRequest.Companion.fromSeconds(1,"content://test/source",a,b,speed,true);}
    static class In extends InputStream {
        final byte[] data;int offset;boolean closed,readFail,closeFail;
        In(byte[] data){this.data=data;}
        public int read()throws IOException{if(readFail)throw new IOException("read");return offset<data.length?data[offset++]&255:-1;}
        public int read(byte[] b,int start,int length)throws IOException{if(readFail)throw new IOException("read");if(offset>=data.length)return -1;int n=Math.min(length,data.length-offset);System.arraycopy(data,offset,b,start,n);offset+=n;return n;}
        public void close()throws IOException{closed=true;if(closeFail)throw new IOException("input close");}
    }
    static class Out extends OutputStream {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean closed,writeFail,closeFail,flushFail;
        public void write(int b)throws IOException{if(writeFail)throw new IOException("write");bytes.write(b);}
        public void write(byte[] b,int o,int n)throws IOException{if(writeFail)throw new IOException("write");bytes.write(b,o,n);}
        public void flush()throws IOException{if(flushFail)throw new IOException("flush");}
        public void close()throws IOException{closed=true;if(closeFail)throw new IOException("output close");}
    }
    public static void main(String[] args)throws Exception {
        for(double speed:new double[]{0.1,0.25,0.5,0.75,1}){ClipExportRequest r=request(2,12,speed);ok(r.getStartUs()==2_000_000&&r.getEndUs()==12_000_000);ok(r.getExpectedDurationUs()==Math.round(10_000_000/speed));}
        ok(request(0.033333333,1,0.25).getStartUs()==33333);ok(!new ClipExportRequest(1,"content://test/source",0,1,1,false).getKeepAudio());
        rejects(IllegalArgumentException.class,()->request(-1,2,1));rejects(IllegalArgumentException.class,()->request(2,2,1));rejects(IllegalArgumentException.class,()->request(3,2,1));
        for(double bad:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,0.3})rejects(IllegalArgumentException.class,()->request(0,1,bad));
        rejects(IllegalArgumentException.class,()->request(Double.NaN,2,1));rejects(IllegalArgumentException.class,()->request(0,Double.POSITIVE_INFINITY,1));
        rejects(IllegalArgumentException.class,()->new ClipExportRequest(0,"content://test/source",0,10,1,true));
        rejects(IllegalArgumentException.class,()->new ClipExportRequest(1,"file:///source",0,10,1,true));
        rejects(IllegalArgumentException.class,()->request(0,0.00000001,1));
        ok(request(0,10,0.25).matchesDuration(40_000_000,30.0));
        ok(!request(0,10,0.25).matchesDuration(10_000_000,30.0));
        ok(!request(0,10,0.1).matchesDuration(10_000_000,null));ok(!request(0,10,1).matchesDuration(0,30.0));
        ClipExportLease lease=new ClipExportLease();ok(lease.acquire(1));ok(!lease.acquire(2));ok(!lease.release(2));ok(lease.release(1));
        ok(lease.acquire(2));ok(!lease.release(1));ok(!lease.acquire(3));ok(lease.release(2));ok(!lease.acquire(0));ok(!lease.release(0));
        ClipExportGate gate=new ClipExportGate();long id=gate.begin();ok(gate.begin()==null);ok(!gate.move(id+1,ClipExportPhase.Exporting,"old",null));
        ok(!gate.move(id,ClipExportPhase.Saved,"wrong transition",null));ok(gate.move(id,ClipExportPhase.Exporting,"encoding",null));
        ok(gate.progress(id,100));ok(gate.getState().getProgress()==99);ok(gate.progress(id,1));ok(gate.getState().getProgress()==99);
        ok(gate.move(id,ClipExportPhase.Ready,"validated",40_000_000L));ok(!gate.progress(id,50));ok(gate.begin()==null);
        ok(gate.move(id,ClipExportPhase.Saving,"saving",null));ok(gate.move(id,ClipExportPhase.Ready,"close failed; retry",null));
        ok(gate.move(id,ClipExportPhase.Saving,"retry",null));ok(gate.move(id,ClipExportPhase.Saved,"closed successfully",null));
        long next=gate.begin();ok(next!=id);ok(!gate.move(id,ClipExportPhase.Ready,"late",null));ok(gate.move(next,ClipExportPhase.Cancelled,"background",null));
        ok(!gate.move(next,ClipExportPhase.Ready,"late completion",null));gate.close();ok(gate.begin()==null);
        ClipExportGate other=new ClipExportGate();long third=other.begin();ok(third!=id&&third!=next);ok(other.move(third,ClipExportPhase.Failed,"encoder failure",null));ok(other.begin()!=null);
        ClipExportGate concurrent=new ClipExportGate();AtomicInteger winners=new AtomicInteger();CountDownLatch start=new CountDownLatch(1);List<Thread> threads=new ArrayList<>();
        for(int i=0;i<16;i++){Thread t=new Thread(()->{try{start.await();if(concurrent.begin()!=null)winners.incrementAndGet();}catch(InterruptedException e){throw new AssertionError(e);}});threads.add(t);t.start();}start.countDown();for(Thread t:threads)t.join();ok(winners.get()==1);
        for(int i=0;i<100;i++){
            ClipSaveCommit commit=new ClipSaveCommit();AtomicBoolean saved=new AtomicBoolean(),cancelled=new AtomicBoolean();CountDownLatch race=new CountDownLatch(1);
            Thread a=new Thread(()->{try{race.await();saved.set(commit.commit());}catch(Exception e){throw new AssertionError(e);}});
            Thread b=new Thread(()->{try{race.await();cancelled.set(commit.cancel());}catch(Exception e){throw new AssertionError(e);}});
            a.start();b.start();race.countDown();a.join();b.join();ok(saved.get()!=cancelled.get());
        }
        byte[] payload=new byte[200_000];for(int i=0;i<payload.length;i++)payload[i]=(byte)i;
        In input=new In(payload);Out output=new Out();ClipSaveCommit commit=new ClipSaveCommit();
        ok(ClipStreamCopy.INSTANCE.copy(input,output,payload.length,()->false,commit)==payload.length);ok(input.closed&&output.closed);ok(Arrays.equals(output.bytes.toByteArray(),payload));ok(!commit.cancel());
        for(int mode=0;mode<5;mode++){
            In in=new In(payload);Out out=new Out();ClipSaveCommit c=new ClipSaveCommit();
            in.readFail=mode==0;out.writeFail=mode==1;out.flushFail=mode==2;out.closeFail=mode==3;in.closeFail=mode==4;
            rejects(IOException.class,()->ClipStreamCopy.INSTANCE.copy(in,out,payload.length,()->false,c));ok(in.closed&&out.closed);ok(c.cancel());
        }
        In cancelledIn=new In(payload);Out cancelledOut=new Out();ClipSaveCommit c=new ClipSaveCommit();AtomicInteger calls=new AtomicInteger();
        rejects(CancellationException.class,()->ClipStreamCopy.INSTANCE.copy(cancelledIn,cancelledOut,payload.length,()->calls.incrementAndGet()>2,c));ok(cancelledIn.closed&&cancelledOut.closed);ok(cancelledOut.bytes.size()<payload.length);ok(c.cancel());
        In mismatch=new In(new byte[]{1,2});Out mismatchOut=new Out();ClipSaveCommit mismatchCommit=new ClipSaveCommit();
        rejects(IllegalStateException.class,()->ClipStreamCopy.INSTANCE.copy(mismatch,mismatchOut,3,()->false,mismatchCommit));ok(mismatch.closed&&mismatchOut.closed);ok(mismatchCommit.cancel());
        In cancelledFirst=new In(payload);Out outFirst=new Out();ClipSaveCommit first=new ClipSaveCommit();first.cancel();
        rejects(CancellationException.class,()->ClipStreamCopy.INSTANCE.copy(cancelledFirst,outFirst,payload.length,()->false,first));ok(cancelledFirst.closed&&outFirst.closed);
        System.out.println("ClipExportCheck: "+checks+" assertions passed; request/state/stale/cancel-save races/stream errors; no Android/native/video encoding");
    }
}
