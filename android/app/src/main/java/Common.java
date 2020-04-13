package io.rootmos.audiojournal;

import android.content.Context;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class Common {
    public static String TAG = "AudioJournal";

    private static NotificationChannel notificationChannel =
        new NotificationChannel("AUDIO_JOURNAL", "Recording notifications",
                        NotificationManager.IMPORTANCE_LOW);
    private static Boolean notificationChannelCreated = false;

    public static NotificationChannel getNotificationChannel(Context ctx) {
        synchronized(notificationChannelCreated) {
            if(!notificationChannelCreated) {
                NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.createNotificationChannel(notificationChannel);
                notificationChannelCreated = true;
            }
            return notificationChannel;
        }
    }
}
