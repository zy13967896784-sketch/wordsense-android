package com.wordsense.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 收到闹钟后发通知，并安排下一天。 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL = "wordsense_daily";

    @Override
    public void onReceive(Context context, Intent intent) {
        showNotification(context);
        ReminderScheduler.scheduleFromPrefs(context);
    }

    private void showNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            if (!nm.areNotificationsEnabled()) return;
        } catch (Exception ignored) { }

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "每日学习提醒", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("每天到点提醒刷词");
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, 2002, open, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(context, CHANNEL);
        } else {
            builder = new Notification.Builder(context);
        }
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("词感 · 该刷词了")
                .setContentText("今天还没有开口。读 10 句，只要几分钟。")
                .setContentIntent(pi)
                .setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) {
            builder.setPriority(Notification.PRIORITY_DEFAULT);
        }
        nm.notify(2001, builder.build());
    }
}
