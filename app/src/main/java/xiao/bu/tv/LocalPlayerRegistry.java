package xiao.bu.tv;

import java.lang.ref.WeakReference;

/** Same-process management-page input bridge; no capture service or sender lease. */
final class LocalPlayerRegistry {
    static final String OPEN_MANAGEMENT = "xiao.bu.tv.OPEN_MANAGEMENT";
    private static WeakReference<MainActivity> owner = new WeakReference<MainActivity>(null);
    static void attach(MainActivity activity) { owner = new WeakReference<MainActivity>(activity); }
    static void detach(MainActivity activity) { if (owner.get() == activity) owner.clear(); }
    static MainActivity localInputOwner() { return owner.get(); }
}
