package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.Service;
import android.os.IBinder;
import android.os.AsyncTask;
import android.content.Intent;
import android.content.Context;
import android.content.ServiceConnection;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.TaskStackBuilder;
import android.util.Log;
import android.media.AudioRecord;
import android.media.AudioFormat;

import net.sourceforge.javaflacencoder.FLACEncoder;
import net.sourceforge.javaflacencoder.FLACFileOutputStream;
import net.sourceforge.javaflacencoder.StreamConfiguration;
import net.sourceforge.javaflacencoder.EncodingConfiguration;

public class RecordingService extends Service {
    private static int NOTIFICATION_ID = 603141;
    private NotificationManager nm = null;
    private Executor ex = null;

    private RecordTask recordTask = null;
    private boolean stopWhenNotRecording = false;

    public interface OnProgressListener {
        public abstract void recordingProgress(Progress progress);
    }
    private ArrayList<OnProgressListener> progressListeners = new ArrayList<>();

    public interface OnStateChangeListener {
        public abstract void recordingStarted();
        public abstract void recordingCompleted(Sound s);
    }
    private ArrayList<OnStateChangeListener> stateListeners = new ArrayList<>();

    public class Binder extends android.os.Binder {
        public boolean isRecording() {
            return RecordingService.this.isRecording();
        }

        public boolean stop() { return RecordingService.this.stop(); }

        public void addProgressListener(OnProgressListener l) {
            progressListeners.add(l);
        }
        public void removeProgressListener(OnProgressListener l) {
            progressListeners.remove(l);
        }

        public void addStateChangeListener(OnStateChangeListener l) {
            stateListeners.add(l);
        }
        public void removeStateChangeListener(OnStateChangeListener l) {
            stateListeners.remove(l);
        }
    }

    public boolean isRecording() {
        return recordTask != null;
    }

