import io.tabletennisreview.playback.*;
import io.tabletennisreview.player.*;
public final class RotationSensitivityCheck {
 static int n;static void ok(boolean v){n++;if(!v)throw new AssertionError("rotation check "+n);}
 static RotationPlan plan(RotationProcessor p,MappingInput i,MappingAction a,SensitivityLevel s,long t){return p.plan(i,a,s,t);}
 public static void main(String[] args){
  for(SensitivityLevel level:SensitivityLevel.values()){
   for(MappingInput input:new MappingInput[]{MappingInput.KnobCW,MappingInput.KnobCCW,MappingInput.ScrollUp,MappingInput.ScrollDown}){
    for(MappingAction action:new MappingAction[]{MappingAction.FrameForward,MappingAction.FrameBackward}){
     RotationProcessor p=new RotationProcessor(); int count=0;
     for(int i=0;i<16;i++){RotationPlan r=plan(p,input,action,level,i);count+=r.getRepeats();ok(r.getCommandOverride()==null);ok(r.getRepeats()>=0&&r.getRepeats()<=4);}
     ok(count==16*level.getMultiplier());
    }
    for(MappingAction action:new MappingAction[]{MappingAction.SeekForward,MappingAction.SeekBackward}){
     RotationPlan r=plan(new RotationProcessor(),input,action,level,0);
     ok(r.getRepeats()==1);ok(((PlayerCommand.SeekRelative)r.getCommandOverride()).getSeconds()==(action==MappingAction.SeekForward?5:-5)*level.getMultiplier());
    }
    for(MappingAction action:MappingAction.values()){
     if(action==MappingAction.FrameForward||action==MappingAction.FrameBackward||action==MappingAction.SeekForward||action==MappingAction.SeekBackward)continue;
     RotationPlan r=plan(new RotationProcessor(),input,action,level,0);ok(r.getRepeats()==1&&r.getCommandOverride()==null);
    }
   }
   for(MappingInput input:new MappingInput[]{MappingInput.Knob,MappingInput.Scroll,MappingInput.Top,MappingInput.C1,MappingInput.C2,MappingInput.Short,MappingInput.Tall,MappingInput.TourBox})
    for(MappingAction action:MappingAction.values()){RotationPlan r=plan(new RotationProcessor(),input,action,level,0);ok(r.getRepeats()==1&&r.getCommandOverride()==null);}
  }
  RotationProcessor p=new RotationProcessor();
  ok(plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,0).getRepeats()==0);
  ok(plan(p,MappingInput.ScrollUp,MappingAction.FrameForward,SensitivityLevel.Half,0).getRepeats()==0);
  ok(plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,1).getRepeats()==1);
  ok(plan(p,MappingInput.ScrollUp,MappingAction.FrameForward,SensitivityLevel.Half,1).getRepeats()==1);
  p.reset();plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,0);
  ok(plan(p,MappingInput.KnobCCW,MappingAction.FrameBackward,SensitivityLevel.Half,1).getRepeats()==0);
  ok(plan(p,MappingInput.KnobCCW,MappingAction.FrameBackward,SensitivityLevel.Half,2).getRepeats()==1);
  p.reset();plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,0);
  ok(plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,1_000_000_000L).getRepeats()==1);
  p.reset();plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,0);
  ok(plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,1_000_000_001L).getRepeats()==0);
  p.reset();plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,10);
  ok(plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,9).getRepeats()==0);
  p.reset();plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,0);
  plan(p,MappingInput.KnobCW,MappingAction.Screenshot,SensitivityLevel.Half,1);
  ok(plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,2).getRepeats()==0);
  p.reset();ok(plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Half,3).getRepeats()==0);
  p.reset();int count=0;for(int i=0;i<10000;i++)count+=plan(p,MappingInput.KnobCW,MappingAction.FrameForward,SensitivityLevel.Quarter,i).getRepeats();ok(count==2500);
  System.out.println("RotationSensitivityCheck: "+n+" assertions passed (no USB)");
 }
}
