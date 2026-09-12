package com.kidstv.aatest;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarText;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;

/** One dead-simple screen. If this renders in the car, the app's category is accepted on this host. */
public class HelloScreen extends Screen {
    HelloScreen(CarContext ctx) { super(ctx); }

    @NonNull @Override public Template onGetTemplate() {
        CharSequence label = getCarContext().getApplicationInfo().loadLabel(getCarContext().getPackageManager());
        return new MessageTemplate.Builder(CarText.create("This surface works: " + label))
                .setTitle(label.toString())
                .setHeaderAction(Action.APP_ICON)
                .addAction(new Action.Builder().setTitle("OK").setOnClickListener(() -> {}).build())
                .build();
    }
}
