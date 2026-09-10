package com.kidstv.kidodi.wear;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

/** Woken by Play services whenever the phone publishes a watch build — even if this app isn't running. */
public class WearUpdateService extends WearableListenerService {
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent e : events) {
            if (e.getType() != DataEvent.TYPE_CHANGED) continue;
            if (!WearUpdater.PATH_UPDATE.equals(e.getDataItem().getUri().getPath())) continue;
            WearUpdater.consider(this, DataMapItem.fromDataItem(e.getDataItem()));   // copies; safe after the buffer is released
        }
    }
}
