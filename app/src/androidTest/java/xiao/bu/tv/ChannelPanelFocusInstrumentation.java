package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Exercise the real menu timeout in non-touch mode and compare the root overlay. */
public final class ChannelPanelFocusInstrumentation extends Instrumentation {
    private MainActivity activity;
    private View root;
    private Object field(String name) throws Exception {
        Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity);
    }
    private void invoke(String name) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(activity);
    }
    private interface Work { void run() throws Exception; }
    private void ui(Work work) {
        Throwable[] failure=new Throwable[1];
        runOnMainSync(() -> { try { work.run(); } catch(Throwable e) { failure[0]=e; } });
        if(failure[0]!=null) throw new RuntimeException(failure[0]);
    }
    private void check(boolean valid,String message) { if(!valid) throw new AssertionError(message); }
    private int capture(String name) throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        int color=bitmap.getPixel(root.getWidth()/2,root.getHeight()/20);
        try(java.io.FileOutputStream out=activity.openFileOutput(name,0)) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }
        bitmap.recycle();
        return color;
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle(); int code=-1;
        try {
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1000);
            setInTouchMode(false);
            ui(() -> {
                invoke("releasePlayer"); invoke("closeWebSource"); invoke("hideLoading");
                root=(View)field("root");
                ((View)field("channelBar")).setVisibility(View.GONE);
                ((AudioArtworkView)field("audioArtwork")).show("频道列表焦点测试",true);
                if(Build.VERSION.SDK_INT>=26) {
                    check(!root.getDefaultFocusHighlightEnabled(),"Player root still enables default focus highlight");
                    root.setDefaultFocusHighlightEnabled(true); // Reproduce the previous behavior.
                }
                invoke("openChannelList");
            });
            SystemClock.sleep(5600);
            int[] colors=new int[2];
            ui(() -> {
                check(((View)field("channelListPanel")).getVisibility()==View.GONE,"Menu timeout did not hide the panel");
                check(root.isFocused(),"Root did not regain keyboard focus");
                colors[0]=capture("panel-focus-before.png");
                if(Build.VERSION.SDK_INT>=26) root.setDefaultFocusHighlightEnabled(false);
                invoke("openChannelList");
            });
            SystemClock.sleep(5600);
            ui(() -> {
                check(((View)field("channelListPanel")).getVisibility()==View.GONE,"Fixed menu did not auto-hide");
                check(root.isFocused(),"Fix broke keyboard focus");
                colors[1]=capture("panel-focus-after.png");
                if(Build.VERSION.SDK_INT>=26) check(colors[0]!=colors[1],"Could not reproduce the default highlight tint");
                invoke("openChannelList");
                check(((View)field("channelListPanel")).getVisibility()==View.VISIBLE,"Menu cannot reopen");
                invoke("closeChannelList");
            });
            result.putString("stream","PASS menu timeout, root focus, overlay comparison and reopening; API="
                    +Build.VERSION.SDK_INT+" before="+Integer.toHexString(colors[0])+" after="+Integer.toHexString(colors[1])+"\n");
        } catch(Throwable error) {
            code=0; result.putString("stream",android.util.Log.getStackTraceString(error));
        } finally {
            if(activity!=null) ui(() -> {
                if(root!=null && Build.VERSION.SDK_INT>=26) root.setDefaultFocusHighlightEnabled(false);
                activity.finish();
            });
        }
        finish(code,result);
    }
}