    @Override
    public void onCreate() {
        nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        ex = Executors.newFixedThreadPool(1);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, String.format("recording service bound: %s", intent));
        handleIntent(intent);
        return new Binder();
    }

    public static void bind(Context ctx, ServiceConnection sc) {
        Intent i = new Intent(ctx, RecordingService.class);
        i.putExtra("action", "boot");

        if(!ctx.bindService(i, sc, Context.BIND_AUTO_CREATE)) {
            throw new RuntimeException("unable to bind to recording service");
        }
    }

    public static void start(Context ctx,
            MetadataTemplate template, File takesDir) {
        Intent i = new Intent(ctx, RecordingService.class);
        i.putExtra("action", "start");
        i.putExtra("metadatTemplate", template);
        i.putExtra("takesDir", takesDir.toString());

        if(ctx.startService(i) == null) {
            throw new RuntimeException("unable to start recording service");
        }
    }

    private Intent mkStopIntent() {
        Intent i = new Intent(this, RecordingService.class);
        i.putExtra("action", "stop");
        return i;
    }

    private Intent mkShowIntent() {
        return new Intent(this, RecordingActivity.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, String.format("recording service start (%d): %s",
                    startId, intent));

        handleIntent(intent);
        return START_NOT_STICKY;
    }

    private void handleIntent(Intent intent) {
        String action = intent.getStringExtra("action");
        if(action == null) {
            throw new RuntimeException("invalid intent received");
        }

        if(action.equals("boot")) {
        } else if(action.equals("start")) {
            MetadataTemplate mt = intent.getParcelableExtra("metadatTemplate");
            File takesDir = new File(intent.getStringExtra("takesDir"));
            record(mt, takesDir);
        } else if(action.equals("stop")) {
            stop();
        } else {
            throw new RuntimeException("unsupported action: " + action);
        }
    }

    @Override
    public void onDestroy() {
        if(isRecording()) {
            throw new RuntimeException("destroying service while still recording");
        }

        Log.i(TAG, "recording service destroyed");
    }

    @Override
    public void onRebind(Intent intent) {
        Log.i(TAG, "clients have reconnected");
        stopWhenNotRecording = false;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "no more bound clients: recording=" + isRecording());
        if(!isRecording()) stopSelf();
        stopWhenNotRecording = true;
        return true;
    }

    private Notification buildNotification(Progress progress) {
        TaskStackBuilder sb = TaskStackBuilder.create(this);
        sb.addNextIntentWithParentStack(mkShowIntent());
        PendingIntent p = sb.getPendingIntent(0,
                PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent ps = PendingIntent.getService(this, 0, mkStopIntent(), 0);
        Notification.Action a = new Notification.Action.Builder(
            R.drawable.stop_recording, getText(R.string.stop_recording), ps)
            .build();

        Notification.Builder b = new Notification.Builder(
                this, Common.getNotificationChannel(this).getId())
            .setSmallIcon(R.drawable.audio_journal)
            .setSubText("recording...")
            .setContentIntent(p)
            .setContentTitle(recordTask.getTitle())
            .addAction(a);

        if(progress != null) {
            b.setContentText(Utils.formatDuration(progress.getSeconds()));
        }

        return b.build();
    }

    private boolean record(MetadataTemplate template, File takesDir) {
        if(recordTask != null) {
            Log.e(TAG, "trying to start new recording while already recording");
            return false;
        }

        recordTask = new RecordTask(template, takesDir);
        recordTask.executeOnExecutor(ex);

        startForeground(NOTIFICATION_ID, buildNotification(null));

        for(OnStateChangeListener l : stateListeners) {
            l.recordingStarted();
        }

        return true;
    }

    private boolean stop() {
        if(recordTask != null) {
            recordTask.stop();
            return true;
        } else {
            return false;
        }
    }

    private void stopped(Sound s) {
        recordTask = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        for(OnStateChangeListener l : stateListeners) {
            l.recordingCompleted(s);
        }

        if(stopWhenNotRecording) {
            stopSelf();
        }
    }

    public class Progress {
        int channels = 0;
        long samples = 0;
        long clipped = 0;
        int sampleRate = 0;

        short max = 0;
        short cur = 0;

        String title = null;
        OffsetDateTime time = null;
        MetadataTemplate template = null;

        private Progress(MetadataTemplate template, OffsetDateTime time,
                int sampleRate, long samples, long clipped, int channels,
                short max, short cur) {
            this.title = title;
            this.time = time;
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.clipped = clipped;
            this.channels = channels;
            this.max = max;
            this.cur = cur;
        }

        public float getSeconds() {
            return Utils.samplesAndSampleRateToSeconds(
                    samples, sampleRate, channels);
        }

        public int getMaxGainPercent() {
            BigDecimal range = new BigDecimal(Short.MAX_VALUE);
            return new BigDecimal(max)
                .multiply(new BigDecimal(100))
                .divide(range, 0, RoundingMode.HALF_UP)
                .intValue();
        }

        public int getGainPercent() {
            BigDecimal range = new BigDecimal(Short.MAX_VALUE);
            return new BigDecimal(cur)
                .multiply(new BigDecimal(100))
                .divide(range, 0, RoundingMode.HALF_UP)
                .intValue();
        }

        public long getClippedSamples() { return clipped; }
    }

    private class RecordTask extends AsyncTask<Void, Progress, Sound> {
        private MetadataTemplate template = null;
        private File takesDir = null;

        private AudioRecord recorder = null;
        private FLACEncoder encoder = null;
        private FLACFileOutputStream out = null;
        private File path = null;
        private OffsetDateTime time = null;
        private AtomicBoolean stopping = new AtomicBoolean(false);

        public void stop() { stopping.set(true); }

        public RecordTask(MetadataTemplate template, File takesDir) {
            this.template = template;
            this.takesDir = takesDir;
        }

        public String getTitle() {
            return template.renderTitle(time);
        }

        @Override
        protected void onPreExecute() {
            int sampleRate = 48000;

            int minBufSize = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            Log.d(TAG, "recommended minimum buffer size: " + minBufSize);

            int bufSize = Math.max(491520, minBufSize);

            while(true) {
                recorder = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .build();

                // ensure 1 seconds of audio fits in the buffer
                if(recorder.getBufferSizeInFrames() >=
                        recorder.getChannelCount() *
                        recorder.getSampleRate()) {
                    break;
                } else {
                    recorder.release();
                    recorder = null;
                    bufSize *= 2;
                    Log.d(TAG, "buffer too small, trying: " + bufSize);
                }
            }

            Log.d(TAG, "configured channel count: " + recorder.getChannelCount());
            Log.d(TAG, "configured sample rate: " + recorder.getSampleRate());
            Log.d(TAG, "configured buffer size in frames: " + recorder.getBufferSizeInFrames());

            encoder = new FLACEncoder();
            StreamConfiguration sc = new StreamConfiguration();
            sc.setChannelCount(recorder.getChannelCount());
            sc.setSampleRate(recorder.getSampleRate());
            sc.setBitsPerSample(16);
            encoder.setStreamConfiguration(sc);

            EncodingConfiguration ec = new EncodingConfiguration();
            ec.setSubframeType(EncodingConfiguration.SubframeType.EXHAUSTIVE);
            encoder.setEncodingConfiguration(ec);

            time = OffsetDateTime.now();

            String fn = time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                + ".flac";
            path = new File(takesDir, fn);
            try {
                takesDir.mkdirs();

                out = new FLACFileOutputStream(path);
                if(!out.isValid()) {
                    throw new RuntimeException("can't open output stream");
                }
                encoder.setOutputStream(out);
                encoder.openFLACStream();
            } catch(IOException e) {
                throw new RuntimeException("can't open output stream", e);
            }
        }

        @Override
        protected Sound doInBackground(Void... params) {
            Log.i(TAG, "recording: " + path);
            recorder.startRecording();

            final int channels = recorder.getChannelCount();
            final int sampleRate = recorder.getSampleRate();

            // TODO: make the chunk size configurable
            short[] samples = new short[1024*channels];
            short max = 0;
            long samples_captured = 0, samples_encoded = 0, samples_clipped = 0;
            while(!stopping.get()) {
                int r = recorder.read(samples, 0, samples.length,
                        AudioRecord.READ_BLOCKING);
                if(r < 0) {
                    throw new RuntimeException("audio recording falied: " + r);
                }
                samples_captured += r;

                long sum = 0;
                short cur = 0;
                int[] is = new int[r];
                for(int i = 0; i < r; ++i) {
                    short n = (short)Math.abs(samples[i]);
                    if(n == Short.MAX_VALUE) samples_clipped++;
                    if(cur < n) cur = n;
                    is[i] = samples[i];
                }
                if(max < cur) max = cur;
                encoder.addSamples(is, is.length/channels);

                int enc;
                if((enc = encoder.fullBlockSamplesAvailableToEncode()) > 0) {
                    try {
                        r = encoder.encodeSamples(enc, false);
                    } catch(IOException e) {
                        throw new RuntimeException("can't encode samples", e);
                    }
                    samples_encoded += r * channels;
                }

                Progress p = new Progress(template, time,
                        sampleRate, samples_encoded, samples_clipped, channels,
                        max, cur);
                publishProgress(p);

                Log.d(TAG, String.format(
                    "recording: samples captured=%d encoded=%d, cur=%d, max=%d",
                    samples_captured, samples_encoded, cur, max));
            }

            try {
                int s = samples_encoded == samples_captured ? 0 :
                    encoder.samplesAvailableToEncode();
                int r = encoder.encodeSamples(s, true);
                if(r < s) {
                    Log.w(TAG, "trying to encode end one more time");
                    encoder.encodeSamples(s, true);
                }
            } catch(IOException e) {
                throw new RuntimeException("can't encode samples", e);
            }

            Log.d(TAG, "releasing audio recorder");
            recorder.stop();
            recorder.release();
            recorder = null;

            try {
                out.close();
            } catch(IOException e) {
                throw new RuntimeException("can't close output stream", e);
            }

            float seconds = Utils.samplesAndSampleRateToSeconds(
                    samples_encoded, sampleRate, channels);
            Log.i(TAG, String.format("finished recording (%.2fs): %s",
                        seconds, path));
            return template.renderLocalFile(path, time, seconds);
        }

        @Override
        protected void onPostExecute(Sound s) {
            stopped(s);
        }

        @Override
        protected void onProgressUpdate(Progress... values) {
            Progress p = values[0];

            nm.notify(NOTIFICATION_ID, buildNotification(p));

            for(OnProgressListener l : progressListeners) {
                l.recordingProgress(p);
            }
        }
    }
}
