package app.anancy;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

// ============================================================================
//  targetSdk 35 draws every window edge-to-edge, and nothing here consumed the
//  window insets - so the system bars sat ON TOP of the content. With 3-button
//  navigation the nav bar covered the remote's bottom tab row (Remote /
//  Playlists / Queue) and swallowed the taps; gesture navigation only hid the
//  problem because its pill is thin. This adds the bar insets to a content
//  root's OWN padding, so a screen's bottom row always clears the nav bar.
// ============================================================================
public final class SysBars {
    private SysBars() {}

    /** Pad a content root by the system bars (and any display cutout), keeping its existing padding. */
    public static void pad(final View v) {
        if (v == null) return;
        final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets in) {
                int il, it, ir, ib;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets s = in.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    il = s.left; it = s.top; ir = s.right; ib = s.bottom;
                } else {   // API 26-29: the pre-Insets accessors are deprecated but still correct
                    il = in.getSystemWindowInsetLeft(); it = in.getSystemWindowInsetTop();
                    ir = in.getSystemWindowInsetRight(); ib = in.getSystemWindowInsetBottom();
                }
                view.setPadding(l + il, t + it, r + ir, b + ib);
                return in;
            }
        });
        v.requestApplyInsets();
    }
}
