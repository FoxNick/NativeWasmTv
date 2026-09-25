package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ListView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real player + channel entry points; temporary catalog/preferences restored afterward. */
public final class AudioSwitchInstrumentation extends Instrumentation {
    private MainActivity activity;
    private AudioArtworkView artwork;
    private String base;
    private boolean animationsOnly;
    private long assertionDelayMillis;
    private final StringBuilder report = new StringBuilder();
    private Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private void set(String name, Object value) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, value);
    }
    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(activity, args);
    }
    interface Work { void run() throws Exception; }
    private void ui(Work work) {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { failure[0]=e; } });
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }
    private void check(boolean good, String message) { if (!good) throw new AssertionError(message); }
    private void awaitChannel(int index, boolean audio) throws Exception {
        long end = SystemClock.elapsedRealtime() + 15000;
        boolean[] ready = new boolean[1];
        while (SystemClock.elapsedRealtime() < end) {
            ui(() -> ready[0] = (Integer)field(activity, "currentChannelIndex") == index && (Boolean)field(activity, "prepared"));
            if (ready[0]) break;
            SystemClock.sleep(10);
        }
        ui(() -> {
            check((Integer)field(activity,"currentChannelIndex") == index && (Boolean)field(activity,"prepared"), "Channel not prepared: " + index);
            check((Boolean)field(activity,"audioOnlyPlayback") == audio, "Wrong media type");
        });
        SystemClock.sleep(400);
        check(artwork.getVisibility() == (audio ? View.VISIBLE : View.GONE), "Outgoing record did not clear");
        if (audio) check(((View)field(artwork,"recordView")).getTranslationY() == 0f, "Incoming record did not settle");
    }
    private final class ExitFrames implements ViewTreeObserver.OnPreDrawListener {
        final int target, direction;
        boolean seen, moved;
        float lastY;
        String failure;
        ExitFrames(int target, int direction) { this.target=target; this.direction=direction; }
        @Override public boolean onPreDraw() {
            try {
                if ((Integer)field(activity,"currentChannelIndex") != target) return true;
                android.animation.ValueAnimator animation=(android.animation.ValueAnimator)field(artwork,"slideOut");
                View out=(View)field(artwork,"outgoingView");
                if (animation==null || !animation.isStarted() || !out.isShown()) return true;
                float y=out.getTranslationY();
                if (seen && (y-lastY)*direction < -.1f) moved=true;
                lastY=y; seen=true;
                if (((View)field(activity,"channelBar")).getVisibility()==View.VISIBLE)
                    failure="channel card covered the transition";
            } catch (Exception error) { failure=error.toString(); }
            return true;
        }
    }
    private void expectSwitch(int target, boolean audio, int direction, String label, Work trigger) throws Exception {
        // Observe rendered frames before input. The 360 ms animator is cleared
        // on completion, so inspecting it only after input/polling can falsely fail.
        ExitFrames frames=new ExitFrames(target,direction);
        ui(() -> artwork.getViewTreeObserver().addOnPreDrawListener(frames));
        try {
            trigger.run();
            if (assertionDelayMillis>0) SystemClock.sleep(assertionDelayMillis);
            long end=SystemClock.elapsedRealtime()+1500;
            boolean[] observed={false};
            do {
                ui(() -> observed[0]=frames.moved || frames.failure!=null);
                if (observed[0]) break;
                SystemClock.sleep(10);
            } while (SystemClock.elapsedRealtime()<end);
            ui(() -> {
                check((Integer)field(activity,"currentChannelIndex")==target,label+": channel did not switch");
                check(frames.failure==null,label+": "+frames.failure);
                check(frames.seen,label+": no visible exit animation frames");
                check(frames.moved,label+": outgoing record did not move in the expected direction");
                if (assertionDelayMillis>0)
                    report.append(label).append(": observed exit; animator cleared at delayed check=")
                            .append(field(artwork,"slideOut")==null).append('\n');
            });
        } finally {
            ui(() -> artwork.getViewTreeObserver().removeOnPreDrawListener(frames));
        }
        if (label.equals("channel list selection")) ui(() -> {
            View root=(View)field(activity,"root");
            Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(image));
            try(java.io.FileOutputStream file=activity.openFileOutput("record-slide.png",0)) {
                image.compress(Bitmap.CompressFormat.PNG,100,file);
            }
            image.recycle();
        });
        awaitChannel(target, audio);
        check(((View)field(activity,"channelBar")).getVisibility()==View.VISIBLE,
                label + ": channel card missing after transition");
        check(field(artwork,"slideIn") == null, label + ": completed animator retained");
        report.append("PASS ").append(label).append('\n');
    }
    private void key(int key) {
        ui(() -> {
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));
        });
    }
    private void swipe(boolean up) throws Exception {
        View root=(View)field(activity,"root");
        long down=SystemClock.uptimeMillis();
        for(int i=0;i<=12;i++) {
            final int step=i;
            ui(() -> {
                MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),step==0?MotionEvent.ACTION_DOWN:step==12?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,
                        root.getWidth()*.8f,root.getHeight()*(up ? .75f-step*.04f : .25f+step*.04f),0);
                event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                activity.dispatchTouchEvent(event);event.recycle();
            });
            SystemClock.sleep(16);
        }
    }
    private void cacheTiming() throws Exception {
        AlbumArtLoader loader = new AlbumArtLoader();
        try {
            Bitmap[] seen = new Bitmap[1];
            CountDownLatch warm = new CountDownLatch(1);
            ui(() -> loader.load(activity, null, null, base + "cover.png", art -> {seen[0]=art;warm.countDown();}));
            check(warm.await(6, TimeUnit.SECONDS) && seen[0] != null, "Cover cache warmup failed");
            CountDownLatch first = new CountDownLatch(1);
            ui(() -> loader.load(activity, base + "slow-id3.mp3", null, base + "cover.png", art -> first.countDown()));
            check(first.await(1,TimeUnit.SECONDS), "Logo blocked by ID3 probe");
            SystemClock.sleep(200); // Leave an obsolete stream read in flight.
            CountDownLatch hit = new CountDownLatch(1);
            boolean[] same = new boolean[1];
            long start = SystemClock.elapsedRealtime();
            ui(() -> loader.load(activity, null, null, base + "cover.png", art -> {
                same[0] = art == seen[0] && !art.isRecycled(); hit.countDown();
            }));
            check(hit.await(1500,TimeUnit.MILLISECONDS), "Cached logo queued behind old ID3 request");
            check(same[0], "Decoded cache missed or recycled");
            report.append("Cached logo during stalled ID3: ").append(SystemClock.elapsedRealtime()-start).append(" ms\n");
        } finally { ui(loader::close); }
    }
    private void shortSwipeReturns(boolean up, boolean cancel) throws Exception {
        View root=(View)field(activity,"root");
        int channel=(Integer)field(activity,"currentChannelIndex");
        int request=(Integer)field(activity,"playRequestId");
        long down=SystemClock.uptimeMillis();
        for(int step=0;step<=5;step++) {
            final int i=step;
            ui(() -> {
                MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),i==0?MotionEvent.ACTION_DOWN:
                        i==5?(cancel?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP):MotionEvent.ACTION_MOVE,
                        root.getWidth()*.8f,root.getHeight()*(.5f+(up?-1:1)*i*.018f),0);
                event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                activity.dispatchTouchEvent(event);event.recycle();
                if(i==4) check(((View)field(artwork,"recordView")).getTranslationY()!=0f,
                        "Short swipe did not exercise record preview");
            });
            SystemClock.sleep(16);
        }
        SystemClock.sleep(350);
        ui(() -> {
            check((Integer)field(activity,"currentChannelIndex")==channel
                    && (Integer)field(activity,"playRequestId")==request,"Short swipe switched playback");
            check(((View)field(artwork,"recordView")).getTranslationY()==0f,"Short swipe failed to return to center");
        });
        report.append("PASS short swipe ").append(up?"up":"down").append(cancel?" cancelled":" released").append('\n');
    }
    private void handoffTiming() throws Exception {
        ui(() -> {
            invoke("releasePlayer",new Class<?>[0]); invoke("closeWebSource",new Class<?>[0]);
            artwork.show("交接测试 A",true);
        });
        SystemClock.sleep(100);
        ui(() -> {
            artwork.beginChannelSwitch(1); artwork.clear();
            invoke("showChannelBar",new Class<?>[]{String.class,String.class},"交接测试 B","等待交接");
        });
        SystemClock.sleep(900); // Longer than the old independent exit animation.
        ui(() -> {
            View out=(View)field(artwork,"outgoingView");
            check(artwork.getVisibility()==View.VISIBLE && out.getVisibility()==View.VISIBLE,
                    "Slow preparation left an empty background");
            check(Math.abs(out.getTranslationY()) < artwork.getHeight()*.2f,"Outgoing disc left too early");
            check(((View)field(activity,"channelBar")).getVisibility()!=View.VISIBLE,
                    "Card appeared while waiting for incoming disc");
            float before=out.getTranslationY();
            artwork.show("交接测试 B",true);
            check(out.getTranslationY()==before,"Handoff jumped back to origin");
            check(field(artwork,"slideOut")==field(artwork,"slideIn"),"Separate animation clocks");
        });
        for(int i=0;i<4;i++) {
            SystemClock.sleep(45);
            ui(() -> {
                View out=(View)field(artwork,"outgoingView"), in=(View)field(artwork,"recordView");
                android.graphics.RectF disc=(android.graphics.RectF)field(artwork,"disc");
                float distance=Math.max(disc.height()+Math.max(2f,artwork.getHeight()*.025f),disc.bottom+1f);
                check(Math.abs(in.getTranslationY()-out.getTranslationY()-distance)<1f,
                        "Record spacing changed during handoff");
            });
        }
        ui(() -> { artwork.beginChannelSwitch(-1); artwork.clear(); });
        SystemClock.sleep(200);
        ui(() -> {
            check(((View)field(artwork,"outgoingView")).getVisibility()==View.VISIBLE,"Rapid reversal lost both records");
            artwork.show("交接测试 C",true);
        });
        SystemClock.sleep(450);
        ui(() -> {
            check(((View)field(artwork,"recordView")).getTranslationY()==0f,"Reversed handoff failed to settle");
            check(((View)field(artwork,"outgoingView")).getVisibility()==View.GONE,"Outgoing record leaked");
            artwork.beginChannelSwitch(1); artwork.clear(); artwork.finishChannelSwitch();
        });
        SystemClock.sleep(450);
        check(artwork.getVisibility()==View.GONE,"Exit-only transition did not finish");
        report.append("PASS slow load retains disc; shared clock/fixed spacing; rapid reverse; exit only\n");
    }
    private void neighborPreviewTiming() throws Exception {
        ui(() -> artwork.show("预览 A",true));
        SystemClock.sleep(80);
        Bitmap art=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
        art.eraseColor(0xff348ad3);
        ui(() -> {
            artwork.previewSlide(-artwork.getHeight()*.30f,true,"next-logo",null);
            View next=(View)field(artwork,"neighborView");
            check(next.getVisibility()==View.VISIBLE,"Missing empty neighbor record during drag");
            android.graphics.RectF disc=(android.graphics.RectF)field(artwork,"disc");
            check(disc.top+next.getTranslationY()<disc.bottom,"Neighbor is still outside the visible record area");
            float before=next.getTranslationY();
            artwork.updateNeighborCover("next-logo",art);
            check(next.getTranslationY()==before,"Downloaded cover restarted drag preview");
            artwork.beginChannelSwitch(1);
            artwork.showPending("预览 B","next-logo",art);
            View incoming=(View)field(artwork,"recordView");
            check(Math.abs(incoming.getTranslationY()-before)<1f,"Commit jumped away from finger position");
            artwork.clear(true); artwork.clear(true); // Catalog switch and subsequent player creation.
            check(artwork.getVisibility()==View.VISIBLE,"Player release erased placeholder");
        });
        SystemClock.sleep(450);
        ui(() -> {
            check(((View)field(artwork,"recordView")).getTranslationY()==0f,"Placeholder waited for decoder readiness");
            Object animation=field(artwork,"slideIn");
            artwork.show("预览 B",true,art);
            check(field(artwork,"slideIn")==animation,"Prepared callback restarted completed slide");
            artwork.previewSlide(artwork.getHeight()*.08f,true,"previous-logo",null);
            artwork.restoreSlide();
        });
        SystemClock.sleep(240);
        ui(() -> {
            artwork.resetSlide();
            check(((View)field(artwork,"recordView")).getTranslationY()==0f,"Preview rebound failed");
            check(((View)field(artwork,"neighborView")).getVisibility()==View.GONE,"Preview survived rebound");
            artwork.previewSlide(-artwork.getHeight()*.3f,false,"",null);
            check(((View)field(artwork,"neighborView")).getVisibility()==View.GONE,"Video target got a placeholder record");
            artwork.resetSlide(); artwork.beginChannelSwitch(1); artwork.finishChannelSwitch();
            artwork.clear(true); artwork.clear(true);
            check(artwork.isTransitionRunning(),"Video player creation cancelled the outgoing animation");
        });
        SystemClock.sleep(450);
        check(artwork.getVisibility()==View.GONE,"Outgoing-only animation leaked into video");
        report.append("PASS immediate neighbor placeholder, late cover, continuous commit, delayed decoder, rebound and video exclusion\n");
    }
    private void interruptedSlides() throws Exception {
        ui(() -> {
            invoke("releasePlayer",new Class<?>[0]); invoke("closeWebSource",new Class<?>[0]);
            artwork.clear(); artwork.show("连续 A",true);
        });
        for (int i=0; i<5; i++) {
            final int step=i;
            ui(() -> { artwork.beginChannelSwitch(1); artwork.showPending("连续 "+step,"",null); });
            SystemClock.sleep(45);
            ui(() -> {
                View current=(View)field(artwork,"recordView"), out=(View)field(artwork,"outgoingView");
                float before=current.getTranslationY(), outBefore=out.getTranslationY();
                float delta=-artwork.getHeight()*.12f;
                artwork.previewSlide(delta,true,"",null);
                check(Math.abs(current.getTranslationY()-before-delta)<1f,"New drag snapped incoming record to center");
                check(out.getVisibility()==View.VISIBLE && Math.abs(out.getTranslationY()-outBefore-delta)<1f,
                        "New drag discarded outgoing record");
                artwork.previewSlide(delta*2,true,"",null);
                check(Math.abs(current.getTranslationY()-before-delta*2)<1f,"Drag accumulated absolute samples");
                android.graphics.RectF disc=(android.graphics.RectF)field(artwork,"disc");
                boolean visible=false;
                for(String name:new String[]{"recordView","outgoingView","trailingView","neighborView"}) {
                    android.widget.ImageView v=(android.widget.ImageView)field(artwork,name);
                    visible |= v.getVisibility()==View.VISIBLE && v.getDrawable()!=null
                            && disc.bottom+v.getTranslationY()>0 && disc.top+v.getTranslationY()<disc.bottom;
                }
                check(visible,"Fast flicks left every record offscreen");
            });
        }
        ui(() -> artwork.restoreSlide());
        SystemClock.sleep(240);
        ui(() -> {
            check(!artwork.isTransitionRunning(),"Interrupted slides never settled");
            check(((View)field(artwork,"recordView")).getTranslationY()==0f,"Interrupted slides did not rebound");
            check(((View)field(artwork,"trailingView")).getVisibility()==View.GONE,"Old record tail leaked");
        });
        report.append("PASS five interrupted slides preserve positions and rebound\n");
    }

    private void continuousLoadingSwipes() throws Exception {
        Channel[] slow=new Channel[4];
        for(int i=0;i<slow.length;i++) slow[i]=new Channel(""+i,"连续电台 "+i,"",
                base+"slow-id3.mp3?continuous="+i,null,null);
        ui(() -> {
            invoke("releasePlayer",new Class<?>[0]); invoke("closeWebSource",new Class<?>[0]);
            invoke("closeChannelList",new Class<?>[0]);
            ((View)field(activity,"managementPanel")).setVisibility(View.GONE);
            set("currentGroupIndex",0); set("browsingGroupIndex",0); set("artworkGroupIndex",-1);
            ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("连续切台",ChannelCatalog.SOURCE_CUSTOM,slow)};
            invoke("switchChannel",new Class<?>[]{int.class},0);
            artwork.showPending(slow[0].name,"",null);
        });
        for(int i=1;i<=3;i++) {
            final int index=i;
            swipe(true);
            ui(() -> {
                check((Integer)field(activity,"currentChannelIndex")==index,"Continuous swipe lost channel "+index);
                check(!(Boolean)field(activity,"audioOnlyPlayback"),"Slow fixture prepared before loading test");
                check(!(Boolean)field(activity,"channelSwitchAnimating"),"Loading audio entered video switch path");
                check(artwork.hasPendingPresentation() && artwork.isTransitionRunning(),"Continuous swipe lost record animation");
                check(((View)field(activity,"channelBar")).getVisibility()!=View.VISIBLE,"Card interrupted continuous gesture");
            });
        }
        SystemClock.sleep(450);
        ui(() -> {
            check(((View)field(artwork,"recordView")).getTranslationY()==0f,"Final record did not settle before audio readiness");
            check(((View)field(artwork,"outgoingView")).getVisibility()==View.GONE,"Previous record leaked after continuous swipes");
        });
        report.append("PASS three consecutive swipes during slow audio preparation\n");
    }

    @Override public void onCreate(Bundle args) {
        animationsOnly = args != null && "true".equals(args.getString("animationsOnly"));
        assertionDelayMillis=args==null?0:Math.max(0,Math.min(1000,
                Long.parseLong(args.getString("assertionDelayMs","0"))));
        base = args == null ? null : args.getString("base");
        if (base == null) base = "http://127.0.0.1:19981/";
        super.onCreate(args); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = -1;
        ChannelCatalog.Group[] previous = null;
        SharedPreferences prefs = getTargetContext().getSharedPreferences(MainActivity.PREFERENCES,0);
        Map<String,?> saved = new java.util.HashMap<>(prefs.getAll());
        try {
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1200);
            artwork=(AudioArtworkView)field(activity,"audioArtwork");
            if (animationsOnly) {
                interruptedSlides();
            } else {
            cacheTiming();
            handoffTiming();
            neighborPreviewTiming();
            previous=ChannelCatalog.GROUPS;
            interruptedSlides();
            continuousLoadingSwipes();
            Channel[] channels = new Channel[] {
                new Channel("1","唱片 A","",base+"plain-audio.mp3?a",null,null).withLogo(base+"cover.png"),
                new Channel("2","唱片 B","",base+"covered.mp3",null,null),
                new Channel("3","视频","",base+"video.mp4",null,null)
            };
            ui(() -> {
                invoke("releasePlayer",new Class<?>[0]); invoke("closeWebSource",new Class<?>[0]);
                invoke("closeChannelList",new Class<?>[0]);
                ((View)field(activity,"managementPanel")).setVisibility(View.GONE);
                set("reverseUpDown",false); set("currentGroupIndex",0);set("browsingGroupIndex",0);
                set("artworkGroupIndex",-1);
                ChannelCatalog.GROUPS=new ChannelCatalog.Group[]{new ChannelCatalog.Group("唱片测试",ChannelCatalog.SOURCE_CUSTOM,channels)};
                invoke("switchChannel",new Class<?>[]{int.class},0);
            });
            awaitChannel(0,true);
            shortSwipeReturns(true,false);
            shortSwipeReturns(false,false);
            shortSwipeReturns(true,true);
            shortSwipeReturns(false,true);
            Bitmap baseTexture=(Bitmap)field(artwork,"vinyl");
            expectSwitch(1,true,1,"car media next",() -> key(KeyEvent.KEYCODE_MEDIA_NEXT));
            expectSwitch(0,true,-1,"car media previous",() -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
            expectSwitch(1,true,1,"D-pad down",() -> key(KeyEvent.KEYCODE_DPAD_DOWN));
            expectSwitch(0,true,-1,"D-pad up",() -> key(KeyEvent.KEYCODE_DPAD_UP));
            expectSwitch(1,true,1,"right-side swipe up",() -> swipe(true));
            expectSwitch(0,true,-1,"right-side swipe down",() -> swipe(false));
            key(KeyEvent.KEYCODE_MEDIA_NEXT);
            awaitChannel(1,true);
            expectSwitch(0,true,-1,"channel list selection",() -> ui(() -> {
                invoke("openChannelList",new Class<?>[0]);
                ListView list=(ListView)field(activity,"channelList");
                list.performItemClick(list.getChildAt(0),0,0);
            }));
            check(field(artwork,"vinyl")==baseTexture,"Vinyl base decoded again");
            expectSwitch(2,false,1,"audio to video: exit only",() -> ui(() -> {
                invoke("openChannelList",new Class<?>[0]);
                ListView list=(ListView)field(activity,"channelList");
                list.performItemClick(list.getChildAt(2),2,2);
            }));
            report.append("PASS base texture reused, video has no record\n");
            }
        } catch(Throwable error) {code=0;report.append(android.util.Log.getStackTraceString(error));}
        finally {
            final ChannelCatalog.Group[] restore=previous;
            if(activity!=null) ui(() -> {
                invoke("cancelPendingRelativeSwitch",new Class<?>[0]);
                invoke("releasePlayer",new Class<?>[0]);
                if(restore!=null) ChannelCatalog.GROUPS=restore;
                activity.finish();
            });
            waitForIdleSync();
            SharedPreferences.Editor editor=prefs.edit().clear();
            for(Map.Entry<String,?> entry:saved.entrySet()) {
                String k=entry.getKey();Object v=entry.getValue();
                if(v instanceof String)editor.putString(k,(String)v);
                else if(v instanceof Integer)editor.putInt(k,(Integer)v);
                else if(v instanceof Boolean)editor.putBoolean(k,(Boolean)v);
                else if(v instanceof Long)editor.putLong(k,(Long)v);
                else if(v instanceof Float)editor.putFloat(k,(Float)v);
                else if(v instanceof java.util.Set)editor.putStringSet(k,(java.util.Set<String>)v);
            }
            editor.commit();
        }
        result.putString("stream",report.toString()); finish(code,result);
    }
}
