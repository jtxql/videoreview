import io.tabletennisreview.player.*;
import java.util.*;
public final class PlayerCheck {
    private static int count;
    private static final PlayerPolicy policy = PlayerPolicy.INSTANCE;
    private static final LoopPoints empty = new LoopPoints();
    private static void ok(boolean value) { count++; if (!value) throw new AssertionError("Player check " + count); }
    private static void rejects(Runnable action) { boolean found=false; try { action.run(); } catch (IllegalArgumentException e) { found=true; } ok(found); }
    private static List<NativeRequest> plan(PlayerCommand cmd) { return policy.requests(cmd, empty); }
    private static NativeRequest.Property prop(NativeRequest r) { return (NativeRequest.Property)r; }
    private static List<String> args(NativeRequest r) { return ((NativeRequest.Command)r).getArguments(); }
    public static void main(String[] args) {
        ok(prop(plan(new PlayerCommand.SetPaused(true)).get(0)).getValue().equals("yes"));
        ok(prop(plan(new PlayerCommand.SetPaused(false)).get(0)).getValue().equals("no"));
        for(double speed:new double[]{0.1,0.25,0.5,0.75,1.0}) {
            NativeRequest.Property r=prop(plan(new PlayerCommand.SetSpeed(speed)).get(0));
            ok(r.getName().equals("speed") && Double.parseDouble(r.getValue())==speed);
        }
        for(double speed:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,2}) rejects(()->plan(new PlayerCommand.SetSpeed(speed)));
        ok(args(plan(new PlayerCommand.SeekRelative(5)).get(0)).equals(Arrays.asList("seek","5.0","relative+exact")));
        ok(args(plan(new PlayerCommand.SeekRelative(-5)).get(0)).equals(Arrays.asList("seek","-5.0","relative+exact")));
        rejects(()->plan(new PlayerCommand.SeekRelative(0))); rejects(()->plan(new PlayerCommand.SeekRelative(Double.NaN)));
        rejects(()->plan(new PlayerCommand.SeekRelative(Double.NEGATIVE_INFINITY)));
        List<NativeRequest> f=plan(PlayerCommand.StepForward.INSTANCE), b=plan(PlayerCommand.StepBackward.INSTANCE);
        ok(f.size()==2 && prop(f.get(0)).getName().equals("pause") && prop(f.get(0)).getValue().equals("yes"));
        ok(args(f.get(1)).equals(Arrays.asList("frame-step"))); ok(args(b.get(1)).equals(Arrays.asList("frame-back-step")));
        ok(policy.needsSeek(PlayerCommand.StepForward.INSTANCE)); ok(policy.needsSeek(new PlayerCommand.SeekRelative(5)));
        ok(!policy.needsSeek(new PlayerCommand.SetSpeed(0.5)));
        rejects(()->empty.markB(1)); rejects(()->empty.markA(Double.NaN)); rejects(()->empty.markA(-1));
        LoopPoints a=empty.markA(1); rejects(()->a.markB(1)); rejects(()->a.markB(0)); rejects(()->a.markB(Double.POSITIVE_INFINITY));
        LoopPoints ab=a.markB(3); ok(ab.getA()==1 && ab.getB()==3 && !ab.getActive());
        rejects(()->ab.enabled(null)); rejects(()->ab.enabled(0.0)); rejects(()->ab.enabled(Double.NaN)); rejects(()->ab.enabled(2.0));
        LoopPoints live=ab.enabled(3.0); ok(live.getActive());
        rejects(()->live.markA(2)); rejects(()->live.markB(4));
        List<NativeRequest> loopForward=policy.requests(PlayerCommand.StepForward.INSTANCE,live);
        List<NativeRequest> loopBackward=policy.requests(PlayerCommand.StepBackward.INSTANCE,live);
        ok(loopForward.equals(f)); ok(loopBackward.equals(b));
        ok(live.getActive() && live.getA()==1 && live.getB()==3); // frame commands leave both loop points intact
        rejects(()->policy.requests(PlayerCommand.EnableLoop.INSTANCE,new LoopPoints(3.0,1.0,true)));
        List<NativeRequest> enable=policy.requests(PlayerCommand.EnableLoop.INSTANCE,live);
        ok(enable.size()==7);
        ok(prop(enable.get(0)).getName().equals("pause") && prop(enable.get(0)).getValue().equals("yes"));
        ok(prop(enable.get(1)).getName().equals("ab-loop-b") && prop(enable.get(1)).getValue().equals("no"));
        ok(prop(enable.get(2)).getName().equals("ab-loop-a") && prop(enable.get(2)).getValue().equals("1.0"));
        ok(prop(enable.get(3)).getName().equals("ab-loop-count") && prop(enable.get(3)).getValue().equals("inf"));
        ok(prop(enable.get(4)).getName().equals("ab-loop-b") && prop(enable.get(4)).getValue().equals("3.0"));
        ok(args(enable.get(5)).equals(Arrays.asList("seek","1.0","absolute+exact")));
        ok(prop(enable.get(6)).getName().equals("pause") && prop(enable.get(6)).getValue().equals("no"));
        List<NativeRequest> clear=plan(PlayerCommand.ClearLoop.INSTANCE);
        ok(clear.size()==2 && prop(clear.get(0)).getName().equals("ab-loop-b") && prop(clear.get(1)).getName().equals("ab-loop-a"));
        ok(prop(clear.get(0)).getValue().equals("no") && prop(clear.get(1)).getValue().equals("no"));
        ok(plan(PlayerCommand.MarkA.INSTANCE).isEmpty() && plan(PlayerCommand.MarkB.INSTANCE).isEmpty());
        ok(args(policy.requests(PlayerCommand.SeekA.INSTANCE,ab).get(1)).equals(Arrays.asList("seek","1.0","absolute+exact")));
        rejects(()->plan(PlayerCommand.SeekA.INSTANCE));
        ok(ab.markA(2).getB()==null); ok(empty.markA(0).markB(0.01).enabled(1.0).getActive());
        ok(empty.getA()==null && !ab.getActive());
        PlayerEpoch first=new PlayerEpoch(), second=new PlayerEpoch(); ok(!first.isCurrent(0));
        long old=first.begin(); ok(first.isCurrent(old)); first.invalidate(); ok(!first.isCurrent(old));
        first.invalidate(); ok(!first.isCurrent(old)); long next=first.begin(); ok(next!=old && first.isCurrent(next) && !first.isCurrent(old));
        long other=second.begin(); ok(other!=next && !second.isCurrent(next));
        long replacement=first.begin(); ok(!first.isCurrent(next) && first.isCurrent(replacement));
        first.invalidate(); ok(!first.isCurrent(replacement));
        PlayerState state=new PlayerState();
        ok(!policy.canInitialize(false,true,true,state)); // no URI before first pick
        ok(policy.canInitialize(true,true,true,state)); // first URI immediately enables initialize
        ok(!policy.canInitialize(true,false,true,state)); ok(!policy.canInitialize(true,true,false,state));
        ok(!policy.toggled(true).getPaused()); ok(policy.toggled(false).getPaused());
        double[] ring={0.1,0.25,0.5,0.75,1.0};
        for(int i=0;i<ring.length;i++) ok(policy.nextSpeed(ring[i]).getSpeed()==ring[(i+1)%ring.length]);
        ok(policy.nextSpeed(0.50000001).getSpeed()==0.75); ok(policy.nextSpeed(0.3).getSpeed()==0.1);
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) rejects(()->policy.nextSpeed(invalid));
        rejects(()->plan(PlayerCommand.TogglePaused.INSTANCE)); rejects(()->plan(PlayerCommand.CycleSpeed.INSTANCE));
        ok(args(plan(new PlayerCommand.SeekAbsolute(12.5)).get(0)).equals(Arrays.asList("seek","12.5","absolute+exact")));
        ok(policy.needsSeek(new PlayerCommand.SeekAbsolute(0)));rejects(()->plan(new PlayerCommand.SeekAbsolute(-1)));rejects(()->plan(new PlayerCommand.SeekAbsolute(Double.NaN)));
        PlayerInteractionPolicy touch=PlayerInteractionPolicy.INSTANCE;
        ok(touch.progress(null,10.0)==0);ok(touch.progress(1.0,null)==0);ok(touch.progress(Double.NaN,10.0)==0);ok(touch.progress(2.0,0.0)==0);
        ok(touch.progress(5.0,10.0)==500);ok(touch.progress(-1.0,10.0)==0);ok(touch.progress(11.0,10.0)==1000);
        ok(touch.position(500,20)==10);ok(touch.position(-1,20)==0);ok(touch.position(1001,20)==20);rejects(()->touch.position(0,0));rejects(()->touch.position(0,Double.NaN));
        ok(touch.doubleTap(0,300).equals(new PlayerCommand.SeekBySpeed(-1)));
        ok(touch.doubleTap(99.9,300).equals(new PlayerCommand.SeekBySpeed(-1)));
        ok(touch.doubleTap(100,300)==PlayerCommand.TogglePaused.INSTANCE);
        ok(touch.doubleTap(199.9,300)==PlayerCommand.TogglePaused.INSTANCE);
        ok(touch.doubleTap(200,300).equals(new PlayerCommand.SeekBySpeed(1)));
        ok(touch.doubleTap(300,300).equals(new PlayerCommand.SeekBySpeed(1)));
        for(double width:new double[]{300,1080,2400}) {
            ok(touch.doubleTap(width/6,width).equals(new PlayerCommand.SeekBySpeed(-1)));
            ok(touch.doubleTap(width/2,width)==PlayerCommand.TogglePaused.INSTANCE);
            ok(touch.doubleTap(width*5/6,width).equals(new PlayerCommand.SeekBySpeed(1)));
        }
        rejects(()->touch.doubleTap(-1,300));rejects(()->touch.doubleTap(301,300));rejects(()->touch.doubleTap(0,0));
        rejects(()->touch.doubleTap(Double.NaN,300));rejects(()->touch.doubleTap(0,Double.POSITIVE_INFINITY));
        for(int i=0;i<ring.length;i++) for(int direction:new int[]{-1,1}) {
            PlayerCommand.SeekRelative mapped=policy.speedSeek(direction,ring[i]);
            ok(mapped.getSeconds()==direction*(i+1));
            ok(args(plan(mapped).get(0)).equals(Arrays.asList("seek",Double.toString(direction*(i+1.0)),"relative+exact")));
        }
        ok(policy.speedSeek(1,0.25000001).getSeconds()==2);
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,0.3,2}) rejects(()->policy.speedSeek(1,invalid));
        rejects(()->policy.speedSeek(0,1));rejects(()->policy.speedSeek(2,1));
        rejects(()->plan(new PlayerCommand.SeekBySpeed(1)));ok(policy.needsSeek(new PlayerCommand.SeekBySpeed(-1)));
        ok(policy.toggleLoop(empty)==PlayerCommand.EnableLoop.INSTANCE);
        ok(policy.toggleLoop(live)==PlayerCommand.ClearLoop.INSTANCE);
        rejects(()->plan(PlayerCommand.ToggleLoop.INSTANCE));
        rejects(()->policy.requests(policy.toggleLoop(empty),empty.enabled(null)));
        ok(touch.hud(0.25,2.5,120.0).equals("0.25X  2.5S/120S"));
        ok(touch.hud(1.0,0.0,120.04).equals("1X  0S/120S"));
        ok(touch.hud(null,null,null).equals("--X  --S/--S"));
        ok(touch.hud(Double.NaN,-1.0,Double.POSITIVE_INFINITY).equals("--X  --S/--S"));
        ok(policy.shiftSpeed(1,0.25).getSpeed()==0.5);ok(policy.shiftSpeed(-1,0.25).getSpeed()==0.1);
        ok(policy.shiftSpeed(4,0.25).getSpeed()==1);ok(policy.shiftSpeed(-4,0.75).getSpeed()==0.1);
        ok(policy.shiftSpeed(1,1).getSpeed()==1);ok(policy.shiftSpeed(-1,0.1).getSpeed()==0.1);
        ok(policy.shiftSpeed(0,0.5).getSpeed()==0.5);
        rejects(()->policy.shiftSpeed(5,0.5));rejects(()->policy.shiftSpeed(1,Double.NaN));rejects(()->policy.shiftSpeed(1,0.3));
        rejects(()->plan(new PlayerCommand.ShiftSpeed(1)));
        PlayerExportSnapshot snapshot=new PlayerExportSnapshot(1,1,3,0.25,10);
        ok(snapshot.getA()==1&&snapshot.getB()==3&&snapshot.getSpeed()==0.25);
        rejects(()->new PlayerExportSnapshot(0,1,3,1,10));rejects(()->new PlayerExportSnapshot(1,3,1,1,10));
        rejects(()->new PlayerExportSnapshot(1,1,3,0.3,10));rejects(()->new PlayerExportSnapshot(1,1,3,1,2));
        VideoViewport v=new VideoViewport();
        ok(v.getScale()==1&&!v.getEnlarged());
        v.zoom(2,75,50,100,100);ok(v.getScale()==2&&v.getX()==-25&&v.getY()==0);
        v.pan(1000,-1000,100,100);ok(v.getX()==50&&v.getY()==-50);
        v.zoom(100,50,50,100,100);ok(v.getScale()==4);
        v.zoom(0.001,50,50,100,100);ok(v.getScale()==1&&v.getX()==0&&v.getY()==0);
        v.zoom(Double.NaN,50,50,100,100);ok(v.getScale()==1);
        v.zoom(2,50,50,0,100);ok(v.getScale()==1);
        v.zoom(2,50,50,100,100);ok(v.getScale()==2&&v.getX()==0);
        v.pan(10,20,100,100);ok(v.getX()==10&&v.getY()==20);
        v.pan(Double.NaN,0,100,100);ok(v.getX()==10);
        v.reset();ok(v.getScale()==1&&v.getX()==0&&v.getY()==0);
        System.out.println("PlayerCheck: " + count + " assertions passed; pure policy/plans/epoch only, no Android/native");
    }
}
