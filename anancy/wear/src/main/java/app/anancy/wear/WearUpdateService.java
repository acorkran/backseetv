package app.anancy.wear;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

/** Woken by Play services whenever the phone publishes a watch build or a new watch target — even if
    this app isn't running. */
public class WearUpdateService extends WearableListenerService {
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent e : events) {
            if (e.getType() != DataEvent.TYPE_CHANGED) continue;
            String path = e.getDataItem().getUri().getPath();
            if (WatchTarget.PATH.equals(path)) { WatchTarget.apply(this, DataMapItem.fromDataItem(e.getDataItem()).getDataMap()); continue; }
            if (!WearUpdater.PATH_UPDATE.equals(path)) continue;
            WearUpdater.consider(this, DataMapItem.fromDataItem(e.getDataItem()));   // copies; safe after the buffer is released
        }
    }
}
