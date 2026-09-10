package com.kidstv.kidodi;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

public class KidodiSession extends Session {
    @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) {
        // The link is on demand: hold it for as long as the car session is alive. Android 12+ can refuse
        // a foreground-service start from a bound-only process; never let that kill the car session.
        getLifecycle().addObserver(new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull androidx.lifecycle.LifecycleOwner o) { try { RemoteService.acquire(getCarContext(), "aa"); } catch (Exception ignored) {} }
            @Override public void onStop(@NonNull androidx.lifecycle.LifecycleOwner o) { try { RemoteService.release(getCarContext(), "aa"); } catch (Exception ignored) {} }
        });
        return new KidodiScreen(getCarContext());
    }
}
