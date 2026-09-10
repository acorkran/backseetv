package com.kidstv.kidodi;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

public class KidodiSession extends Session {
    @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) {
        // BLE-primary / wifi-fallback link. Android 12+ can refuse a foreground-service start from a
        // bound-only process; never let that kill the car session (the phone remote may run it anyway).
        try { RemoteService.ensure(getCarContext()); } catch (Exception ignored) {}
        return new KidodiScreen(getCarContext());
    }
}
