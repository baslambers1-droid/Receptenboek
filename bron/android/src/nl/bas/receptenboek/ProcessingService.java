package nl.bas.receptenboek;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Keeps the app alive while recipes are processed, so the app can be closed meanwhile. */
public class ProcessingService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent != null && intent.getStringExtra("text") != null ? intent.getStringExtra("text") : "Bezig…";
        startForeground(Notifier.ID_BUSY, Notifier.busy(this, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (!Processor.get(this).hasBusyJobs()) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
