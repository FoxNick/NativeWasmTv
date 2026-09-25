"""Exercise the real VOD reconnect/prepare code without installing a test APK."""
import os, subprocess
from pathlib import Path
root=Path(__file__).resolve().parent.parent
source=(root/'app/src/main/java/xiao/bu/tv/MainActivity.java').read_text(encoding='utf-8')
a=source.index('    private void restartPlayerPreservingPosition(')
b=source.index('    private void resetPlaybackRecoveryState()',a)
method=source[a:b]
java='''import java.io.IOException;
public class VodReconnectCheck {
 static final String TAG="test";
 static class Log {static void i(String t,String s){}}
 static class Channel {}
 static class IjkMediaPlayer {
  long duration,position,seek=-1; boolean paused;
  IjkMediaPlayer(long d,long p){duration=d;position=p;}
  long getDuration(){return duration;} long getCurrentPosition(){return position;}
  void seekTo(long p){seek=p;} void pause(){paused=true;}
 }
 IjkMediaPlayer player;
 String directHttpMediaUrl; boolean fail, deferred;
 void startIjkPlayer(Channel c,String u,boolean s,boolean direct,int[] tracks,long initialPositionMs)throws IOException {
  if(fail)throw new IOException("network");
  if(deferred)return;
  player=new IjkMediaPlayer(600000,0);player.seek=initialPositionMs;
 }
 METHOD

 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  VodReconnectCheck c=new VodReconnectCheck();
  c.player=new IjkMediaPlayer(600000,67321);IjkMediaPlayer old=c.player;
  c.restartPlayerPreservingPosition(new Channel(),"url",false);
  check(c.player!=old,"Bind to replacement player");
  check(old.seek==-1,"Old player must not receive the new initial seek");
  check(c.player.seek==67321,"VOD must resume beyond minute one");
  c.startIjkPlayer(new Channel(),"next-channel",false,false,null,0);
  check(c.player.seek==0,"A later channel must not inherit recovery position");
  c.player=new IjkMediaPlayer(0,70000);
  c.restartPlayerPreservingPosition(new Channel(),"live",false);
  check(c.player.seek==0,"Live streams must not seek");
  c.player=new IjkMediaPlayer(75560,90000);
  c.restartPlayerPreservingPosition(new Channel(),"url",false);
  check(c.player.seek==74560,"Clamp stale clock before end of film");
  c.player=new IjkMediaPlayer(600000,-1);
  c.restartPlayerPreservingPosition(new Channel(),"url",false);
  check(c.player.seek==0,"Invalid position must not seek");
  c.player=new IjkMediaPlayer(600000,80000);old=c.player;c.deferred=true;
  c.restartPlayerPreservingPosition(new Channel(),"url",false);
  check(c.player==old&&old.seek==-1,"Deferred surface start must not seek old instance");
  c.deferred=false;c.fail=true;old=c.player;
  try {c.restartPlayerPreservingPosition(new Channel(),"url",false);throw new AssertionError("Expected IOException");}
  catch(IOException expected){}
  check(c.player==old&&old.seek==-1,"Failed restart must not schedule stale seek");
  System.out.println("PASS VOD initial seek, live, end clamp, invalid clock, old instance, deferred/failed restart");
 }
}'''.replace('METHOD',method)
out=root/'.codex-tmp/vod-reconnect-test';out.mkdir(parents=True,exist_ok=True)
(out/'VodReconnectCheck.java').write_text(java,encoding='utf-8')
java_bin=Path(os.environ['JAVA_HOME'])/'bin'
subprocess.run([str(java_bin/'javac.exe'),'-encoding','UTF-8',str(out/'VodReconnectCheck.java')],check=True)
subprocess.run([str(java_bin/'java.exe'),'-cp',str(out),'VodReconnectCheck'],check=True)
