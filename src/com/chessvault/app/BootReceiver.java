package com.chessvault.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        Log.i("ChessVaultBoot", "boot receiver action=" + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            "android.intent.action.PACKAGE_REPLACED".equals(action)) {
            DatabaseHelper db = new DatabaseHelper(ctx);
            String enabled = db.getConfig("auto_sync_enabled", "true");
            String username = db.getConfig("username", "");
            db.close();
            boolean should = "true".equals(enabled) && username != null && !username.trim().isEmpty();
            if (should) {
                AlarmReceiver.scheduleExactAlarm(ctx);
                Log.i("ChessVaultBoot", "rescheduled alarm after boot");
            } else {
                Log.i("ChessVaultBoot", "not scheduling, enabled=" + enabled);
            }
        }
    }
}
