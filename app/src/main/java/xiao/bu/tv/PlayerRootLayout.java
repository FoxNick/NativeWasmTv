package xiao.bu.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Player root without the platform full-screen focus tint. */
public final class PlayerRootLayout extends FrameLayout {

    public PlayerRootLayout(Context context) {
        this(context, null);
    }

    public PlayerRootLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        // The player root receives focus when menus close so remote keys keep
        // working. It is not a selectable control: Oreo's default focus highlight
        // would otherwise tint the entire playback area in mouse/remote mode.
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            setDefaultFocusHighlightEnabled(false);
        }
    }

}
