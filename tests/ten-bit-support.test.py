"""Check stream depth and codec capability decisions without a device test APK."""
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent.parent
out = root / '.codex-tmp/ten-bit-support-test'
files = {
    'android/os/Build.java': '''package android.os;
public class Build { public static class VERSION { public static int SDK_INT=19; } }''',
    'android/media/MediaCodecInfo.java': '''package android.media;
public class MediaCodecInfo {
 public String name="OMX.vendor.hevc",type="video/hevc"; public boolean encoder,broken;
 public int[] profiles={2};
 public boolean isEncoder(){return encoder;} public String getName(){return name;}
 public boolean isHardwareAccelerated(){return !name.startsWith("c2.android.");}
 public String[] getSupportedTypes(){return new String[]{type};}
 public static class CodecProfileLevel {public int profile;}
 public static class CodecCapabilities {public CodecProfileLevel[] profileLevels;}
 public CodecCapabilities getCapabilitiesForType(String type){
  if(broken)throw new IllegalArgumentException();
  CodecCapabilities c=new CodecCapabilities();c.profileLevels=new CodecProfileLevel[profiles.length];
  for(int i=0;i<profiles.length;i++){c.profileLevels[i]=new CodecProfileLevel();c.profileLevels[i].profile=profiles[i];}
  return c;
 }
}''',
    'android/media/MediaCodecList.java': '''package android.media;
public class MediaCodecList {
 public static MediaCodecInfo[] codecs={};
 public static int getCodecCount(){return codecs.length;}
 public static MediaCodecInfo getCodecInfoAt(int index){return codecs[index];}
}''',
    'xiao/bu/tv/TenBitSupportCheck.java': '''package xiao.bu.tv;
import android.media.*;
public class TenBitSupportCheck {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Boolean support(){return TenBitVideoSupport.hasHardwareDecoder("video/hevc","Main 10");}
 public static void main(String[] args){
  check(TenBitVideoSupport.isTenBit("yuv420p10le",null),"Dolby metadata without profile");
  check(TenBitVideoSupport.isTenBit("p010le",null),"Semi-planar 10 bit");
  check(TenBitVideoSupport.isTenBit(null,"Main 10"),"Profile fallback");
  check(!TenBitVideoSupport.isTenBit("yuv420p","Main 10"),"Actual 8 bit wins");
  check(!TenBitVideoSupport.isTenBit("yuv420p12le",null),"Do not mislabel 12 bit");
  check(!TenBitVideoSupport.isTenBit(null,null),"Unknown is not 10 bit");
  check(Boolean.FALSE.equals(support()),"No HEVC hardware");
  MediaCodecInfo c=new MediaCodecInfo();MediaCodecList.codecs=new MediaCodecInfo[]{c};
  check(Boolean.TRUE.equals(support()),"Main10 hardware");
  c.profiles=new int[]{1};check(Boolean.FALSE.equals(support()),"HEVC Main is only 8 bit");
  c.profiles=new int[]{4096};check(Boolean.TRUE.equals(support()),"HDR10 includes Main10");
  c.name="OMX.google.hevc.decoder";check(Boolean.FALSE.equals(support()),"Software is not hardware");
  c.name="OMX.vendor.hevc";c.profiles=new int[]{};check(support()==null,"Missing capabilities remain unknown");
  c.broken=true;check(support()==null,"Broken vendor capabilities do not crash");
  c.broken=false;c.encoder=true;check(Boolean.FALSE.equals(support()),"Encoder does not count");
  android.os.Build.VERSION.SDK_INT=14;check(support()==null,"API14 never enumerates codecs");
  check(TenBitVideoSupport.matchesProfile("video/avc","High 10",16),"AVC High10");
  check(!TenBitVideoSupport.matchesProfile("video/x-vnd.on2.vp9","Profile 3",4),"VP9 profile must match");
  System.out.println("PASS 10-bit metadata, hardware profiles, software exclusion, unknown capabilities and legacy API");
 }
}''',
    'xiao/bu/tv/TenBitVideoSupport.java': (root / 'app/src/main/java/xiao/bu/tv/TenBitVideoSupport.java').read_text(encoding='utf-8'),
}
for name, contents in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding='utf-8')
java = Path(os.environ['JAVA_HOME']) / 'bin'
subprocess.run([str(java/'javac.exe'), '-encoding', 'UTF-8', '-d', str(out)] + [str(out/name) for name in files], check=True)
subprocess.run([str(java/'java.exe'), '-cp', str(out), 'xiao.bu.tv.TenBitSupportCheck'], check=True)
