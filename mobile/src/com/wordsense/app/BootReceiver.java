package com.wordsense.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 重启后把每日提醒重新挂到 AlarmManager。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ReminderScheduler.scheduleFromPrefs(context);
    }
}
