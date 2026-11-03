import io.tabletennisreview.playback.*;
import java.io.*;
public final class ScreenshotCheck {
 static int n;
 static void ok(boolean b){n++;if(!b)throw new AssertionError("screenshot check "+n);}
 static void near(double a,double b){ok(Math.abs(a-b)<0.000001);}
 static void reject(Runnable r){boolean b=false;try{r.run();}catch(IllegalArgumentException e){b=true;}ok(b);}
 public static void main(String[] args) throws Exception {
  ShotGeometry g=new ShotGeometry(400,200,2,0,0);
  near(g.sourceAt(0,0).getX(),0.25);near(g.sourceAt(400,200).getY(),0.75);
  g=new ShotGeometry(400,200,2,100,-50);near(g.sourceAt(200,100).getX(),0.375);near(g.sourceAt(200,100).getY(),0.625);
  reject(()->new ShotGeometry(0,200,1,0,0));reject(()->new ShotGeometry(400,200,5,0,0));
  reject(()->new ShotGeometry(400,200,1,100,0));reject(()->new ImagePoint(Double.NaN,0));
  ImageFit fit=new ImageFit(300,300,200,100);ok(fit.point(150,74)==null);ok(fit.point(150,226)==null);
  near(fit.point(150,75).getX(),0.5);near(fit.point(150,75).getY(),0);near(fit.point(300,225).getY(),1);
  near(fit.getLeft(),0);near(fit.getTop(),75);near(fit.getScale(),1.5);
  AnnotationDocument d=new AnnotationDocument();ImagePoint a=new ImagePoint(.1,.2),b=new ImagePoint(.8,.9);
  ok(d.getDirty());ok(d.commit(MarkTool.Line,MarkColor.Red,a,b));ok(d.getShapes().size()==1);
  ok(d.commit(MarkTool.Arrow,MarkColor.Blue,b,a));ok(d.commit(MarkTool.Ellipse,MarkColor.Yellow,b,a));
  ok(!d.commit(MarkTool.Line,MarkColor.Red,a,a));ok(!d.commit(MarkTool.Ellipse,MarkColor.Red,a,new ImagePoint(.1,.9)));
  d.undo();ok(d.getShapes().size()==2);d.markSaved(d.getRevision());ok(!d.getDirty());
  d.clear();ok(d.getShapes().isEmpty()&&d.getDirty());d.undo();ok(d.getShapes().isEmpty());
  for(int i=0;i<200;i++)ok(d.commit(MarkTool.Line,MarkColor.Red,a,b));ok(!d.commit(MarkTool.Line,MarkColor.Red,a,b));
  ShotSessionGate gate=new ShotSessionGate();long t=gate.begin(10,400,200);ok(t>0);ok(gate.begin(10,400,200)==0);
  ok(gate.matches(t,10,400,200));ok(!gate.matches(t,11,400,200));ok(!gate.matches(t,10,200,400));
  ok(!gate.accept(t+1));ok(gate.accept(t));ok(!gate.accept(t));ok(gate.picking(t));ok(!gate.picking(t));
  ok(gate.picked(t,false));ok(gate.getPhase()==ShotPhase.Editing);ok(gate.picking(t));ok(gate.picked(t,true));
  ok(!gate.picking(t));ok(gate.finishSave(t));ok(gate.getPhase()==ShotPhase.Editing);gate.end();ok(!gate.current(t));
  long next=gate.begin(10,400,200);ok(next>t);ok(!gate.accept(t));gate.end();gate.end();ok(!gate.current(next));
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  ShotOutput.INSTANCE.write(out, stream->{try{stream.write(new byte[]{1,2,3});}catch(IOException e){throw new RuntimeException(e);}return true;});ok(out.size()==3);
  boolean failed=false;try{ShotOutput.INSTANCE.write(new ByteArrayOutputStream(){public void close() throws IOException {throw new IOException("close failed");}},stream->true);}catch(IOException e){failed=true;}ok(failed);
  failed=false;try{ShotOutput.INSTANCE.write(new ByteArrayOutputStream(),stream->false);}catch(Exception e){failed=true;}ok(failed);
  failed=false;try{ShotOutput.INSTANCE.write(new ByteArrayOutputStream(){public void flush() throws IOException{throw new IOException("flush failed");}},stream->true);}catch(IOException e){failed=true;}ok(failed);
  ShotSaveCommit commit=new ShotSaveCommit();ok(commit.commit());ok(!commit.cancel());ok(!commit.commit());
  commit=new ShotSaveCommit();ok(commit.cancel());ok(!commit.commit());
  for(int i=0;i<100;i++) { final ShotSaveCommit c=new ShotSaveCommit();
   final boolean[] r=new boolean[2];Thread one=new Thread(()->r[0]=c.commit()),two=new Thread(()->r[1]=c.cancel());
   one.start();two.start();one.join();two.join();ok(r[0]!=r[1]);
  }
  AnnotationGesture gesture=new AnnotationGesture();gesture.down(a);ok(gesture.move(b,1));ok(gesture.up(b,1).size()==2);
  ok(gesture.up(b,1).isEmpty());gesture.down(a);ok(!gesture.move(null,1));ok(!gesture.move(b,1));ok(gesture.up(b,1).isEmpty());
  gesture.down(a);ok(!gesture.move(b,2));ok(gesture.up(b,1).isEmpty());gesture.down(null);ok(gesture.up(b,1).isEmpty());
  gesture.down(a);gesture.cancel();ok(gesture.up(b,1).isEmpty());gesture.down(a);ok(gesture.up(b,2).isEmpty());
  near(new ImageFit(600,300,200,100).point(400,150).getX(),2.0/3);near(new ImageFit(300,300,200,100).point(200,150).getX(),2.0/3);
  // A MOVE can leave the image and reenter within one Android batched event.
  gesture.down(a);ok(!gesture.moveBatch(java.util.Arrays.asList(b,null,b),b,1));ok(gesture.up(b,1).isEmpty());
  gesture.down(a);ok(gesture.moveBatch(java.util.Arrays.asList(a,b),b,1));ok(gesture.up(b,1).size()==2);
  gesture.down(a);ok(!gesture.moveBatch(java.util.Arrays.asList(a),b,2));ok(gesture.up(b,1).isEmpty());
  System.out.println("ScreenshotCheck: "+n+" assertions passed; geometry/document/stale/duplicates/save-close failures; no Android capture");
 }
}
