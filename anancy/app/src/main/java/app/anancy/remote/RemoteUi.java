package app.anancy.remote;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

// ============================================================================
//  Stateless view construction for the phone remote, factored out of
//  RemoteActivity so the god-class keeps only orchestration. These are PURE
//  builders: given a Context (the Activity), the shared palette (RemoteActivity
//  constants), and the caller's tap callbacks, they return a fresh, styled view.
//  No Activity state is read or written here. Moved verbatim from RemoteActivity
//  (only `this` -> ctx and the palette/withAlpha references were re-qualified).
// ============================================================================
final class RemoteUi {
    private final Context ctx;
    RemoteUi(Context ctx) { this.ctx = ctx; }

    int dp(int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }

    /** A tactile "depress" on touch: the button scales down under the finger, then springs back. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    void pressFx(final View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.91f).scaleY(0.91f).setDuration(55).start(); break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start(); break;
            }
            return false;   // don't consume — the click still fires
        });
    }

    View gridCell(int icon, final int accent, String label, final Runnable onTap, boolean big) {
        LinearLayout c = new LinearLayout(ctx); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setColor(RemoteActivity.withAlpha(accent, 0x22)); g.setCornerRadius(dp(14)); g.setStroke(dp(1), RemoteActivity.withAlpha(accent, big ? 0x88 : 0x55));
        c.setBackground(g); c.setClickable(true);
        IconView ic = new IconView(ctx, icon, 0x00000000, accent);
        ic.setClickable(false);   // decorative — let the cell receive the tap (IconView is clickable by default)
        int sz = big ? dp(27) : dp(23);
        c.addView(ic, new LinearLayout.LayoutParams(sz, sz));
        TextView t = new TextView(ctx); t.setText(label); t.setTextColor(RemoteActivity.TXT); t.setTextSize(big ? 11 : 10); t.setPadding(0, dp(3), 0, 0);
        c.addView(t);
        c.setOnClickListener(v -> onTap.run());
        pressFx(c);
        return c;
    }

    /** A clearly-tappable pill button for the playlist / library / queue chips. */
    TextView pillChip(String label, View.OnClickListener onClick) {
        TextView t = new TextView(ctx);
        t.setText(label); t.setTextColor(RemoteActivity.TXT); t.setTextSize(14); t.setAllCaps(false);
        t.setGravity(Gravity.CENTER); t.setMaxLines(1); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(10), dp(12), dp(10), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(RemoteActivity.BTN); g.setCornerRadius(dp(22));
        t.setBackground(g);
        t.setClickable(true); t.setOnClickListener(onClick);
        return t;
    }

    /** One styled checkbox row (drawn ☑/☐ box + label). */
    View checkRow(String label, boolean checked, final Runnable onTap) {
        LinearLayout r = new LinearLayout(ctx); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(11), dp(16), dp(11)); r.setClickable(true);
        TextView box = new TextView(ctx); box.setText(checked ? "☑" : "☐");
        box.setTextColor(checked ? RemoteActivity.GOOD : RemoteActivity.SUB); box.setTextSize(20); box.setPadding(0, 0, dp(12), 0);
        r.addView(box);
        TextView t = new TextView(ctx); t.setText(label); t.setTextColor(RemoteActivity.TXT); t.setTextSize(15);
        r.addView(t);
        r.setOnClickListener(v -> onTap.run());
        return r;
    }

    void hSep(LinearLayout col) {
        View d = new View(ctx); d.setBackgroundColor(0x22FFFFFF);
        col.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    Button smallBtn(String text) {
        Button b = new Button(ctx); b.setAllCaps(false); b.setText(text); b.setTextSize(12); b.setTextColor(RemoteActivity.TXT); b.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable g = new GradientDrawable(); g.setColor(RemoteActivity.BTN); g.setCornerRadius(dp(10)); b.setBackground(g);
        return b;
    }

    Button rowBtn(String text, int color) {
        Button row = new Button(ctx);
        row.setAllCaps(false); row.setText(text); row.setTextColor(color); row.setTextSize(14); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMaxLines(3);   // 2 lines cut long episode names mid-word with no ellipsis
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        GradientDrawable g = new GradientDrawable(); g.setColor(RemoteActivity.CARD); g.setCornerRadius(dp(12)); row.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(0, dp(3), 0, dp(3)); row.setLayoutParams(lp);
        return row;
    }

    TextView noteText(String s) { TextView t = new TextView(ctx); t.setText(s); t.setTextColor(RemoteActivity.SUB); t.setTextSize(12); t.setPadding(dp(6), dp(6), dp(6), dp(6)); return t; }

    EditText field(String hint, boolean password) {
        EditText e = new EditText(ctx); e.setHint(hint); e.setTextColor(RemoteActivity.TXT); e.setHintTextColor(RemoteActivity.SUB); e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        return e;
    }
}
