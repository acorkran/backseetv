package com.kidstv.aatest;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Trivial launcher so the APK installs and is openable on the phone. The real subject is the
 *  Android Auto surface (HelloCarAppService for template flavors, HelloMediaService for media). */
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
        t.setText(label + "\n\nAndroid Auto surface probe.\nLook for \"" + label + "\" in the car.");
        t.setTextColor(Color.WHITE); t.setTextSize(20); t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(0xFF101418);
        setContentView(t);
    }
}
