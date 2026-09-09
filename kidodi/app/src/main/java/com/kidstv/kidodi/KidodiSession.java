package com.kidstv.kidodi;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

public class KidodiSession extends Session {
    @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) {
        RemoteService.ensure(getCarContext());   // BLE-primary / wifi-fallback link
        return new KidodiScreen(getCarContext());
    }
}
