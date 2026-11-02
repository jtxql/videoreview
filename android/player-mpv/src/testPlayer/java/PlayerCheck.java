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
        rejects(()->policy.requests(PlayerCommand.StepForward.INSTANCE,live));
        rejects(()->policy.requests(PlayerCommand.EnableLoop.INSTANCE,new LoopPoints(3.0,1.0,true)));
        List<NativeRequest> enable=policy.requests(PlayerCommand.EnableLoop.INSTANCE,live);
        ok(enable.size()==6); ok(prop(enable.get(0)).getName().equals("ab-loop-b") && prop(enable.get(0)).getValue().equals("no"));
        ok(prop(enable.get(1)).getName().equals("ab-loop-a") && prop(enable.get(1)).getValue().equals("1.0"));
        ok(prop(enable.get(2)).getName().equals("ab-loop-count") && prop(enable.get(2)).getValue().equals("inf"));
        ok(prop(enable.get(3)).getName().equals("ab-loop-b") && prop(enable.get(3)).getValue().equals("3.0"));
        ok(prop(enable.get(4)).getName().equals("pause")); ok(args(enable.get(5)).equals(Arrays.asList("seek","1.0","absolute+exact")));
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
        System.out.println("PlayerCheck: " + count + " assertions passed; pure policy/plans/epoch only, no Android/native");
    }
}
