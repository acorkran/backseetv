package com.kidstv.kidodi;

import java.util.UUID;

/** Shared BLE GATT identity for the Kidodi control channel. */
public final class Ble {
    private Ble() {}
    // "6b69646f" = "kido"
    public static final UUID SERVICE = UUID.fromString("6b69646f-0001-1000-8000-00805f9b34fb");
    public static final UUID CMD     = UUID.fromString("6b69646f-0002-1000-8000-00805f9b34fb"); // remote WRITES commands
    public static final UUID STATE   = UUID.fromString("6b69646f-0003-1000-8000-00805f9b34fb"); // player NOTIFIES state
    public static final UUID IMG     = UUID.fromString("6b69646f-0004-1000-8000-00805f9b34fb"); // player NOTIFIES chunked low-res still frames
    public static final UUID CCCD    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"); // standard notify descriptor
}
