package app.anancy;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The link's own black box. logcat on a busy phone holds about twenty minutes, so a failure in
 *  the car was gone by the time the phone got home. Link, sync and hotspot events also go to
 *  Android/data/app.anancy/files/link-log.txt (two files of 512 KB, oldest dropped). */
public final class Diag {
    private Diag() {}
    private static volatile File file;
    private static final long MAX = 512 * 1024;

    public static void init(Context c) {
        if (file != null || c == null) return;
        try { File d = c.getApplicationContext().getExternalFilesDir(null); if (d != null) file = new File(d, "link-log.txt"); }
        catch (Exception ignored) {}
    }

    public static void log(String tag, String msg) {
        Log.i(tag, msg);
        write(msg);
    }
    public static void warn(String tag, String msg) {
        Log.w(tag, msg);
        write("WARN " + msg);
    }
    private static synchronized void write(String msg) {
        File f = file;
        if (f == null) return;
        try {
            if (f.length() > MAX) { File old = new File(f.getPath() + ".1"); old.delete(); f.renameTo(old); }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + msg + "\n");
            }
        } catch (Exception ignored) {}
    }

    /** A command as it may appear in a log: Wi-Fi passwords, keys, the PIN and the unlock pattern
        replaced with <secret>. */
    public static String redact(String cmd) {
        if (cmd == null) return null;
        if (cmd.startsWith("wifi") || cmd.startsWith("grant:")) return cmd.substring(0, cmd.indexOf(':') + 1) + "<secret>";
        if (cmd.startsWith("cfg:anancy_pin=") || cmd.startsWith("cfg:anancy_pattern=")) return cmd.substring(0, cmd.indexOf('=') + 1) + "<secret>";
        return cmd;
    }
}
