import io.tabletennisreview.playback.*;
public final class PlayerUiCheck {
    static int count;
    static void ok(boolean value){count++;if(!value)throw new AssertionError("UI policy "+count);}
    public static void main(String[] args) {
        TourBoxControlUiPolicy p=TourBoxControlUiPolicy.INSTANCE;
        boolean wanted=p.wanted(false,false,false);ok(!wanted);ok(!p.selectable(true,true,false,true));
        // Initial Allow callback: preference is usable before an initialized session or video exists.
        wanted=p.wanted(false,true,wanted);ok(wanted);ok(p.selectable(true,true,true,true));
        wanted=p.wanted(true,true,wanted);ok(wanted); // initialized session arrives / periodic refresh
        wanted=false;wanted=p.wanted(true,true,wanted);ok(!wanted); // explicit opt-out stays off
        ok(!p.selectable(false,true,true,true));ok(!p.selectable(true,false,true,true));ok(!p.selectable(true,true,true,false));
        wanted=p.wanted(true,false,true);ok(!wanted);wanted=p.wanted(false,true,wanted);ok(wanted);
        // A permission UI pause/resume does not permanently gray the preference.
        ok(p.selectable(true,true,true,true));
        PlayerProgressOverlay overlay=new PlayerProgressOverlay();
        overlay.layout(false,false);ok(!overlay.getOverlay()&&overlay.getVisible());
        overlay.layout(true,false);ok(overlay.getOverlay()&&overlay.getVisible());
        overlay.tap();ok(!overlay.getVisible());overlay.layout(true,false);ok(!overlay.getVisible());
        overlay.tap();ok(overlay.getVisible());overlay.layout(false,true);ok(overlay.getOverlay());
        overlay.tap();ok(!overlay.getVisible());overlay.layout(true,true);ok(!overlay.getVisible());
        overlay.layout(false,false);ok(!overlay.getOverlay()&&overlay.getVisible());
        overlay.tap();ok(overlay.getVisible());overlay.layout(true,false);ok(overlay.getVisible());
        PlayerVerticalGesture left=new PlayerVerticalGesture(10,200,300,48,8);
        ok(left.update(11,196)==null);
        VerticalChange move=left.update(11,150);ok(move.getControl()==VerticalControl.Speed&&move.getSteps()==1);
        ok(left.update(290,100).getControl()==VerticalControl.Speed); // start region remains locked
        ok(left.update(11,250).getSteps()==-1);ok(left.update(11,200).getSteps()==0);
        PlayerVerticalGesture right=new PlayerVerticalGesture(290,200,300,48,8);
        ok(right.update(290,100).getControl()==VerticalControl.Volume);ok(right.update(290,100).getSteps()==2);
        ok(right.update(290,300).getSteps()==-2);
        PlayerVerticalGesture center=new PlayerVerticalGesture(150,200,300,48,8);ok(center.update(150,0)==null);
        PlayerVerticalGesture horizontal=new PlayerVerticalGesture(10,200,300,48,8);ok(horizontal.update(100,198)==null);ok(horizontal.update(100,0)==null);
        PlayerVerticalGesture border=new PlayerVerticalGesture(100,200,300,48,8);ok(border.update(100,0)==null);
        border=new PlayerVerticalGesture(200,200,300,48,8);ok(border.update(200,0).getControl()==VerticalControl.Volume);
        ok(right.update(Double.NaN,0)==null);ok(right.update(0,Double.POSITIVE_INFINITY)==null);
        System.out.println("PlayerUiCheck: "+count+" assertions passed; initial permission preference and overlay state (no Android/USB)");
    }
}
