import io.tabletennisreview.input.*;
import io.tabletennisreview.player.*;
import io.tabletennisreview.playback.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import java.util.*;
import java.util.concurrent.*;

/** Device-free tests of mapping, queue and authorization boundaries; no Android/native classes. */
public final class TourBoxPlayerBindingCheck {
    static int assertions, scenarios;
    static void ok(boolean v) { assertions++; if(!v) throw new AssertionError("mapping assertion " + assertions); }
    static final class Source implements TourBoxInputEvents {
        Function1<? super TourBoxInputEvent,Unit> listener;
        long active=7; boolean gate, fail; int closes;
        public AutoCloseable subscribe(Function1<? super TourBoxInputEvent,Unit> l) { listener=l; return ()->{closes++;listener=null;}; }
        public boolean isCurrent(TourBoxInputEvent e) {return active==e.getStamp().getSessionId();}
        public boolean withCurrent(TourBoxInputEvent e, Function0<Unit> f) {
            if(fail)throw new IllegalStateException("source");
            if(!isCurrent(e))return false;
            gate=true; try {f.invoke();return true;}finally{gate=false;}
        }
        void emit(TourBoxInputEvent e) {gate=true;try{if(listener!=null)listener.invoke(e);}finally{gate=false;}}
    }
    static final class Ui implements Executor {
        ArrayDeque<Runnable> tasks=new ArrayDeque<>(); boolean reject;
        public void execute(Runnable r) {if(reject)throw new RejectedExecutionException();tasks.add(r);}
        void drain(){int limit=200;while(!tasks.isEmpty()){if(--limit==0)throw new AssertionError("busy loop");tasks.remove().run();}}
    }
    static final class Player implements PlayerController {
        final Source source; Function1<? super PlayerState,Unit> listener;
        PlayerState state=state(11,false); List<PlayerCommand> commands=new ArrayList<>();
        List<Function0<Boolean>> guards=new ArrayList<>(); boolean reject,fail;
        Player(Source source){this.source=source;}
        static PlayerState state(long token,boolean busy){return new PlayerState(token,PlayerPhase.Paused,true,busy,0.0,30.0,1.0,true,new LoopPoints(),"test");}
        public PlayerState getState(){return state;}
        public void setStateListener(Function1<? super PlayerState,Unit> l){listener=l;}
        public void setLogListener(Function1<? super String,Unit> l){}
        void emit(PlayerState s){state=s;if(listener!=null)listener.invoke(s);}
        void complete(){emit(state(state.getSessionId(),false));}
        public CommandAdmission initialize(String uri){return CommandAdmission.Accepted.INSTANCE;}
        public CommandAdmission initializePaused(String uri){throw new AssertionError("mapping must not initialize screenshots");}
        public CommandAdmission prepareScreenshot(Function1<? super ScreenshotPreparation,Unit> callback){throw new AssertionError("mapping must not capture screenshots");}
        public CommandAdmission prepareScreenshotGuarded(Function0<Boolean> authorization,Function1<? super ScreenshotPreparation,Unit> callback){throw new AssertionError("mapping must use host screenshot entry");}
        public CommandAdmission execute(PlayerCommand c){throw new AssertionError("guard required");}
        public CommandAdmission executeGuarded(PlayerCommand c,Function0<Boolean> a,String origin){
            ok(!source.gate); if(fail)throw new IllegalStateException("player");
            if(reject)return new CommandAdmission.Rejected("test");
            if(!a.invoke())return new CommandAdmission.Rejected("stale");
            ok(!source.gate);ok(origin.contains("usbSession=7"));
            commands.add(c);guards.add(a);emit(state(state.getSessionId(),true));return CommandAdmission.Accepted.INSTANCE;
        }
        public CommandAdmission prepareReviewSnapshot(Function0<Boolean> authorization,Function1<? super ReviewPreparation,Unit> callback){throw new AssertionError("mapping must not create bookmarks");}
        public CommandAdmission restoreReview(ReviewPreset preset,Function0<Boolean> authorization,Function1<? super ReviewRestoreResult,Unit> callback){throw new AssertionError("mapping must not restore bookmarks");}
        public CommandAdmission prepareExport(Function1<? super ExportPreparation,Unit> callback){throw new AssertionError("mapping must not start export");}
        public void pauseForPageChange(){} public void release(String why){} public void close(){}
    }
    static final class Test implements AutoCloseable {
        Source source=new Source();Ui ui=new Ui();Player player=new Player(source);
        int captures; Function0<Boolean> captureGuard; long clock=1000,seq;boolean healthy=true;List<String> logs=new ArrayList<>();TourBoxPlayerBinding binding;
        Test(){this(32);}
        Test(int capacity){
            binding=new TourBoxPlayerBinding(source,player,ui,()->clock,s->{logs.add(s);return Unit.INSTANCE;},s->Unit.INSTANCE,()->healthy,capacity,2_000_000_000L,(guard,origin)->{
                ok(!source.gate);if(!guard.invoke())return new CommandAdmission.Rejected("stale capture");
                captures++;captureGuard=guard;binding.setEnabled(false);return CommandAdmission.Accepted.INSTANCE;
            });
            player.setStateListener(s->{binding.updateTarget(s,true);return Unit.INSTANCE;});
            binding.updateTarget(player.state,true);ui.drain();scenarios++;
        }
        InputStamp stamp(int raw){return new InputStamp(7,++seq,clock,0,raw);}
        TourBoxInputEvent cw(){return new TourBoxInputEvent.KnobClockwise(stamp(0x44));}
        void enable(){ok(binding.setEnabled(true));ui.drain();}
        void send(TourBoxInputEvent e){source.emit(e);}
        void finish(){player.complete();ui.drain();}
        public void close(){binding.close();ui.drain();}
    }
    static void sensitivityChecks(){
        try(Test t=new Test()) {
            t.binding.updateMappings(MappingConfig.defaults().withSensitivity(RotaryControl.Knob,SensitivityLevel.Quarter),1);t.enable();
            for(int i=0;i<3;i++)t.send(t.cw());t.ui.drain();ok(t.player.commands.isEmpty());
            t.send(t.cw());t.ui.drain();ok(t.player.commands.equals(Arrays.asList(PlayerCommand.StepForward.INSTANCE)));
            ok(t.binding.status().getReceived()==4);ok(t.binding.status().getIgnored()==3);
        }
        try(Test t=new Test()) {
            t.binding.updateMappings(MappingConfig.defaults().withSensitivity(RotaryControl.Knob,SensitivityLevel.Quadruple),1);t.enable();
            t.send(t.cw());ok(t.binding.status().getPending()==4);t.ui.drain();for(int i=1;i<4;i++)t.finish();
            ok(t.player.commands.equals(Arrays.asList(PlayerCommand.StepForward.INSTANCE,PlayerCommand.StepForward.INSTANCE,PlayerCommand.StepForward.INSTANCE,PlayerCommand.StepForward.INSTANCE)));
            ok(t.binding.status().getReceived()==1&&t.binding.status().getAdmitted()==4);
            ok(t.logs.stream().anyMatch(x->x.contains("part=4/4")));
        }
        try(Test t=new Test()) {
            t.binding.updateMappings(MappingConfig.defaults().withSensitivity(RotaryControl.Scroll,SensitivityLevel.Quadruple),1);t.enable();
            t.send(new TourBoxInputEvent.ScrollUp(t.stamp(0x49)));t.ui.drain();
            ok(t.player.commands.equals(Arrays.asList(new PlayerCommand.SeekRelative(20))));
        }
        for(MappingAction action:new MappingAction[]{MappingAction.TogglePause,MappingAction.MarkA,MappingAction.SpeedUp,MappingAction.Screenshot})try(Test t=new Test()){
            t.binding.updateMappings(MappingConfig.defaults().with(MappingInput.KnobCW,action).withSensitivity(RotaryControl.Knob,SensitivityLevel.Quadruple),1);t.enable();
            t.send(t.cw());ok(t.binding.status().getPending()==1);t.ui.drain();ok(t.captures+(t.player.commands.size())==1);
        }
        try(Test t=new Test()) {
            t.binding.updateMappings(MappingConfig.defaults().with(MappingInput.Knob,MappingAction.FrameForward).withSensitivity(RotaryControl.Knob,SensitivityLevel.Quadruple),1);t.enable();
            t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.KNOB,t.stamp(0x37)));t.ui.drain();ok(t.player.commands.size()==1);
        }
        for(int mode=0;mode<6;mode++)try(Test t=new Test()){
            MappingConfig cfg=MappingConfig.defaults().withSensitivity(RotaryControl.Knob,SensitivityLevel.Half);
            t.binding.updateMappings(cfg,1);t.enable();t.send(t.cw());t.ui.drain();ok(t.player.commands.isEmpty());
            switch(mode){
                case 0:t.binding.setEnabled(false);t.enable();break;
                case 1:t.binding.updateMappings(cfg.with(MappingInput.Top,MappingAction.Play),2);break;
                case 2:t.binding.approveUsbSession(null);t.binding.approveUsbSession(7L);t.enable();break;
                case 3:t.player.emit(Player.state(12,false));t.enable();break;
                case 4:t.binding.cancelPending("screen");break;
                case 5:t.binding.updateTarget(t.player.state,false);t.binding.updateTarget(t.player.state,true);t.enable();break;
            }
            t.send(t.cw());t.ui.drain();ok(t.player.commands.isEmpty());t.send(t.cw());t.ui.drain();ok(t.player.commands.size()==1);
        }
        try(Test t=new Test()) {
            t.binding.updateMappings(MappingConfig.defaults().withSensitivity(RotaryControl.Knob,SensitivityLevel.Half),1);t.enable();t.send(t.cw());
            t.player.emit(Player.state(11,true));t.player.complete();t.send(t.cw());t.ui.drain();ok(t.player.commands.size()==1);
        }
        try(Test t=new Test(32)) {
            t.binding.updateMappings(MappingConfig.defaults().withSensitivity(RotaryControl.Knob,SensitivityLevel.Quadruple),1);t.enable();
            for(int i=0;i<1000;i++)t.send(t.cw());ok(t.binding.status().getPending()==32);ok(t.binding.status().getReceived()==1000);ok(t.binding.status().getCancelled()==3968);
            t.ui.drain();t.binding.approveUsbSession(null);t.finish();ok(t.player.commands.size()==1);ok(!t.player.guards.get(0).invoke());
        }
        try(Test t=new Test()) {
            t.binding.updateMappings(MappingConfig.defaults().withSensitivity(RotaryControl.Knob,SensitivityLevel.Quadruple),1);t.enable();t.send(t.cw());
            t.clock+=2_000_000_001L;t.ui.drain();ok(t.player.commands.isEmpty());ok(t.binding.status().getCancelled()==4);
        }
        try(Test t=new Test()) {
            t.binding.updateMappings(MappingConfig.defaults().withSensitivity(RotaryControl.Knob,SensitivityLevel.Double),1);t.enable();t.send(t.cw());
            t.send(new TourBoxInputEvent.KnobCounterClockwise(t.stamp(4)));t.ui.drain();for(int i=1;i<4;i++)t.finish();
            ok(t.player.commands.equals(Arrays.asList(PlayerCommand.StepForward.INSTANCE,PlayerCommand.StepForward.INSTANCE,PlayerCommand.StepBackward.INSTANCE,PlayerCommand.StepBackward.INSTANCE)));
        }
    }
    public static void main(String[] args) throws Exception {
        sensitivityChecks();
        try(Test t=new Test()) {
            t.send(t.cw());t.ui.drain();ok(t.player.commands.isEmpty());ok(t.binding.status().getIgnored()==1);
            t.enable();
            TourBoxInputEvent[] events={t.cw(),new TourBoxInputEvent.KnobCounterClockwise(t.stamp(4)),new TourBoxInputEvent.ScrollUp(t.stamp(0x49)),new TourBoxInputEvent.ScrollDown(t.stamp(9)),new TourBoxInputEvent.ButtonDown(TourBoxButton.KNOB,t.stamp(0x37)),new TourBoxInputEvent.ButtonDown(TourBoxButton.SCROLL,t.stamp(0x38))};
            for(TourBoxInputEvent e:events)t.send(e);t.ui.drain();
            for(int i=1;i<events.length;i++)t.finish();
            ok(t.player.commands.equals(Arrays.asList(PlayerCommand.StepForward.INSTANCE,PlayerCommand.StepBackward.INSTANCE,new PlayerCommand.SeekRelative(5),new PlayerCommand.SeekRelative(-5),PlayerCommand.TogglePaused.INSTANCE,PlayerCommand.CycleSpeed.INSTANCE)));
            ok(t.binding.status().getAdmitted()==6);ok(t.binding.status().getPending()==0);
        }
        try(Test t=new Test()) {t.enable();for(TourBoxButton b:TourBoxButton.values())t.send(new TourBoxInputEvent.ButtonUp(b,t.stamp(0x80)));for(TourBoxButton b:TourBoxButton.values())if(b==TourBoxButton.SHORT||b==TourBoxButton.TALL||b==TourBoxButton.TOURBOX)t.send(new TourBoxInputEvent.ButtonDown(b,t.stamp(2)));t.send(new TourBoxInputEvent.UnknownRawCode(t.stamp(0xff)));t.ui.drain();ok(t.player.commands.isEmpty());ok(t.binding.status().getIgnored()==12);}
        try(Test t=new Test()) {t.enable();for(int i=0;i<10;i++)t.send(t.cw());t.ui.drain();ok(t.player.commands.size()==1);ok(t.binding.status().getPending()==9);for(int i=1;i<10;i++)t.finish();ok(t.player.commands.size()==10);ok(t.binding.status().getAdmitted()==10);}
        try(Test t=new Test(2)) {t.enable();for(int i=0;i<3;i++)t.send(t.cw());t.ui.drain();ok(t.binding.status().getEnabled());ok(t.binding.status().getCancelled()==1);ok(t.player.commands.size()==1);ok(t.logs.stream().anyMatch(x->x.contains("PRESSURE")));t.finish();ok(t.player.commands.size()==2);t.finish();t.send(t.cw());t.ui.drain();ok(t.player.commands.size()==3);}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.clock+=2_000_000_001L;t.ui.drain();ok(t.binding.status().getEnabled());ok(t.player.commands.isEmpty());ok(t.binding.status().getCancelled()==1);t.send(t.cw());t.ui.drain();ok(t.player.commands.size()==1);}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.clock--;t.ui.drain();ok(!t.binding.status().getEnabled());ok(t.player.commands.isEmpty());}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.source.active=8;t.ui.drain();ok(t.player.commands.isEmpty());ok(t.binding.status().getCancelled()==1);}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.player.emit(Player.state(12,false));t.ui.drain();ok(t.player.commands.isEmpty());ok(t.binding.status().getCancelled()==1);}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.ui.drain();ok(t.player.guards.get(0).invoke());t.source.active=8;ok(!t.player.guards.get(0).invoke());ok(!t.player.guards.get(0).invoke());ok(t.binding.status().getCancelled()==1);}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.binding.setEnabled(false);t.ui.drain();ok(t.player.commands.isEmpty());}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.binding.updateTarget(t.player.state,false);t.ui.drain();ok(t.player.commands.isEmpty());ok(!t.binding.status().getEnabled());ok(!t.binding.setEnabled(true));}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.ui.drain();t.binding.cancelPending("screen");ok(!t.player.guards.get(0).invoke());t.finish();t.send(t.cw());t.ui.drain();ok(t.player.commands.size()==2);}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.binding.close();t.binding.close();t.ui.drain();ok(t.source.closes==1);ok(t.player.commands.isEmpty());ok(!t.binding.setEnabled(true));}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.healthy=false;t.ui.drain();ok(t.player.commands.isEmpty());ok(!t.binding.status().getEnabled());}
        try(Test t=new Test()) {t.enable();t.source.fail=true;t.send(t.cw());t.ui.drain();ok(t.player.commands.isEmpty());ok(!t.binding.status().getEnabled());}
        try(Test t=new Test()) {t.enable();t.player.reject=true;t.send(t.cw());t.send(t.cw());t.ui.drain();ok(t.binding.status().getRejected()==2);ok(t.binding.status().getEnabled());ok(t.binding.status().getPending()==0);}
        try(Test t=new Test()) {t.enable();t.player.fail=true;t.send(t.cw());t.ui.drain();ok(t.binding.status().getRejected()==1);ok(!t.binding.status().getEnabled());}
        try(Test t=new Test()) {t.enable();t.ui.reject=true;t.send(t.cw());ok(!t.binding.status().getEnabled());ok(t.player.commands.isEmpty());}
        try(Test t=new Test()) {t.binding.updateTarget(new PlayerState(),true);ok(!t.binding.setEnabled(true));t.ui.drain();t.send(t.cw());t.ui.drain();ok(t.player.commands.isEmpty());}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.ui.drain();t.binding.updateTarget(t.player.state,false);ok(!t.player.guards.get(0).invoke());}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.ui.drain();t.healthy=false;ok(!t.player.guards.get(0).invoke());ok(!t.binding.status().getEnabled());}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.ui.drain();t.player.emit(Player.state(12,false));ok(!t.player.guards.get(0).invoke());ok(!t.binding.status().getEnabled());}
        try(Test t=new Test()) {t.enable();t.send(t.cw());t.binding.cancelPending("screen before drain");t.ui.drain();ok(t.player.commands.isEmpty());ok(t.binding.status().getCancelled()==1);}
        {
            Source source=new Source();Ui ui=new Ui();Player player=new Player(source);
            TourBoxPlayerBinding b=new TourBoxPlayerBinding(source,player,ui,()->1000L,s->{throw new IllegalStateException("audit");},s->Unit.INSTANCE,()->true,32,2_000_000_000L);
            b.updateTarget(player.state,true);b.setEnabled(true);ui.drain();ok(!b.status().getEnabled());ok(b.status().getFault().contains("日志"));b.close();scenarios++;
        }
        try(Test t=new Test()) {
            t.binding.approveUsbSession(null);t.enable();t.send(t.cw());t.ui.drain();ok(t.player.commands.isEmpty());
            t.binding.approveUsbSession(7L);t.enable();t.send(t.cw());t.ui.drain();ok(t.player.commands.size()==1);
            t.binding.approveUsbSession(8L);ok(!t.player.guards.get(0).invoke());t.finish();t.enable();t.send(t.cw());t.ui.drain();ok(t.player.commands.size()==1);
        }
        try(Test t=new Test()) {
            t.enable();
            t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.C1,t.stamp(0)));
            t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.C2,t.stamp(0)));
            t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.TOP,t.stamp(2)));
            t.send(new TourBoxInputEvent.ButtonUp(TourBoxButton.C1,t.stamp(0)));
            t.send(new TourBoxInputEvent.ButtonUp(TourBoxButton.C2,t.stamp(0)));
            t.send(new TourBoxInputEvent.ButtonUp(TourBoxButton.TOP,t.stamp(0x82)));
            t.ui.drain();t.finish();t.finish();
            ok(t.player.commands.equals(Arrays.asList(PlayerCommand.MarkA.INSTANCE,PlayerCommand.MarkB.INSTANCE,PlayerCommand.ToggleLoop.INSTANCE)));
            ok(t.binding.status().getIgnored()==3);
        }
        try(Test t=new Test(3)) {
            t.enable();t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.C1,t.stamp(0)));
            t.send(t.cw());t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.C2,t.stamp(0)));
            t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.TOP,t.stamp(2)));t.ui.drain();t.finish();t.finish();
            ok(t.player.commands.equals(Arrays.asList(PlayerCommand.MarkA.INSTANCE,PlayerCommand.MarkB.INSTANCE,PlayerCommand.ToggleLoop.INSTANCE)));
            ok(t.binding.status().getCancelled()==1);ok(t.binding.status().getEnabled());
        }
        try(Test t=new Test(2)) {
            t.enable();t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.C1,t.stamp(0)));
            t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.C2,t.stamp(0)));t.send(t.cw());t.ui.drain();t.finish();
            ok(t.player.commands.equals(Arrays.asList(PlayerCommand.MarkA.INSTANCE,PlayerCommand.MarkB.INSTANCE)));
            ok(t.binding.status().getCancelled()==1);ok(t.binding.status().getEnabled());
        }
        try(Test t=new Test()) {
            t.enable();t.send(t.cw());t.send(t.cw());t.clock+=2_000_000_001L;t.send(new TourBoxInputEvent.ScrollUp(t.stamp(0x49)));t.ui.drain();
            ok(t.binding.status().getEnabled());ok(t.binding.status().getCancelled()==2);
            ok(t.player.commands.equals(Arrays.asList(new PlayerCommand.SeekRelative(5))));
        }
        try(Test t=new Test(32)) {
            t.enable();for(int i=0;i<1000;i++)t.send(t.cw());ok(t.binding.status().getPending()==32);ok(t.binding.status().getCancelled()==968);
            t.ui.drain();for(int i=1;i<32;i++)t.finish();ok(t.player.commands.size()==32);ok(t.binding.status().getEnabled());
        }
        try(Test t=new Test()) {
            t.enable();t.binding.updateMappings(MappingConfig.defaults().with(MappingInput.KnobCW,MappingAction.None).with(MappingInput.Short,MappingAction.Play),1);
            t.send(t.cw());t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.SHORT,t.stamp(3)));t.ui.drain();
            ok(t.player.commands.equals(Arrays.asList(new PlayerCommand.SetPaused(false))));
        }
        try(Test t=new Test()) {
            t.enable();t.send(t.cw());t.send(t.cw());t.binding.updateMappings(MappingConfig.defaults().with(MappingInput.KnobCW,MappingAction.FrameBackward),1);
            t.ui.drain();ok(t.player.commands.isEmpty());ok(t.binding.status().getCancelled()==2);
            t.send(t.cw());t.ui.drain();ok(t.player.commands.equals(Arrays.asList(PlayerCommand.StepBackward.INSTANCE)));
            ok(!t.binding.updateMappings(MappingConfig.defaults(),0));
        }
        try(Test t=new Test()) {
            t.enable();t.send(t.cw());t.ui.drain();Function0<Boolean> old=t.player.guards.get(0);
            t.binding.updateMappings(MappingConfig.defaults().with(MappingInput.Top,MappingAction.Play),1);ok(!old.invoke());
        }
        try(Test t=new Test()) {
            t.enable();t.binding.updateMappings(MappingConfig.defaults().with(MappingInput.C1,MappingAction.Screenshot),1);
            t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.C1,t.stamp(0x22)));t.ui.drain();
            ok(t.captures==1&&t.player.commands.isEmpty());ok(t.captureGuard.invoke()); // own suspension must not revoke capture
            t.source.active=8;ok(!t.captureGuard.invoke());
        }
        try(Test t=new Test()) {
            t.enable();t.binding.updateMappings(MappingConfig.defaults().with(MappingInput.C1,MappingAction.Screenshot),1);
            t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.C1,t.stamp(0x22)));t.ui.drain();ok(t.captureGuard.invoke());
            t.binding.updateMappings(MappingConfig.defaults(),2);ok(!t.captureGuard.invoke());
        }
        try(Test t=new Test(2)) {
            t.enable();t.binding.updateMappings(MappingConfig.defaults().with(MappingInput.KnobCW,MappingAction.Screenshot),1);
            t.send(t.cw());t.send(t.cw());t.send(new TourBoxInputEvent.ButtonDown(TourBoxButton.TOP,t.stamp(2)));
            ok(t.binding.status().getPending()==2&&t.binding.status().getCancelled()==1);t.ui.drain();
            ok(t.captures==1&&t.binding.status().getPending()==0);
        }
        System.out.println("TourBoxPlayerBindingCheck: "+scenarios+" scenarios, "+assertions+" assertions passed (no Android/native/USB)");
    }
}
