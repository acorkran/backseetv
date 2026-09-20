package com.kidstv.kidodi.admin;

import android.app.admin.DeviceAdminReceiver;

/** Device-admin hook so the optional "locked" tier can run device-owner APIs
 *  (inescapable lock-task, status-bar disable). Only relevant if the app is made
 *  device owner; the default ships as a normal app using screen-pinning. */
public class AdminReceiver extends DeviceAdminReceiver {}
