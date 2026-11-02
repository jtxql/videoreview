import io.tabletennisreview.usbdiagnostics.*;
import java.util.*;
public final class AutoInitCheck {
    static int count;
    static void ok(boolean v){count++;if(!v)throw new AssertionError("auto init "+count);}
    static AutoInitSequence prefix() {
        AutoInitSequence s=new AutoInitSequence(9);
        ok(s.next(9,0,false)==AutoInitStep.A1);ok(s.next(9,1,false)==null);
        s.complete(9,AutoInitStep.A1,7,2);
        ok(s.next(9,3,false)==AutoInitStep.A0);s.complete(9,AutoInitStep.A0,0,4);
        ok(s.next(9,5,false)==AutoInitStep.Init8);s.complete(9,AutoInitStep.Init8,8,6);
        return s;
    }
    public static void main(String[] args) {
        AutoInitSequence s=prefix();ok(s.getStep()==AutoInitStep.Reply26);
        for(int i=0;i<20;i++)ok(s.next(9,7+i,false)==null);
        ok(s.next(9,50,true)==AutoInitStep.Config94);
        for(int i=0;i<20;i++)ok(s.next(9,51+i,true)==null);
        s.complete(9,AutoInitStep.Config94,94,100);ok(s.getStep()==AutoInitStep.Ready);
        for(int i=0;i<20;i++)ok(s.next(9,10000+i,true)==null);
        s.complete(9,AutoInitStep.Config94,94,10001);ok(s.getStep()==AutoInitStep.Ready);
        s=prefix();ok(s.next(9,3007,false)==null);ok(s.getStep()==AutoInitStep.Failed);ok(s.next(9,3010,true)==null);
        s=new AutoInitSequence(9);ok(s.next(8,0,true)==null);s.complete(8,AutoInitStep.A1,7,0);ok(s.getStep()==AutoInitStep.A1);
        ok(s.next(9,0,false)==AutoInitStep.A1);s.complete(9,AutoInitStep.A0,0,1);ok(s.getStep()==AutoInitStep.Failed);
        for(int result:new int[]{-1,0,6,8}){s=new AutoInitSequence(9);s.next(9,0,false);s.complete(9,AutoInitStep.A1,result,1);ok(s.getStep()==AutoInitStep.Failed);ok(s.next(9,2,true)==null);}
        s=new AutoInitSequence(9);s.next(9,0,false);ok(s.next(9,5001,true)==null);ok(s.getStep()==AutoInitStep.Failed);
        for(int result:new int[]{-1,0,93}){s=prefix();s.next(9,100,true);s.complete(9,AutoInitStep.Config94,result,101);ok(s.getStep()==AutoInitStep.Failed);ok(s.next(9,102,true)==null);}
        s=prefix();s.fail("detach/log failure");ok(s.next(9,100,true)==null);ok(s.getStep()==AutoInitStep.Failed);
        s=new AutoInitSequence(9);s.next(9,0,false);s.complete(9,AutoInitStep.A1,7,5001);ok(s.getStep()==AutoInitStep.Failed);
        // Combine the ordered plan with the existing real reply/once-per-session gate.
        Config94Experiment gate=new Config94Experiment();gate.record(CdcAction.A1,7);gate.record(CdcAction.A0,0);gate.begin(9,0);gate.sent(8);
        byte[] reply=hex("04 E0 5E BB CF BC C7 42 9E A7 BF 37 33 6C F7 0B 00 00 00 02 00 03 03 01 00 00");
        s=prefix();ok(!gate.observe(9,Arrays.copyOfRange(reply,0,13),10));ok(s.next(9,10,gate.ready(9))==null);
        ok(gate.observe(9,Arrays.copyOfRange(reply,13,26),11));ok(s.next(9,11,gate.ready(9))==AutoInitStep.Config94);
        ok(gate.reserve(9,true));ok(!gate.reserve(9,true));
        System.out.println("AutoInitCheck: "+count+" assertions passed; ordered/no retry/stale/timeout/split reply/single reserve (no USB)");
    }
    static byte[] hex(String text){String[] split=text.split(" ");byte[] bytes=new byte[split.length];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(split[i],16);return bytes;}
}
