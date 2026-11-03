import io.tabletennisreview.playback.*;
public final class PlayerUiCheck {
    static int count;
    static void ok(boolean value){count++;if(!value)throw new AssertionError("UI policy "+count);}
    public static void main(String[] args) {
        TourBoxControlUiPolicy p=TourBoxControlUiPolicy.INSTANCE;
        ok(!p.automatic(false,false,false));ok(!p.automatic(true,false,false));
        ok(p.automatic(true,true,false));ok(!p.automatic(true,true,true));
        ok(!p.automatic(false,true,false));ok(!p.automatic(false,false,true));
        ok(p.automatic(true,true,false)); // new healthy session can enable again, no checkbox
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
        ProgressMarkers marks=ProgressMarkers.INSTANCE;
        ok(marks.fraction(0.0,10.0)==0);ok(marks.fraction(10.0,10.0)==1);
        ok(marks.fraction(2.5,10.0)==0.25);
        ok(marks.fraction(null,10.0)==null);ok(marks.fraction(1.0,null)==null);
        ok(marks.fraction(1.0,0.0)==null);ok(marks.fraction(-1.0,10.0)==null);
        ok(marks.fraction(11.0,10.0)==null);ok(marks.fraction(Double.NaN,10.0)==null);
        ok(marks.fraction(1.0,Double.POSITIVE_INFINITY)==null);
        ok(PlaybackButtonUi.INSTANCE.label(io.tabletennisreview.player.PlayerPhase.Playing).equals("暂停"));
        ok(PlaybackButtonUi.INSTANCE.label(io.tabletennisreview.player.PlayerPhase.Buffering).equals("暂停"));
        ok(PlaybackButtonUi.INSTANCE.label(io.tabletennisreview.player.PlayerPhase.Paused).equals("播放"));
        ok(PlaybackButtonUi.INSTANCE.label(io.tabletennisreview.player.PlayerPhase.Released).equals("播放"));
        ok(PlayerResumePolicy.INSTANCE.pausedAfterStop(false,false));ok(PlayerResumePolicy.INSTANCE.pausedAfterStop(true,true));
        ok(PlayerResumePolicy.INSTANCE.pausedAfterStop(true,false));ok(!PlayerResumePolicy.INSTANCE.pausedAfterStop(false,true));
        ok(SettingsUiPolicy.INSTANCE.canOpen(io.tabletennisreview.player.PlayerPhase.Paused,false,false));
        ok(SettingsUiPolicy.INSTANCE.canOpen(io.tabletennisreview.player.PlayerPhase.Released,false,false));
        ok(!SettingsUiPolicy.INSTANCE.canOpen(io.tabletennisreview.player.PlayerPhase.Initializing,false,false));
        ok(!SettingsUiPolicy.INSTANCE.canOpen(io.tabletennisreview.player.PlayerPhase.Loading,false,false));
        ok(!SettingsUiPolicy.INSTANCE.canOpen(io.tabletennisreview.player.PlayerPhase.Playing,true,false));
        ok(!SettingsUiPolicy.INSTANCE.canOpen(io.tabletennisreview.player.PlayerPhase.Playing,false,true));
        // Android can save state before onStop marks reloadPaused; modal visibility must suffice.
        boolean[][] resumeCases={{false,false,false,true},{false,false,true,false},{false,true,false,true},{false,true,true,true},
          {true,false,false,true},{true,false,true,true},{true,true,false,true},{true,true,true,true}};
        for(boolean[] c:resumeCases)ok(PlayerResumePolicy.INSTANCE.pausedForRecreation(c[0],c[1],c[2])==c[3]);
        io.tabletennisreview.player.PlayerState loaded=new io.tabletennisreview.player.PlayerState(5,io.tabletennisreview.player.PlayerPhase.Paused,true,false,0.0,10.0,1.0,true,new io.tabletennisreview.player.LoopPoints(),"ready");
        ok(BookmarkUiPolicy.INSTANCE.canOpen(loaded,true,true,false,false,true));
        ok(!BookmarkUiPolicy.INSTANCE.canOpen(loaded,false,true,false,false,true));
        ok(!BookmarkUiPolicy.INSTANCE.canOpen(loaded,true,false,false,false,true));
        ok(!BookmarkUiPolicy.INSTANCE.canOpen(loaded,true,true,true,false,true));
        ok(!BookmarkUiPolicy.INSTANCE.canOpen(loaded,true,true,false,true,true));
        ok(!BookmarkUiPolicy.INSTANCE.canOpen(loaded,true,true,false,false,false));
        ok(!BookmarkUiPolicy.INSTANCE.canOpen(new io.tabletennisreview.player.PlayerState(),true,true,false,false,true));
        // Bookmark modal uses the same explicit pause-intent policy as screenshot/settings.
        ok(PlayerResumePolicy.INSTANCE.pausedForRecreation(false,true,true));
        ok(PlayerResumePolicy.INSTANCE.pausedAfterStop(true,true));
        System.out.println("PlayerUiCheck: "+count+" assertions passed; automatic control gates and overlay state (no Android/USB)");
    }
}
