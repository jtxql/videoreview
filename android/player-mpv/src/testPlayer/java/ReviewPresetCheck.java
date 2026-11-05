import io.tabletennisreview.player.*;
import java.util.*;
public final class ReviewPresetCheck {
 static int n;static void ok(boolean v){n++;if(!v)throw new AssertionError("preset check "+n);}
 static void rejects(Runnable f){try{f.run();throw new AssertionError("expected invalid preset");}catch(IllegalArgumentException e){n++;}}
 static NativeRequest.Property prop(List<NativeRequest> r,int i){return (NativeRequest.Property)r.get(i);}
 public static void main(String[] args){
  ok(ReviewSnapshotPlan.INSTANCE.reads(false).equals(Arrays.asList(ReviewSnapshotRead.Speed,ReviewSnapshotRead.Duration)));
  ok(ReviewSnapshotPlan.INSTANCE.reads(true).equals(Arrays.asList(ReviewSnapshotRead.Pause,ReviewSnapshotRead.Speed,ReviewSnapshotRead.Duration)));
  for(PlayerPhase phase:PlayerPhase.values()){
   ok(ReviewSnapshotPlan.INSTANCE.phaseAfter(phase,false)==phase);
   ok(ReviewSnapshotPlan.INSTANCE.phaseAfter(phase,true)==PlayerPhase.Paused);
  }
  for(double speed:new double[]{.1,.25,.5,.75,1}){
   ReviewPreset preset=new ReviewPreset(2,4,speed);LoopPoints loop=preset.validated(10);ok(loop.getActive());ok(loop.getA()==2&&loop.getB()==4);
   List<NativeRequest> r=ReviewRestorePlan.INSTANCE.requests(preset,10);ok(r.size()==9);
   String[] names={"pause","ab-loop-b","ab-loop-a","speed","ab-loop-a","ab-loop-count","ab-loop-b"};
   String[] values={"yes","no","no",Double.toString(speed),"2.0","inf","4.0"};
   for(int i=0;i<7;i++){ok(prop(r,i).getName().equals(names[i]));ok(prop(r,i).getValue().equals(values[i]));}
   ok(((NativeRequest.Command)r.get(7)).getArguments().equals(Arrays.asList("seek","2.0","absolute+exact")));
   ok(prop(r,8).getName().equals("pause")&&prop(r,8).getValue().equals("no"));
  }
  for(double[] v:new double[][]{{-1,2,1},{2,2,1},{4,2,1},{Double.NaN,4,1},{0,Double.POSITIVE_INFINITY,1},{0,2,2},{0,2,Double.NaN}})
   rejects(()->new ReviewPreset(v[0],v[1],v[2]).validated(10));
  for(double duration:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,3})rejects(()->new ReviewPreset(2,4,1).validated(duration));
  ok(new ReviewPreset(0,4,.1).validated(4).getActive());
  System.out.println("ReviewPresetCheck: "+n+" assertions passed (no native)");
 }
}
