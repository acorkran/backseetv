package com.kidstv.kidodi.wear;

import java.util.UUID;

/** Same GATT identity as the player's BleServer. */
public final class Ble {
    private Ble() {}
    public static final UUID SERVICE = UUID.fromString("6b69646f-0001-1000-8000-00805f9b34fb");
    public static final UUID CMD     = UUID.fromString("6b69646f-0002-1000-8000-00805f9b34fb");
    public static final UUID STATE   = UUID.fromString("6b69646f-0003-1000-8000-00805f9b34fb");
    public static final UUID IMG     = UUID.fromString("6b69646f-0004-1000-8000-00805f9b34fb"); // chunked low-res still frames
    public static final UUID CCCD    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
}
