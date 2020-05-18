package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;

import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.HashSet;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.AsyncTask;
import android.util.Log;

import com.amazonaws.regions.Region;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.event.ProgressEvent;

import org.apache.commons.codec.binary.Hex;

public class UploadService extends Service {
    private static int NOTIFICATION_ID = 8544647;
    private static String NOTIFICATION_GROUP = "io.rootmos.audiojournal.UPLOAD";
    private NotificationManager nm = null;
    private NotificationChannel nc = null;

    private AmazonS3Client s3 = null;
    private Settings settings = new Settings(this);

    private Set<Sound> active = new HashSet<>();

    public class Binder extends android.os.Binder {
    }

    @Override
    public void onCreate() {
        nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

        s3 = new AmazonS3Client(AWSAuth.getAuth(),
                Region.getRegion(settings.getBucketRegion()));

        nc = new NotificationChannel(
                "AUDIO_JOURNAL_UPLOAD", "Upload progress",
                NotificationManager.IMPORTANCE_LOW);

        nm.createNotificationChannel(nc);

        Notification n = new Notification.Builder(this, nc.getId())
            .setSmallIcon(R.mipmap.audio_journal)
            .setGroup(NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .setSubText("uploading...")
            .build();

        startForeground(NOTIFICATION_ID, n);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, String.format("upload service bound: %s", intent));
        return new Binder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, String.format("upload service start (%d): %s",
                    startId, intent));

        handleIntent(intent);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "upload service destroyed");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "no more clients bound to upload service");
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        Log.i(TAG, "clients have reconnected to the upload service");
    }

    private void handleIntent(Intent i) {
        Log.i(TAG, "preparing to upload: " + i);
        String raw = i.getStringExtra("metadata");
        try {
            Sound s = Sound.fromLocalFile(Paths.get(raw));
            if(!active.contains(s)) {
                active.add(s);
                notify(s, 0);
                new UploadTask().execute(s);
            }
        } catch(FileNotFoundException e) {
            Log.e(TAG, "unable to find: " + raw, e);
        }
    }

    public static void upload(Context ctx, Sound s) {
        Intent i = new Intent(ctx, UploadService.class);
        i.putExtra("metadata", s.getMetadata().toString());

        if(ctx.startService(i) == null) {
            throw new RuntimeException("unable to start recording service");
        }
    }

    private class Progress implements ProgressListener {
        private Sound s = null;
        private long sum = 0;
        private long total = 0;

        public Progress(Sound s) {
            this.s = s;
            this.total = s.getLocal().toFile().length();
        }

        public void progressChanged(ProgressEvent e) {
            String sha1 = Hex.encodeHexString(s.getSHA1());
            try {
                switch(e.getEventCode()) {
                    case 0: // uhm?
                        sum += e.getBytesTransferred();
                        Log.i(TAG, String.format(
                                    "upload progress (%s): bytes=%d",
                                    sha1, sum));
                        UploadService.this.notify(s, (int)(100*sum/total));
                        break;
                    default:
                        Log.d(TAG, String.format(
                                    "upload progress (%s): bytes=%d event=%d",
                                    sha1, e.getBytesTransferred(),
                                    e.getEventCode()));
                }
            } catch(Exception ex) {
                Log.e(TAG, String.format(
                            "exception while processing upload progress of sound (%s): %s",
                            sha1, s.getTitle()), ex);
            }
        }
    }

    private class UploadTask extends AsyncTask<Sound, Sound, Boolean> {
        @Override
        protected Boolean doInBackground(Sound... ss) {
            for(Sound s : ss) {
                // TODO: use prefix?
                Path r = settings.getBaseDir()
                    .relativize(s.getLocal().getParent());
                String bucket = settings.getBucketName();
                String key = r.toString() + "/" + s.getFilename();

                String metadataKey = r.toString() + "/" + s.getMetadata().getFileName();
                Log.d(TAG, String.format("uploading: s3://%s/%s", bucket, metadataKey));
                s.setURI(Uri.parse(s3.getResourceUrl(bucket, key)));
                s3.putObject(bucket, metadataKey, s.toJSON());
                s3.setObjectAcl(bucket, metadataKey,
                        CannedAccessControlList.PublicRead);

                Log.d(TAG, String.format("uploading: s3://%s/%s", bucket, key));
                PutObjectRequest req = new PutObjectRequest(
                        bucket, key, s.getLocal().toFile())
                    .withGeneralProgressListener(new Progress(s));
                s3.putObject(req);
                s3.setObjectAcl(bucket, key,
                        CannedAccessControlList.PublicRead);

                Log.i(TAG, String.format("uploaded (%s): %s",
                            Hex.encodeHexString(s.getSHA1()), s.getTitle()));

                publishProgress(s);
            }

            return Boolean.TRUE;
        }

        @Override
        protected void onProgressUpdate(Sound... ss) {
            for(Sound s : ss) {
                completed(s);
            }
        }
    }

    private void notify(Sound s, int progress) {
        Notification n = new Notification.Builder(this, nc.getId())
            .setSmallIcon(R.mipmap.audio_journal)
            .setGroup(NOTIFICATION_GROUP)
            .setSubText("uploading...")
            .setContentTitle(s.getTitle())
            .setProgress(100, progress, progress == 0)
            .build();

        nm.notify(s.hashCode(), n);
    }

    private void completed(Sound s) {
        active.remove(s);

        nm.cancel(s.hashCode());

        if(active.isEmpty()) {
            stopSelf();
        }
    }
}
