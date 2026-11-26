package nl.bas.receptenboek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Notifications: an ongoing one while recipes are being read, and one per finished recipe. */
class Notifier {
    static final String CH_BUSY = "busy";
    static final String CH_DONE = "done";
    static final int ID_BUSY = 1;

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel busy = new NotificationChannel(CH_BUSY, "Bezig met recepten", NotificationManager.IMPORTANCE_LOW);
        busy.setShowBadge(false);
        nm.createNotificationChannel(busy);
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Recept toegevoegd", NotificationManager.IMPORTANCE_DEFAULT));
    }

    static PendingIntent open(Context c, String recipeId, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (recipeId != null) i.putExtra("openRecipe", recipeId);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification busy(Context c, String text) {
        channels(c);
        return new Notification.Builder(c, CH_BUSY)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Recept toevoegen")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setContentIntent(open(c, null, 0))
                .build();
    }

    static void updateBusy(Context c, String text) {
        c.getSystemService(NotificationManager.class).notify(ID_BUSY, busy(c, text));
    }

    static void done(Context c, String recipeId, String title) {
        channels(c);
        int id = (int) (System.currentTimeMillis() & 0xffffff) + 10;
        c.getSystemService(NotificationManager.class).notify(id, new Notification.Builder(c, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Recept toegevoegd")
                .setContentText(title)
                .setAutoCancel(true)
                .setContentIntent(open(c, recipeId, id))
                .build());
    }

    static void failed(Context c, String source, String error) {
        channels(c);
        int id = (int) (System.currentTimeMillis() & 0xffffff) + 10;
        c.getSystemService(NotificationManager.class).notify(id, new Notification.Builder(c, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Recept toevoegen niet gelukt (" + source + ")")
                .setContentText(error)
                .setStyle(new Notification.BigTextStyle().bigText(error))
                .setAutoCancel(true)
                .setContentIntent(open(c, null, id))
                .build());
    }
}
