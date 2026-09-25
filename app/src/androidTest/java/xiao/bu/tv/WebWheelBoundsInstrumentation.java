package xiao.bu.tv;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Measures DOM and native offsets independently; no external website required. */
public final class WebWheelBoundsInstrumentation extends Instrumentation {
    private WebView web;
    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private String js(String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1); String[] result = {null};
        runOnMainSync(() -> web.evaluateJavascript(script, value -> { result[0] = value; done.countDown(); }));
        if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("JS timeout");
        return result[0];
    }
    private String dimensions() throws Exception {
        String dom = js("JSON.stringify({y:scrollY,h:innerHeight,client:document.documentElement.clientHeight,"
                + "content:document.documentElement.scrollHeight,body:document.body.scrollHeight,"
                + "pane:document.getElementById('pane')?document.getElementById('pane').scrollTop:null})");
        String[] nativeSize = {null};
        runOnMainSync(() -> nativeSize[0] = " nativeY=" + web.getScrollY() + " height=" + web.getHeight()
                + " content=" + web.getContentHeight() + " scale=" + web.getScale());
        return dom + nativeSize[0];
    }
    private void touch(int action, float y, long down) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                web.getWidth() / 2f, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(event); event.recycle();
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = -1; StringBuilder report = new StringBuilder();
        try {
            MainActivity activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1500);
            WebSourceView source = (WebSourceView) field(activity, "webSourceView");
            runOnMainSync(() -> { source.setListener(null); source.open(91001, "about:blank"); });
            web = (WebView) field(source, "webView");
            FlyMouseCursorView cursor = (FlyMouseCursorView) field(activity, "flyMouseCursor");
            Method scroll = MainActivity.class.getDeclaredMethod("handleRemoteScroll", int.class, int.class);
            scroll.setAccessible(true);
            Method closeList = MainActivity.class.getDeclaredMethod("closeChannelList"); closeList.setAccessible(true);
            runOnMainSync(() -> { try { closeList.invoke(activity); } catch (Exception e) { throw new RuntimeException(e); } });
            String[] fixtures = {
                "<style>body{margin:0}#end{height:2400px;background:linear-gradient(red,blue)}</style><div id=end>root</div>",
                "<style>html,body{margin:0;height:100%;overflow:hidden}#pane{height:100%;overflow:auto}#end{height:2400px;background:linear-gradient(red,blue)}</style><div id=pane><div id=end>nested</div></div>",
                "<style>html,body{margin:0;height:100%;overflow:hidden}#end{height:2400px;background:linear-gradient(red,blue)}</style><div id=end>locked root</div>"
            };
            for (int index = 0; index < fixtures.length; index++) {
                final String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'></head><body>" + fixtures[index] + "</body></html>";
                runOnMainSync(() -> web.loadDataWithBaseURL("https://wheel-fixture.invalid/", html, "text/html", "UTF-8", null));
                SystemClock.sleep(1000);
                report.append("fixture ").append(index).append(" initial ").append(dimensions()).append('\n');
                // Cursor starts in the page centre, below browser chrome.
                runOnMainSync(() -> cursor.moveBy(web.getWidth() / 2f - cursor.cursorX(), source.getHeight() / 2f - cursor.cursorY()));
                for (int i = 0; i < 20; i++) {
                    runOnMainSync(() -> { try { scroll.invoke(activity, 0, 500); } catch (Exception e) { throw new RuntimeException(e); } });
                    SystemClock.sleep(40);
                }
                SystemClock.sleep(300);
                report.append("wheel ").append(dimensions()).append('\n');
                js("scrollTo(0,0);if(document.getElementById('pane'))document.getElementById('pane').scrollTop=0");
                SystemClock.sleep(100);
                for (int gesture = 0; gesture < 12; gesture++) {
                    final long down = SystemClock.uptimeMillis();
                    runOnMainSync(() -> touch(MotionEvent.ACTION_DOWN, web.getHeight() * .8f, down));
                    for (int step = 1; step <= 6; step++) {
                        final float fraction = .8f - step * .1f;
                        SystemClock.sleep(20);
                        runOnMainSync(() -> touch(MotionEvent.ACTION_MOVE, web.getHeight() * fraction, down));
                    }
                    runOnMainSync(() -> touch(MotionEvent.ACTION_UP, web.getHeight() * .2f, down));
                    SystemClock.sleep(60);
                }
                SystemClock.sleep(300);
                report.append("touch ").append(dimensions()).append('\n');
            }
            runOnMainSync(source::closePage);
        } catch (Throwable error) { code = 0; report.append(android.util.Log.getStackTraceString(error)); }
        result.putString("stream", report.toString()); finish(code, result);
    }
}
