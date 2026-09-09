package com.kidstv.kidodi.wear;

// ============================================================================
//  Glanceable Tile: swipe to it and control the tablet-player without opening
//  the app. Shows the current show + Prev / Play-Pause / Next. Taps map to a
//  clickable id which we forward to the (persistent) BleService. The tile
//  refreshes every few seconds to keep the title + play glyph current.
//  Requires the app to have been opened once so BleService is alive.
// ============================================================================

import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.LayoutElementBuilders.Column;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.LayoutElementBuilders.Row;
import androidx.wear.protolayout.LayoutElementBuilders.Spacer;
import androidx.wear.protolayout.ModifiersBuilders.Clickable;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.material.Button;
import androidx.wear.protolayout.material.Text;
import androidx.wear.protolayout.material.Typography;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

public class KidodiTileService extends TileService {
    static final String VER = "1";

    @Override protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest req) {
        // a tap arrives as the last-clicked id -> forward it to the live BLE service
        try {
            String clicked = req.getCurrentState().getLastClickableId();
            if (clicked != null && !clicked.isEmpty()) {
                BleService s = BleService.get();
                if (s != null) s.send(clicked);
            }
        } catch (Exception ignored) {}

        boolean playing = false;
        String title = "Open Kidodi";
        BleService svc = BleService.get();
        if (svc != null) {
            try {
                JSONObject o = new JSONObject(svc.state());
                playing = o.optBoolean("playing", false);
                String t = o.optString("title", "").replaceAll("\\.(mp4|mkv|m4v)$", "");
                int i = t.indexOf(".S");
                title = (i > 0 ? t.substring(0, i) : t).replace('.', ' ').trim();
                if (title.isEmpty()) title = "Kidodi";
            } catch (Exception ignored) { title = "Kidodi"; }
        }

        LayoutElement root = layout(title, playing);
        TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                .setResourcesVersion(VER)
                .setFreshnessIntervalMillis(3000)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build();
        return Futures.immediateFuture(tile);
    }

    @Override protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(RequestBuilders.ResourcesRequest req) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion(VER).build());
    }

    private LayoutElement layout(String title, boolean playing) {
        Text titleText = new Text.Builder(this, title)
                .setColor(ColorBuilders.argb(0xFFFFFFFF))
                .setTypography(Typography.TYPOGRAPHY_TITLE3)
                .setMaxLines(1)
                .build();

        Row transport = new Row.Builder()
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(btn("prev", "◀◀"))
                .addContent(spacerW(8))
                .addContent(btn("playpause", playing ? "❚❚" : "▶"))
                .addContent(spacerW(8))
                .addContent(btn("next", "▶▶"))
                .build();

        Column col = new Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(titleText)
                .addContent(new Spacer.Builder().setHeight(DimensionBuilders.dp(14)).build())
                .addContent(transport)
                .build();

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(col)
                .build();
    }

    private Button btn(String id, String glyph) {
        Clickable c = new Clickable.Builder()
                .setId(id)
                .setOnClick(new ActionBuilders.LoadAction.Builder().build())
                .build();
        return new Button.Builder(this, c).setTextContent(glyph).build();
    }

    private Spacer spacerW(int dp) {
        return new Spacer.Builder().setWidth(DimensionBuilders.dp(dp)).build();
    }
}
