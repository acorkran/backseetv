package com.kidstv.aatest;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/** Template-surface entry point. The manifest's intent-filter category (per flavor) is what we're
 *  testing — whether projected Android Auto surfaces a sideloaded app of that category. */
public class HelloCarAppService extends CarAppService {
    @NonNull @Override public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;   // dev
    }
    @NonNull @Override public Session onCreateSession() { return new HelloSession(); }
}
