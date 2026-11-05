import io.tabletennisreview.playback.*;
import io.tabletennisreview.input.*;
public final class TourBoxButtonGestureCheck {
 static int n;static long seq;static void ok(boolean b){n++;if(!b)throw new AssertionError("gesture check "+n);}
 static TourBoxInputEvent event(boolean down,long ms){InputStamp s=new InputStamp(7,++seq,ms*1_000_000,0,42);return down?new TourBoxInputEvent.ButtonDown(TourBoxButton.TOURBOX,s):new TourBoxInputEvent.ButtonUp(TourBoxButton.TOURBOX,s);}
 static GestureDecision accept(TourBoxButtonGesture g,boolean down,long ms){return g.accept(event(down,ms),true,ms*1_000_000);}
 public static void main(String[] args){
  TourBoxButtonGesture g=new TourBoxButtonGesture();GestureDecision r=accept(g,true,0);ok(r.getDispatches().isEmpty());ok(r.getDeadlineNanos()==400_000_000L);
  ok(g.expire(399_999_999L).getDispatches().isEmpty());r=g.expire(400_000_000L);ok(r.getDispatches().size()==1&&r.getDispatches().get(0).getInput()==MappingInput.TourBox);ok(g.expire(500_000_000L).getDispatches().isEmpty());
  g.reset();TourBoxInputEvent first=event(true,0);g.accept(first,true,0);accept(g,false,100);r=accept(g,true,399);
  ok(r.getDispatches().size()==1&&r.getDispatches().get(0).getInput()==MappingInput.TourBoxDouble);ok(r.getDispatches().get(0).getEvent().getStamp().getReceivedNanos()==399_000_000L);ok(g.expire(800_000_000L).getDispatches().isEmpty());
  g.reset();accept(g,true,0);ok(accept(g,true,20).getDispatches().isEmpty());r=g.expire(400_000_000L);ok(r.getDispatches().size()==1);ok(r.getDispatches().get(0).getEvent().getStamp().getReceivedNanos()==0);
  g.reset();accept(g,true,0);accept(g,false,1);r=accept(g,true,400);ok(r.getDispatches().size()==1&&r.getDispatches().get(0).getInput()==MappingInput.TourBox);ok(g.expire(800_000_000L).getDispatches().size()==1);
  g.reset();int doubles=0,singles=0;for(int i=0;i<3;i++){for(GestureDispatch d:accept(g,true,i*50).getDispatches())if(d.getInput()==MappingInput.TourBoxDouble)doubles++;accept(g,false,i*50+1);}singles=g.expire(600_000_000L).getDispatches().size();ok(doubles==1&&singles==1);
  g.reset();doubles=0;for(int i=0;i<4;i++){for(GestureDispatch d:accept(g,true,i*50).getDispatches())if(d.getInput()==MappingInput.TourBoxDouble)doubles++;accept(g,false,i*50+1);}ok(doubles==2&&g.expire(600_000_000L).getDispatches().isEmpty());
  g.reset();r=g.accept(event(true,0),false,0);ok(r.getDispatches().size()==1&&r.getDispatches().get(0).getInput()==MappingInput.TourBox);ok(r.getDeadlineNanos()==null);
  g.reset();accept(g,true,0);g.reset();ok(g.expire(900_000_000L).getDispatches().isEmpty());
  g.reset();accept(g,true,0);r=g.accept(new TourBoxInputEvent.KnobClockwise(new InputStamp(7,++seq,1,0,0x44)),true,1);ok(r.getDispatches().get(0).getInput()==MappingInput.KnobCW);ok(g.expire(400_000_000L).getDispatches().size()==1);
  System.out.println("TourBoxButtonGestureCheck: "+n+" assertions passed (no timer/USB)");
 }
}
