package xiao.bu.tv;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/** Inject the service denial from the reported stack without changing device permissions. */
public final class WifiDirectStartupInstrumentation extends Instrumentation {
    private volatile Activity resumedActivity;

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (activity instanceof MainActivity) resumedActivity = activity;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static final class TestActivity extends Activity {
        String deniedPermission = "";
        Object service;
        RuntimeException lookupFailure;
        int lookups;

        TestActivity(Context context) { attachBaseContext(context); }

        @Override public int checkCallingOrSelfPermission(String permission) {
            return deniedPermission.equals(permission)
                    ? PackageManager.PERMISSION_DENIED : PackageManager.PERMISSION_GRANTED;
        }

        @Override public Object getSystemService(String name) {
            if (!Context.WIFI_P2P_SERVICE.equals(name)) return super.getSystemService(name);
            lookups++;
            if (lookupFailure != null) throw lookupFailure;
            return service;
        }
    }

    private WifiP2pManager service(final RuntimeException failure, final int[] calls)
            throws Exception {
        Class<?> binder = Class.forName("android.net.wifi.p2p.IWifiP2pManager");
        Object stub = Proxy.newProxyInstance(binder.getClassLoader(), new Class<?>[]{binder},
                (proxy, method, args) -> {
                    if ("getMessenger".equals(method.getName())) {
                        calls[0]++;
                        if (failure != null) throw failure;
                        return null; // Framework initialize() must handle an unavailable channel.
                    }
                    throw new AssertionError("Unexpected Wi-Fi operation: " + method.getName());
                });
        Constructor<WifiP2pManager> constructor = WifiP2pManager.class.getDeclaredConstructor(binder);
        constructor.setAccessible(true);
        return constructor.newInstance(stub);
    }

    private void unavailable(TestActivity activity, String expectedMessage) throws Exception {
        WifiDirectCoordinator coordinator = new WifiDirectCoordinator(activity,
                () -> { throw new AssertionError("Normal Wi-Fi permissions cannot be requested at runtime"); });
        try {
            check(!coordinator.isSupported(), "Failed initialization still advertised as usable");
            check("unavailable".equals(coordinator.stateJson().getString("state")), "Wrong fallback state");
            check(coordinator.stateJson().getString("message").contains(expectedMessage), "Missing failure detail");
            coordinator.prepareReceiver(false, "", "");
            coordinator.removeGroup();
        } finally { coordinator.close(); }
    }

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        Activity activity = null;
        int code = -1;
        try {
            check(getTargetContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
                    "Test device must advertise Wi-Fi Direct");
            Throwable[] error = new Throwable[1];
            runOnMainSync(() -> {
                try {
                    for (String permission : new String[]{Manifest.permission.CHANGE_WIFI_STATE,
                            Manifest.permission.ACCESS_WIFI_STATE}) {
                        TestActivity denied = new TestActivity(getTargetContext());
                        denied.deniedPermission = permission;
                        unavailable(denied, "权限不可用");
                        check(denied.lookups == 0, "Missing permission still accessed P2P service");
                    }
                    report.append("PASS missing Wi-Fi state permissions, fallback actions and cleanup\n");
                    RuntimeException[] failures = {
                            new SecurityException("WifiP2pService: no android.permission.CHANGE_WIFI_STATE"),
                            new IllegalStateException("Service unavailable"), null};
                    for (RuntimeException failure : failures) {
                        TestActivity denied = new TestActivity(getTargetContext());
                        int[] calls = {0};
                        denied.service = service(failure, calls);
                        unavailable(denied, failure instanceof SecurityException ? "权限被系统拒绝"
                                : failure == null ? "服务不可用" : "初始化失败");
                        check(calls[0] == 1, "Did not exercise WifiP2pManager.initialize/getMessenger");
                    }
                    report.append("PASS real framework initialize: SecurityException, service failure, null channel\n");
                    TestActivity lookup = new TestActivity(getTargetContext());
                    lookup.lookupFailure = new SecurityException("Service lookup denied");
                    unavailable(lookup, "权限被系统拒绝");
                    report.append("PASS denied service lookup\n");
                } catch (Throwable failure) { error[0] = failure; }
            });
            if (error[0] != null) throw new RuntimeException(error[0]);
            // A playing/receiving Activity may never make its UI queue idle.
            // Observe resume instead of startActivitySync's unbounded idle wait.
            getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline = SystemClock.uptimeMillis() + 10000L;
            while (resumedActivity == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50L);
            activity = resumedActivity;
            check(activity != null && !activity.isFinishing(), "MainActivity failed to start");
            Field field = MainActivity.class.getDeclaredField("wifiDirectCoordinator");
            field.setAccessible(true);
            check(field.get(activity) != null, "Startup did not initialize coordinator");
            report.append("PASS normal MainActivity startup\n");
        } catch (Throwable failure) {
            code = 0;
            report.append(android.util.Log.getStackTraceString(failure));
        } finally {
            final Activity started = activity;
            if (started != null) runOnMainSync(() -> started.finish());
        }
        result.putString("stream", report.toString());
        finish(code, result);
    }
}
