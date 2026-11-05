import io.tabletennisreview.playback.*;
import java.util.*;
public final class ClipBookmarkCheck {
 static int n;static void ok(boolean v){n++;if(!v)throw new AssertionError("bookmark assertion "+n);}
 static void rejects(Runnable r){try{r.run();throw new AssertionError("expected rejection");}catch(IllegalArgumentException expected){n++;}}
 static String id(int i){return new UUID(0,i).toString();}
 static ClipBookmark item(int i,String uri,String name){return new ClipBookmark(id(i),uri,name,2,4,.25,i);}
 static class Port implements BookmarkFilePort {
  String value;boolean fail,readFail,uncertain;int writes;
  public String read(){if(readFail)throw new IllegalStateException();return value;}
  public void write(String text){writes++;if(uncertain)throw new BookmarkCommitUncertain();if(fail)throw new IllegalStateException();value=text;}
 }
 static void storeChecks(){
  String u="content://v/1";Port port=new Port();BookmarkSaveCoordinator c=new BookmarkSaveCoordinator(port);
  ok(c.snapshot().getWritable());ok(c.snapshot().getRevision()==0);
  BookmarkMutation add=new BookmarkMutation.Add(item(5,u,"x"));port.fail=true;
  ok(!c.apply(0,add).getSuccess());ok(c.snapshot().getCatalog().getItems().isEmpty());ok(port.value==null);
  port.fail=false;ok(c.apply(0,add).getSuccess());ok(c.snapshot().getRevision()==1);int writes=port.writes;
  ok(!c.apply(0,new BookmarkMutation.Delete(id(5),u)).getSuccess());ok(port.writes==writes);
  ok(c.apply(1,new BookmarkMutation.Rename(id(5),u,"new")).getSuccess());ok(new BookmarkSaveCoordinator(port).snapshot().getCatalog().forVideo(u).get(0).getName().equals("new"));
  port.fail=true;ok(!c.apply(2,new BookmarkMutation.Delete(id(5),u)).getSuccess());ok(c.snapshot().getCatalog().getItems().size()==1);
  port.fail=false;ok(c.apply(2,new BookmarkMutation.Delete(id(5),u)).getSuccess());ok(c.snapshot().getCatalog().getItems().isEmpty());
  port.value="broken";BookmarkSaveCoordinator bad=new BookmarkSaveCoordinator(port);writes=port.writes;
  ok(!bad.snapshot().getWritable());ok(!bad.apply(0,add).getSuccess());ok(port.writes==writes&&port.value.equals("broken"));
  port.readFail=true;ok(!new BookmarkSaveCoordinator(port).snapshot().getWritable());
  Port uncertain=new Port();uncertain.uncertain=true;BookmarkSaveCoordinator un=new BookmarkSaveCoordinator(uncertain);
  ok(!un.apply(0,add).getSuccess());ok(!un.snapshot().getWritable());writes=uncertain.writes;
  ok(!un.apply(0,add).getSuccess());ok(writes==uncertain.writes);
 }
 static void sessionChecks(){
  BookmarkSource a=new BookmarkSource("content://v/1",1),b=new BookmarkSource("content://v/1",2),other=new BookmarkSource("content://v/2",1);
  ClipBookmarkSession session=new ClipBookmarkSession(a);long first=session.beginSnapshot();ok(first>0);ok(session.beginRestore()==0&&session.beginSnapshot()==0);
  ok(session.isCurrent(first,a));ok(!session.isCurrent(first,b));ok(!session.isCurrent(first,other));
  ok(!session.finish(first+1));ok(session.isCurrent(first,a));ok(session.finish(first));ok(!session.isCurrent(first,a));
  long restore=session.beginRestore();ok(restore>first);ok(session.getPhase()==BookmarkPhase.Restoring);ok(!session.isCurrent(first,a));
  session.close();session.close();ok(!session.isCurrent(restore,a));ok(session.beginRestore()==0&&session.beginSnapshot()==0);ok(!session.finish(restore));
  ClipBookmarkSession next=new ClipBookmarkSession(a);long token=next.beginRestore();ok(token!=restore);ok(!next.isCurrent(restore,a));ok(next.isCurrent(token,a));
 }
 public static void main(String[] args){
  storeChecks();sessionChecks();
  String u="content://videos/1",v="content://videos/2";
  BookmarkCatalog c=new BookmarkCatalog(Collections.emptyList());c=c.add(item(1,u,"接发球")).add(item(2,u,"接发球")).add(item(3,v,"接发球"));
  ok(c.forVideo(u).size()==2);ok(c.forVideo(v).size()==1);ok(c.forVideo(u).get(0).getId().equals(id(1)));
  ok(c.rename(id(1),u,"正手失误").forVideo(u).get(0).getName().equals("正手失误"));ok(c.forVideo(u).get(0).getName().equals("接发球"));
  ok(c.delete(id(1),u).forVideo(u).size()==1);BookmarkCatalog fixed=c;
  rejects(()->fixed.delete(id(1),v));rejects(()->fixed.rename(id(1),v,"x"));rejects(()->fixed.add(item(1,u,"x")));
  for(double speed:new double[]{.1,.25,.5,.75,1}){
   BookmarkCatalog a=new BookmarkCatalog(List.of(new ClipBookmark(id(4),u,"中文😀",0,1,speed,0)));
   BookmarkLoad load=BookmarkCodec.decode(BookmarkCodec.encode(a));ok(load.getWritable());ok(load.getCatalog().equals(a));
  }
  for(String name:new String[]{"", " ","a\nb","a\tb","x".repeat(41)})rejects(()->item(9,u,name));
  ok(item(9,u,"😀".repeat(40)).getName().codePointCount(0,80)==40);
  rejects(()->item(9,"file:///tmp/x","x"));rejects(()->new ClipBookmark("bad",u,"x",0,1,1,0));
  for(double[] ab:new double[][]{{-1,2},{2,2},{3,2},{Double.NaN,4},{0,Double.POSITIVE_INFINITY}})rejects(()->new ClipBookmark(id(1),u,"x",ab[0],ab[1],1,0));
  rejects(()->new ClipBookmark(id(1),u,"x",0,1,2,0));rejects(()->new ClipBookmark(id(1),u,"x",0,1,1,-1));
  ok(BookmarkCodec.decode(null).getWritable());ok(BookmarkCodec.decode(null).getCatalog().getItems().isEmpty());
  for(String bad:new String[]{"", "bookmarks-version=2", "bookmarks-version=1\nx", "x".repeat(2*1024*1024+1)}){BookmarkLoad l=BookmarkCodec.decode(bad);ok(!l.getWritable());ok(l.getNotice()!=null);}
  String text=BookmarkCodec.encode(c);ok(!BookmarkCodec.decode(text+"\n"+text.substring(text.indexOf('\n')+1)).getWritable());
  ArrayList<ClipBookmark> many=new ArrayList<>();for(int i=1;i<=200;i++)many.add(item(i,u,"x"));
  BookmarkCatalog full=new BookmarkCatalog(many);ok(full.forVideo(u).size()==200);rejects(()->full.add(item(201,u,"x")));
  many.clear();ok(full.getItems().size()==200);try{full.getItems().clear();throw new AssertionError("mutable catalog");}catch(UnsupportedOperationException expected){n++;}
  ArrayList<ClipBookmark> all=new ArrayList<>();for(int i=1;i<=2000;i++)all.add(item(i,"content://video/"+(i/200),"x"));
  BookmarkCatalog limit=new BookmarkCatalog(all);ok(limit.getItems().size()==2000);rejects(()->limit.add(item(2001,"content://extra","x")));
  System.out.println("ClipBookmarkCheck: "+n+" assertions passed (no Android/USB)");
 }
}
