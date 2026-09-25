package xiao.bu.tv;

import android.os.Build;
import android.webkit.WebView;

/** Prevent Chromium's fullscreen focus transfer from activating an unrelated editor. */
final class WebFullscreenCompatibility {
    private WebFullscreenCompatibility() {}

    // Only guard the fullscreen transition, never normal pointer/hover delivery or
    // editor input. Some WebViews focus the first input when attaching their custom
    // fullscreen view even though the clicked HTML control is a non-focusable div.
    static final String SCRIPT = "(function(){"
            + "if(window.__ntvFullscreenFocus)return;"
            + "var pending=false,allowed=null,timer=0;"
            + "function editor(e){return e&&(e.isContentEditable||e.tagName==='TEXTAREA'||"
            + "e.tagName==='INPUT'&&!/^(hidden|button|submit|reset|checkbox|radio|range|color|file|image)$/.test(e.type));}"
            + "function clear(){pending=false;allowed=null;clearTimeout(timer);}"
            + "function begin(){clear();var a=document.activeElement;allowed=editor(a)?a:null;"
            + "pending=true;timer=setTimeout(clear,1500);}"
            + "window.__ntvFullscreenFocus={begin:begin};"
            + "function wrap(p,n){var f=p&&p[n];if(typeof f!=='function')return;"
            + "p[n]=function(){begin();"
            + "try{return f.apply(this,arguments);}catch(e){clear();throw e;}};}"
            + "document.addEventListener('focusin',function(e){"
            + "if(pending&&e.target!==allowed&&editor(e.target))e.target.blur();},true);"
            + "document.addEventListener('mousedown',clear,true);"
            + "document.addEventListener('touchstart',clear,true);"
            + "function finished(){clearTimeout(timer);timer=setTimeout(clear,0);}"
            + "document.addEventListener('fullscreenchange',finished,true);"
            + "document.addEventListener('webkitfullscreenchange',finished,true);"
            + "document.addEventListener('fullscreenerror',clear,true);"
            + "document.addEventListener('webkitfullscreenerror',clear,true);"
            + "wrap(window.Element&&Element.prototype,'requestFullscreen');"
            + "wrap(window.Element&&Element.prototype,'webkitRequestFullscreen');"
            + "wrap(window.Element&&Element.prototype,'webkitRequestFullScreen');"
            + "wrap(window.Document&&Document.prototype,'exitFullscreen');"
            + "wrap(window.Document&&Document.prototype,'webkitExitFullscreen');"
            + "wrap(window.Document&&Document.prototype,'webkitCancelFullScreen');"
            + "})();";

    static void apply(WebView view) {
        if (Build.VERSION.SDK_INT >= 19) view.evaluateJavascript(SCRIPT, null);
        else view.loadUrl("javascript:" + SCRIPT);
    }

    static void beforeNativeExit(WebView view, Runnable exit) {
        if (Build.VERSION.SDK_INT >= 19) {
            Runnable finish = new Runnable() {
                private boolean done;
                @Override public void run() {
                    if (done) return;
                    done = true;
                    view.removeCallbacks(this);
                    exit.run();
                }
            };
            // A stalled renderer must not leave the user trapped in fullscreen.
            view.postDelayed(finish, 250L);
            view.evaluateJavascript("if(window.__ntvFullscreenFocus)window.__ntvFullscreenFocus.begin();",
                    ignored -> finish.run());
        } else {
            // Older WebViews have no evaluation callback; keep Back synchronous.
            exit.run();
        }
    }
}
