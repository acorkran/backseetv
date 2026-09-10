package com.kidstv.kidodi;

// ============================================================================
//  Tiny QR encoder (ZXing core) -> ARGB Bitmap. Used to SHOW wifi credentials /
//  connection info on one screen so another device can scan it — nobody types a
//  random-hotspot password by hand.
// ============================================================================

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.HashMap;
import java.util.Map;

final class QrCode {
    private QrCode() {}

    /** Encode payload as a QR bitmap sizePx square (black modules on white). Null on failure. */
    static Bitmap encode(String payload, int sizePx) {
        if (payload == null || payload.isEmpty() || sizePx <= 0) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);   // small quiet zone
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                int off = y * w;
                for (int x = 0; x < w; x++) px[off + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) { return null; }
    }
}
