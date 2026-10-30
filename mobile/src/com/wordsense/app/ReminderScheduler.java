package com.wordsense.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/** 每天固定时间发一条本地学习提醒。 */
public final class ReminderScheduler {
    static final String PREFS = "wordsense_reminder";
    static final String KEY_ON = "enabled";
    static final String KEY_HOUR = "hour";
    static final String KEY_MIN = "minute";
    static final int REQUEST = 2001;

    private ReminderScheduler() { }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        return PendingIntent.getBroadcast(context, REQUEST, intent, pendingFlags());
    }

    static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(context));
        prefs(context).edit().putBoolean(KEY_ON, false).apply();
    }

    static void schedule(Context context, int hour, int minute) {
        SharedPreferences p = prefs(context);
        p.edit().putBoolean(KEY_ON, true).putInt(KEY_HOUR, hour).putInt(KEY_MIN, minute).apply();

        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_MONTH, 1);
        }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(context);
        if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        }
    }

    static void scheduleFromPrefs(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_ON, false)) return;
        schedule(context, p.getInt(KEY_HOUR, 20), p.getInt(KEY_MIN, 30));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
