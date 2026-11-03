import io.tabletennisreview.playback.*;
import io.tabletennisreview.player.*;
import io.tabletennisreview.input.*;
public final class TourBoxMappingCheck {
 static int n; static void ok(boolean b){n++;if(!b)throw new AssertionError("config check "+n);}
 static final class Port implements MappingPreferencesPort {
  String value; boolean fail,throwsIo,readFail; int writes;
  public String read(){if(readFail)throw new IllegalStateException("read failed");return value;}
  public boolean write(String s){writes++; value=s;if(throwsIo)throw new IllegalStateException("disk");return !fail;}
 }
 static InputStamp stamp(){return new InputStamp(1,1,1,1,0);}
 static void sensitivityChecks(){
  MappingConfig base=MappingConfig.defaults().with(MappingInput.Top,MappingAction.Screenshot);
  for(RotaryControl c:RotaryControl.values())ok(base.sensitivity(c)==SensitivityLevel.Normal);
  for(SensitivityLevel k:SensitivityLevel.values())for(SensitivityLevel s:SensitivityLevel.values()){
   MappingConfig config=base.withSensitivity(RotaryControl.Knob,k).withSensitivity(RotaryControl.Scroll,s);
   ok(config.sensitivity(RotaryControl.Knob)==k&&config.sensitivity(RotaryControl.Scroll)==s);
   ok(config.action(MappingInput.Top)==MappingAction.Screenshot);
   ok(config.with(MappingInput.C1,MappingAction.Play).sensitivity(RotaryControl.Knob)==k);
   ok(MappingConfig.decode(config.encode()).getConfig().equals(config));
   ok(MappingConfig.decode(config.encode()).getNotice()==null);
  }
  String legacy=base.encode().lines().filter(x->!x.contains("-sensitivity=")).collect(java.util.stream.Collectors.joining("\n"));
  ok(MappingConfig.decode(legacy).getConfig().equals(base));ok(MappingConfig.decode(legacy).getNotice()==null);
  for(String bad:new String[]{"knob-sensitivity=bogus","knob-sensitivity=half\nknob-sensitivity=double"}){
   MappingLoad loaded=MappingConfig.decode(legacy+"\nscroll-sensitivity=quadruple\n"+bad);
   ok(loaded.getNotice()!=null);ok(loaded.getConfig().sensitivity(RotaryControl.Knob)==SensitivityLevel.Normal);
   ok(loaded.getConfig().sensitivity(RotaryControl.Scroll)==SensitivityLevel.Quadruple);
   ok(loaded.getConfig().action(MappingInput.Top)==MappingAction.Screenshot);
  }
  MappingDraft d=new MappingDraft(base);d.setSensitivity(RotaryControl.Knob,SensitivityLevel.Half);
  ok(d.getDirty());ok(base.sensitivity(RotaryControl.Knob)==SensitivityLevel.Normal);
  MappingConfig changed=d.snapshot();d.accept(changed);ok(!d.getDirty());d.resetDefaults();
  ok(d.snapshot().sensitivity(RotaryControl.Knob)==SensitivityLevel.Normal);ok(d.getDirty());
  Port port=new Port();MappingSaveCoordinator store=new MappingSaveCoordinator(port);
  port.fail=true;ok(!store.save(0,changed).getSuccess());ok(store.snapshot().getConfig().equals(MappingConfig.defaults()));
  port.fail=false;ok(store.save(0,changed).getSuccess());ok(new MappingSaveCoordinator(port).snapshot().getConfig().equals(changed));
 }
 public static void main(String[] args){
  sensitivityChecks();
  MappingConfig defaults=MappingConfig.defaults();ok(MappingInput.values().length==12);
  MappingAction[] want={MappingAction.FrameForward,MappingAction.FrameBackward,MappingAction.SeekForward,MappingAction.SeekBackward,
   MappingAction.TogglePause,MappingAction.CycleSpeed,MappingAction.ToggleLoop,MappingAction.MarkA,MappingAction.MarkB,MappingAction.None,MappingAction.None,MappingAction.None};
  int i=0;for(MappingInput input:MappingInput.values()){ok(defaults.action(input)==want[i++]);
   for(MappingAction action:MappingAction.values()){MappingConfig c=defaults.with(input,action);ok(c.action(input)==action);ok(MappingConfig.decode(c.encode()).getConfig().equals(c));}
  }
  ok(MappingInput.fromEvent(new TourBoxInputEvent.KnobClockwise(stamp()))==MappingInput.KnobCW);
  ok(MappingInput.fromEvent(new TourBoxInputEvent.KnobCounterClockwise(stamp()))==MappingInput.KnobCCW);
  ok(MappingInput.fromEvent(new TourBoxInputEvent.ScrollUp(stamp()))==MappingInput.ScrollUp);
  ok(MappingInput.fromEvent(new TourBoxInputEvent.ScrollDown(stamp()))==MappingInput.ScrollDown);
  for(TourBoxButton button:TourBoxButton.values()){
   ok(MappingInput.fromEvent(new TourBoxInputEvent.ButtonDown(button,stamp()))!=null);
   ok(MappingInput.fromEvent(new TourBoxInputEvent.ButtonUp(button,stamp()))==null);
  }
  ok(MappingInput.fromEvent(new TourBoxInputEvent.UnknownRawCode(stamp()))==null);
  ok(MappingAction.Screenshot.command()==null&&!MappingAction.Screenshot.getRepeatable());
  ok(MappingAction.FrameForward.command()==PlayerCommand.StepForward.INSTANCE&&MappingAction.FrameForward.getRepeatable());
  ok(MappingAction.MarkA.command()==PlayerCommand.MarkA.INSTANCE&&!MappingAction.TogglePause.getRepeatable());
  ok(((PlayerCommand.SetSpeed)MappingAction.Speed025.command()).getSpeed()==.25);
  ok(((PlayerCommand.ShiftSpeed)MappingAction.SpeedDown.command()).getSteps()==-1);
  ok(((PlayerCommand.SeekRelative)MappingAction.SeekBackward.command()).getSeconds()==-5);
  MappingDraft d=new MappingDraft(defaults);d.set(MappingInput.Top,MappingAction.Screenshot);ok(d.getDirty());ok(defaults.action(MappingInput.Top)==MappingAction.ToggleLoop);
  d.resetDefaults();ok(!d.getDirty());d.set(MappingInput.Top,MappingAction.None);ok(d.snapshot().action(MappingInput.Top)==MappingAction.None);
  ok(MappingConfig.decode(null).getConfig().equals(defaults));ok(MappingConfig.decode("version=99").getConfig().equals(defaults));
  String text=defaults.with(MappingInput.Top,MappingAction.None).encode();ok(MappingConfig.decode(text).getConfig().action(MappingInput.Top)==MappingAction.None);
  ok(MappingConfig.decode(text+"\nunknown-input=bogus").getConfig().action(MappingInput.Top)==MappingAction.None);
  ok(MappingConfig.decode(text+"\ntop=play").getConfig().action(MappingInput.Top)==MappingAction.ToggleLoop);
  ok(MappingConfig.decode("version=1\ntop=broken").getNotice()!=null);
  ok(MappingConfig.decode("x".repeat(9000)).getConfig().equals(defaults));
  Port p=new Port();MappingSaveCoordinator store=new MappingSaveCoordinator(p);MappingSnapshot initial=store.snapshot();
  MappingConfig custom=defaults.with(MappingInput.Top,MappingAction.Screenshot);
  p.fail=true;MappingSaveResult r=store.save(initial.getRevision(),custom);ok(!r.getSuccess());ok(store.snapshot().getConfig().equals(defaults));ok(p.value==null);
  p.fail=false;r=store.save(0,custom);ok(r.getSuccess());ok(store.snapshot().getConfig().equals(custom));
  int writes=p.writes;ok(!store.save(0,defaults).getSuccess());ok(p.writes==writes);ok(store.snapshot().getConfig().equals(custom));
  ok(new MappingSaveCoordinator(p).snapshot().getConfig().equals(custom));
  p.throwsIo=true;r=store.save(store.snapshot().getRevision(),defaults);ok(!r.getSuccess());ok(store.snapshot().getConfig().equals(custom));
  MappingEditSession edit=new MappingEditSession(initial);edit.getDraft().set(MappingInput.Top,MappingAction.Screenshot);
  long token=edit.beginSave();ok(token>0&&edit.beginSave()==0);ok(!edit.complete(token+1,new MappingSaveResult(false,initial,"failed")));
  ok(edit.complete(token,new MappingSaveResult(false,initial,"failed")));ok(edit.getDraft().getDirty());
  token=edit.beginSave();edit.close();ok(!edit.complete(token,new MappingSaveResult(true,new MappingSnapshot(custom,1,null),"ok")));
  ok(edit.beginSave()==0);
  PlayerCommand[] expectedCommands={null,PlayerCommand.TogglePaused.INSTANCE,new PlayerCommand.SetPaused(false),new PlayerCommand.SetPaused(true),
   PlayerCommand.StepForward.INSTANCE,PlayerCommand.StepBackward.INSTANCE,new PlayerCommand.SeekRelative(5),new PlayerCommand.SeekRelative(-5),
   new PlayerCommand.ShiftSpeed(1),new PlayerCommand.ShiftSpeed(-1),PlayerCommand.CycleSpeed.INSTANCE,
   new PlayerCommand.SetSpeed(.1),new PlayerCommand.SetSpeed(.25),new PlayerCommand.SetSpeed(.5),new PlayerCommand.SetSpeed(.75),new PlayerCommand.SetSpeed(1),
   PlayerCommand.MarkA.INSTANCE,PlayerCommand.MarkB.INSTANCE,PlayerCommand.EnableLoop.INSTANCE,PlayerCommand.ClearLoop.INSTANCE,PlayerCommand.ToggleLoop.INSTANCE,PlayerCommand.SeekA.INSTANCE,null};
  i=0;for(MappingAction action:MappingAction.values())ok(java.util.Objects.equals(action.command(),expectedCommands[i++]));
  MappingInput[] buttonInputs={MappingInput.Knob,MappingInput.Scroll,MappingInput.Top,MappingInput.Short,MappingInput.C1,MappingInput.C2,MappingInput.Tall,MappingInput.TourBox};
  i=0;for(TourBoxButton button:TourBoxButton.values())ok(MappingInput.fromEvent(new TourBoxInputEvent.ButtonDown(button,stamp()))==buttonInputs[i++]);
  MappingDraft restore=new MappingDraft(custom);restore.resetDefaults();ok(restore.getDirty());ok(custom.action(MappingInput.Top)==MappingAction.Screenshot);
  Port unreadable=new Port();unreadable.readFail=true;MappingSnapshot fallback=new MappingSaveCoordinator(unreadable).snapshot();
  ok(fallback.getConfig().equals(defaults)&&fallback.getNotice()!=null);
  edit=new MappingEditSession(initial);edit.getDraft().set(MappingInput.Top,MappingAction.None);token=edit.beginSave();
  ok(edit.complete(token,new MappingSaveResult(true,new MappingSnapshot(custom,4,null),"saved")));ok(!edit.getDraft().getDirty()&&edit.getRevision()==4);
  System.out.println("TourBoxMappingCheck: "+n+" assertions passed; all mappings/drafts/codec/save failures/conflicts/stale; no Android/USB");
 }
}
