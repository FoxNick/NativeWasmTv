package xiao.bu.tv;

/** Platform and target-SDK permission contracts; no new framework references on old TVs. */
final class CastPermissionPolicy {
    static String directPermission(int sdk, int target) {
        if (sdk < 23) return "";
        return sdk >= 33 && target >= 33
                ? "android.permission.NEARBY_WIFI_DEVICES"
                : "android.permission.ACCESS_FINE_LOCATION";
    }

    static boolean requiresLocalNetworkPermission(int sdk, int target) {
        // Android 17 applies local-network protection to installed apps even
        // when they retain a legacy target SDK for old TV compatibility.
        return sdk >= 37;
    }
}
