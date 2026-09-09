package com.kidstv.kidodi;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

// Android Auto CAR APP (not a media app) -> shows in the app launcher, IOT
// category, and never competes for the media slot with Spotify/Samsung Music.
public class KidodiCarAppService extends CarAppService {
    @NonNull @Override public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;   // dev; tighten for public release
    }
    @NonNull @Override public Session onCreateSession() { return new KidodiSession(); }
}
