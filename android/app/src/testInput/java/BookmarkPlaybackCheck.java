import io.tabletennisreview.playback.*;
import io.tabletennisreview.player.*;
import java.util.*;
public final class BookmarkPlaybackCheck {
 static int n;static void ok(boolean b){n++;if(!b)throw new AssertionError("playback bookmark "+n);}
 static ClipBookmark item(int id,double a,long time){return new ClipBookmark(new UUID(0,id).toString(),"content://video/1","片段"+id,a,a+1,.25,time);}
 public static void main(String[] args){
  ClipBookmark late=item(1,10,1),early=item(2,2,5),mid=item(3,5,4),tie=item(4,5,4);
  BookmarkCatalog c=new BookmarkCatalog(Arrays.asList(late,mid,tie,early));
  ok(c.forVideo("content://video/1").equals(Arrays.asList(early,mid,tie,late)));ok(c.defaultName("content://video/1").equals("片段5"));
  BookmarkSource source=new BookmarkSource("content://video/1",7),other=new BookmarkSource("content://video/1",8);
  BookmarkPlaybackState s=new BookmarkPlaybackState();s.observe(source,new LoopPoints());
  ok(s.candidate(c,1).equals(early));ok(s.candidate(c,-1).equals(late));ok(s.activeName(c)==null);
  s.observe(source,new LoopPoints(2.0,3.0,true));s.restored(early.getId(),source);ok(s.activeName(c).equals(early.getName()));
  ok(s.candidate(c,-1).equals(late));ok(s.candidate(c,1).equals(mid));
  s.observe(source,new LoopPoints(2.0,3.0,true));ok(s.activeName(c).equals(early.getName()));
  s.observe(source,new LoopPoints());ok(s.activeName(c)==null);ok(s.candidate(c,1).equals(mid));
  s.saved(mid,source,new LoopPoints());ok(s.activeName(c)==null);ok(s.candidate(c,1).equals(tie));
  s.saved(mid,source,new LoopPoints(5.0,6.0,true));ok(s.activeName(c).equals(mid.getName()));
  ok(s.activeName(c.rename(mid.getId(),source.getVideoUri(),"改名")).equals("改名"));
  BookmarkCatalog deleted=c.delete(mid.getId(),source.getVideoUri());ok(s.activeName(deleted)==null);ok(s.candidate(deleted,1).equals(early));
  s.restored(late.getId(),other);ok(s.activeName(c)==null);
  s.observe(other,new LoopPoints(5.0,6.0,true));ok(s.candidate(c,1).equals(early));ok(s.activeName(c)==null);
  s.saved(mid,source,new LoopPoints(5.0,6.0,true));ok(s.activeName(c)==null);
  s.reset();ok(s.candidate(c,1)==null);
  s.observe(source,new LoopPoints());BookmarkCatalog single=new BookmarkCatalog(List.of(early));
  for(int i=0;i<20;i++){ok(s.candidate(single,1).equals(early));ok(s.candidate(single,-1).equals(early));s.restored(early.getId(),source);}
  ok(s.candidate(new BookmarkCatalog(List.of()),1)==null);
  for(ClipBookmark x:Arrays.asList(early,mid,tie,late))s.restored(x.getId(),source);
  ok(s.candidate(c,1).equals(early));ok(s.candidate(c,-1).equals(tie));
  s.observe(source,new LoopPoints(2.0,3.0,true));s.restored(early.getId(),source);
  BookmarkSnapshot snap=new BookmarkSnapshot(c,1,true,null);
  ok(!s.acceptSave(new BookmarkSaveResult(false,snap,"disk"),mid,source,new LoopPoints(5.0,6.0,true)));
  ok(s.candidate(c,1).equals(mid));ok(s.activeName(c).equals(early.getName()));
  ok(s.acceptSave(new BookmarkSaveResult(true,snap,"ok"),mid,source,new LoopPoints()));ok(s.candidate(c,1).equals(tie));ok(s.activeName(c)==null);
  ok(!s.acceptRestore(new ReviewRestoreResult.Rejected("failed"),late.getId(),source));ok(s.candidate(c,1).equals(tie));
  ok(!s.acceptRestore(new ReviewRestoreResult.Ready(8),late.getId(),source));
  s.observe(other,new LoopPoints(2.0,3.0,true));ok(!s.acceptSave(new BookmarkSaveResult(true,snap,"ok"),early,source,new LoopPoints(2.0,3.0,true)));
  ok(!s.acceptRestore(new ReviewRestoreResult.Ready(7),early.getId(),source));ok(s.activeName(c)==null);
  BookmarkHudContent portrait=BookmarkHudPolicy.INSTANCE.compose("接发球",false,false,.25,2.5,120.0);
  ok(portrait.getVisible()&&portrait.getName().equals("接发球")&&portrait.getDetail()==null);
  ok(!BookmarkHudPolicy.INSTANCE.compose(null,false,false,.25,2.5,120.0).getVisible());
  BookmarkHudContent landscape=BookmarkHudPolicy.INSTANCE.compose("正手失误",true,false,.25,2.5,120.0);
  ok(landscape.getVisible()&&landscape.getDetail().equals(PlayerInteractionPolicy.INSTANCE.hud(.25,2.5,120.0)));
  ok(BookmarkHudPolicy.INSTANCE.compose(null,false,true,1.0,0.0,120.0).getDetail()!=null);
  System.out.println("BookmarkPlaybackCheck: "+n+" assertions passed (no Android/USB)");
 }
}
