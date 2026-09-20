package app.anancy.wear;

// ============================================================================
//  Glanceable STATUS tile (not a control surface -- tiles refresh on an interval,
//  so they make laggy buttons). Shows what's on, whether the TV is awake/asleep,
//  and the sleep-timer countdown. Tap anywhere -> opens the full remote app.
//  Reads the live state the persistent BleService holds; refreshes every 2s.
// ============================================================================

import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.LayoutElementBuilders.Column;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.LayoutElementBuilders.Spacer;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ModifiersBuilders.Clickable;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.protolayout.material.Text;
import androidx.wear.protolayout.material.Typography;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

public class AnancyTileService extends TileService {
    static final String VER = "1";

    // The BLE link is on demand: hold it only while the tile is actually on screen.
    @Override protected void onTileEnterEvent(androidx.wear.tiles.EventBuilders.TileEnterEvent e) { BleService.acquire(this, "tile"); }
    @Override protected void onTileLeaveEvent(androidx.wear.tiles.EventBuilders.TileLeaveEvent e) { BleService.release(this, "tile"); }

    @Override protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest req) {
        String title = "Open Anancy";
        String status = "Tap to open the remote";
        int statusColor = 0xFF9AA0A6;

        BleService svc = BleService.get();
        if (svc != null) {
            try {
                JSONObject o = new JSONObject(svc.state());
                String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
                int i = t.indexOf(".S");
                title = (i > 0 ? t.substring(0, i) : t).replace('.', ' ').trim();
                if (title.isEmpty()) title = "Anancy";

                if (o.optBoolean("sleeping", false)) {
                    long s = Math.max(0, o.optLong("sleep_remaining_ms", 0)) / 1000;
                    status = "Sleep in " + (s / 60) + ":" + String.format("%02d", s % 60);
                    statusColor = 0xFFC9A227;
                } else if (o.optBoolean("napped", false)) {
                    status = "Asleep (off)"; statusColor = 0xFF9AA0A6;
                } else if (o.optBoolean("playing", false)) {
                    status = "Playing"; statusColor = 0xFF8FD694;
                } else {
                    status = "Paused"; statusColor = 0xFFFFC107;
                }
            } catch (Exception ignored) { title = "Anancy"; status = "connecting…"; }
        }

        TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                .setResourcesVersion(VER)
                .setFreshnessIntervalMillis(2000)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(title, status, statusColor)))
                .build();
        return Futures.immediateFuture(tile);
    }

    @Override protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(RequestBuilders.ResourcesRequest req) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion(VER).build());
    }

    private LayoutElement layout(String title, String status, int statusColor) {
        Column col = new Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(new Text.Builder(this, title)
                        .setColor(ColorBuilders.argb(0xFFFFFFFF))
                        .setTypography(Typography.TYPOGRAPHY_TITLE3).setMaxLines(2).build())
                .addContent(new Spacer.Builder().setHeight(DimensionBuilders.dp(10)).build())
                .addContent(new Text.Builder(this, status)
                        .setColor(ColorBuilders.argb(statusColor))
                        .setTypography(Typography.TYPOGRAPHY_TITLE2).setMaxLines(1).build())
                .addContent(new Spacer.Builder().setHeight(DimensionBuilders.dp(10)).build())
                .addContent(new Text.Builder(this, "Tap to open remote")
                        .setColor(ColorBuilders.argb(0xFF6B7075))
                        .setTypography(Typography.TYPOGRAPHY_CAPTION2).setMaxLines(1).build())
                .build();

        Clickable open = new Clickable.Builder()
                .setId("open")
                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(getPackageName())   // applicationId is app.anancy now
                                .setClassName("app.anancy.wear.WearRemoteActivity")
                                .build())
                        .build())
                .build();

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
                .addContent(col)
                .build();
    }
}
