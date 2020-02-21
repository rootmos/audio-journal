package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import net.sourceforge.javaflacencoder.FLACEncoder;
import net.sourceforge.javaflacencoder.FLACFileOutputStream;
import net.sourceforge.javaflacencoder.StreamConfiguration;
import net.sourceforge.javaflacencoder.EncodingConfiguration;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;

public class MainActivity extends Activity {
    private enum Continuation {
        RECORD(815468);

        private final int value;
        private Continuation(int value) {
            this.value = value;
        }
        public int getValue() {
            return value;
        }
    };

    private enum State {
        IDLE, RECORDING, PLAYING
    };
    private State state = State.IDLE;

    private TextView status_text = null;;
    private Button start_button = null;
    private Button stop_button = null;

    private SoundsAdapter sa = new SoundsAdapter();
    private RecordTask recorder = null;
    private SoundItem active_sound = null;

    private Set<String> granted_permissions = new HashSet<>();

    private AmazonS3Client s3 = null;
    private String bucket = "rootmos-sounds";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status_text = (TextView)findViewById(R.id.status);
        start_button = (Button)findViewById(R.id.start);
        stop_button = (Button)findViewById(R.id.stop);

        ((ListView)findViewById(R.id.sounds)).setAdapter(sa);

        Log.d(TAG, "creating main activity");

        start_button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                start_recording();
            }
        });

        stop_button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stop_recording();
            }
        });

        Region r = Region.getRegion("eu-central-1");
        s3 = new AmazonS3Client(AWSAuth.getAuth(), r);

        new ListSoundsTask(this).execute();
    }

    private boolean ensurePermissionGranted(
            Continuation continuation,
            String[] permissions) {
        if(granted_permissions.containsAll(Arrays.asList(permissions))) {
            return true;
        }

        ActivityCompat.requestPermissions(
                this,
                permissions,
                continuation.getValue());

        return false;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        for(int i = 0; i < permissions.length; ++i) {
            if(grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "was granted: " + permissions[i]);
                granted_permissions.add(permissions[i]);
            }
        }

        if(requestCode == Continuation.RECORD.getValue()) {
            start_recording();
        } else {
            Log.e(TAG, "unexpected requestCode: " + requestCode);
        }
    }

    private File getBaseDir() {
        File[] rs = getExternalMediaDirs();
        if(rs.length == 0) {
            throw new RuntimeException("no media dirs");
        }
        return rs[0];
    }

    private File getTakesDir() {
        return new File(getBaseDir(), "takes");
    }

    private void start_recording() {
        if(state == State.RECORDING) {
            Log.e(TAG, "illegal state transition: recording -> recording");
            return;
        } else if(state == State.PLAYING) {
            stop_playing();
        }

        if(!ensurePermissionGranted(
                    Continuation.RECORD,
                    new String[] { Manifest.permission.RECORD_AUDIO })) {
            return;
        }

        MetadataTemplate template = new MetadataTemplate(
                "Session @ %t", "rootmos", "Gustav Behm", ".flac");
        template.setTargetDir(new File(getBaseDir(), "sessions"));
        template.setFilename("%t%s");

        recorder = new RecordTask(this, getTakesDir(), template);
        recorder.execute();

        state = State.RECORDING;
        status_text.setText("Recording!");
        stop_button.setEnabled(true);
        stop_button.setVisibility(View.VISIBLE);
        start_button.setEnabled(false);
        start_button.setVisibility(View.GONE);

        Log.i(TAG, "state: recording");
    }

    private void stop_recording() {
        if(state != State.RECORDING) {
            Log.e(TAG, "trying to stop recording in non-recording state");
            return;
        }

        recorder.stop();

        state = State.IDLE;
        status_text.setText("Not recording");
        stop_button.setEnabled(false);
        stop_button.setVisibility(View.GONE);
        start_button.setEnabled(true);
        start_button.setVisibility(View.VISIBLE);

        Log.i(TAG, "state: recording -> idle");
    }

    private void play_sound(SoundItem s) {
        if(state == State.RECORDING) {
            Toast.makeText(this,
                    "Stop recording to start playing",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if(state == State.PLAYING) {
            stop_playing();
        }

        if(state != State.IDLE) {
            Log.e(TAG, "trying to start playing in non-idle state");
            return;
        }

        active_sound = s;
        active_sound.play();
        state = State.PLAYING;

        Log.i(TAG, "state: ... -> playing");
    }

    private void stop_playing() {
        if(state != State.PLAYING) {
            Log.e(TAG, "trying to stop playing in non-playing state");
            return;
        }

        active_sound.stop();
        active_sound = null;

        state = State.IDLE;
        Log.i(TAG, "state transition: playing -> idle");
    }

    private void upload_sound(SoundItem s) {
        Log.d(TAG, "preparing to upload sound: " + s.getSound().getLocal());
        new UploadTask(this).execute(s);
    }

    private class RecordTask extends AsyncTask<Void, Float, Sound> {
        private AudioRecord recorder = null;
        private FLACEncoder encoder = null;
        private File baseDir = null;
        private FLACFileOutputStream out = null;
        private File path = null;
        private MetadataTemplate template = null;
        private OffsetDateTime time = null;
        private AtomicBoolean stopping = new AtomicBoolean(false);
        private Context ctx = null;

        public RecordTask(Context ctx, File baseDir,
                MetadataTemplate template) {
            this.ctx = ctx;
            this.baseDir = baseDir;
            this.template = template;
        };

        public void stop() { stopping.set(true); }

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
            path = new File(baseDir, fn);
            try {
                baseDir.mkdirs();

                out = new FLACFileOutputStream(path);
                if(!out.isValid()) {
                    throw new RuntimeException("can't open output stream");
                }
                encoder.setOutputStream(out);
                encoder.openFLACStream();
            } catch(IOException e) {
                throw new RuntimeException("can't open output stream", e);
            }
            Log.i(TAG, "recording: " + path);

            recorder.startRecording();
        }

        @Override
        protected Sound doInBackground(Void... params) {
            final int channels = recorder.getChannelCount();
            final int sampleRate = recorder.getSampleRate();

            // TODO: make the chunk size configurable
            short[] samples = new short[1024*channels];
            float seconds = 0;
            long samples_captured = 0, samples_encoded = 0;
            while(!stopping.get()) {
                int r = recorder.read(samples, 0, samples.length,
                        AudioRecord.READ_BLOCKING);
                if(r < 0) {
                    throw new RuntimeException("audio recording falied: " + r);
                }
                samples_captured += r;

                int[] is = new int[r];
                for(int i = 0; i < r; ++i) {
                    is[i] = samples[i];
                }
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

                seconds += (float)r / (channels * sampleRate);
                publishProgress(seconds);

                Log.d(TAG, String.format(
                    "recording: duration=%.2fs, samples captured=%d encoded=%d",
                    seconds, samples_captured, samples_encoded));
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

            Log.e(TAG, "releasing audio recorder");
            recorder.stop();
            recorder.release();
            recorder = null;

            try {
                out.close();
            } catch(IOException e) {
                throw new RuntimeException("can't close output stream", e);
            }

            Log.i(TAG, String.format("finished recording (%.2fs): %s",
                        seconds, path));

            return template.renderLocalFile(path, time, seconds);
        }

        @Override
        protected void onPostExecute(Sound s) {
            sa.addSounds(ctx, s);
        }

        @Override
        protected void onProgressUpdate(Float... values) {
            status_text.setText(String.format("Recording: %s",
                        Utils.formatDuration(values[0])));
        }
    }

    private class ListSoundsTask extends AsyncTask<Void, Sound, List<Sound>> {
        File cache = null;
        Context ctx = null;

        public ListSoundsTask(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        protected void onPreExecute() {
            cache = new File(getCacheDir(), "upstream");
            cache.mkdirs();
        }

        @Override
        protected List<Sound> doInBackground(Void... params) {
            ArrayList<Sound> ss = new ArrayList<>();

            for(Sound s : Sound.scanDir(getBaseDir())) {
                ss.add(s);
                publishProgress(s);
            }

            List<S3ObjectSummary> ol =
                s3.listObjects(bucket).getObjectSummaries();
            for(S3ObjectSummary os : ol) {
                if(!os.getKey().endsWith(".json")) continue;

                File f = new File(cache, os.getETag());
                if(!f.exists()) {
                    Log.d(TAG, String.format(
                                "fetching metadata: s3://%s/%s etag=%s",
                                os.getBucketName(), os.getKey(), os.getETag()));
                    s3.getObject(new GetObjectRequest(
                                os.getBucketName(), os.getKey()), f);
                } else {
                    Log.d(TAG, String.format(
                                "using cached metadata: s3://%s/%s etag=%s",
                                os.getBucketName(), os.getKey(), os.getETag()));
                }


                try {
                    FileInputStream is = new FileInputStream(f);
                    Sound s = Sound.fromInputStream(is);
                    is.close();
                    ss.add(s);
                    publishProgress(s);
                } catch(FileNotFoundException e) {
                    throw new RuntimeException("unable to open file", e);
                } catch(IOException e) {
                    throw new RuntimeException(
                            "exception while handling metadata file", e);
                }

            }
            return ss;
        }

        @Override
        protected void onProgressUpdate(Sound... sounds) {
            sa.addSounds(ctx, sounds);
        }
    }

    private class SoundItem implements View.OnClickListener,
            MediaPlayer.OnErrorListener, MediaPlayer.OnInfoListener,
            MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
            MediaPlayer.OnBufferingUpdateListener {
        private View v = null;
        private Sound s = null;
        private Context ctx = null;

        private ImageButton play = null;
        private ImageButton resume = null;
        private ImageButton pause = null;
        private ImageButton stop = null;
        private ImageButton upload = null;

        private MediaPlayer player = null;
        private FileInputStream is = null;

        public SoundItem(Context ctx, Sound s) {
            this.ctx = ctx;
            this.s = s;
        }

        public Sound getSound() { return s; }

        @Override
        public void onClick(View w) {
            if(w == play) {
                play_sound(this);
            } else if(w == upload) {
                upload_sound(this);
            } else {
                if(active_sound != this) {
                    Log.w(TAG, "click on inactive sound");
                    return;
                }

                if(w == pause) {
                    Log.i(TAG, "pausing: " + s.getURI());
                    player.pause();
                    pause.setVisibility(View.GONE);
                    resume.setVisibility(View.VISIBLE);
                } else if(w == resume) {
                    Log.i(TAG, "resuming: " + s.getURI());
                    player.start();
                    pause.setVisibility(View.VISIBLE);
                    resume.setVisibility(View.GONE);
                } else if(w == stop) {
                    stop_playing();
                } else {
                    Log.w(TAG, "unexpected click");
                }
            }
        }

        public View getView(ViewGroup vg) {
            if(v != null) return v;
            v = getLayoutInflater().inflate(R.layout.sounds_item, vg, false);

            play = (ImageButton)v.findViewById(R.id.play);
            play.setOnClickListener(this);

            pause = (ImageButton)v.findViewById(R.id.pause);
            pause.setOnClickListener(this);

            resume = (ImageButton)v.findViewById(R.id.resume);
            resume.setOnClickListener(this);

            stop = (ImageButton)v.findViewById(R.id.stop);
            stop.setOnClickListener(this);

            ((TextView)v.findViewById(R.id.title)).setText(s.getTitle());
            ((TextView)v.findViewById(R.id.artist)).setText(s.getArtist());
            ((TextView)v.findViewById(R.id.composer)).setText(s.getComposer());
            ((TextView)v.findViewById(R.id.duration)).setText(
                Utils.formatDuration(s.getDuration()));

            if(s.getDateTime() == null) {
                ((TextView)v.findViewById(R.id.date)).setText(
                    s.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
            } else {
                ((TextView)v.findViewById(R.id.date)).setText(s.getDateTime()
                    .withNano(0)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }

            upload = (ImageButton)v.findViewById(R.id.upload);
            upload.setOnClickListener(this);
            if(s.getURI() == null && s.getLocal() != null) {
                upload.setVisibility(View.VISIBLE);
            }

            return v;
        }

        public void play() {
            player = new MediaPlayer();

            try {
                if(s.getLocal() != null) {
                    is = new FileInputStream(s.getLocal());
                    Log.d(TAG, "using local data source: " + s.getLocal());
                    player.setDataSource(is.getFD());
                } else if(s.getURI() != null) {
                    Log.d(TAG, "using remote data source: " + s.getURI());
                    player.setDataSource(ctx, s.getURI());
                } else {
                    Log.e(TAG, "no source for sound: " + s.getTitle());
                    return;
                }
            } catch(IOException e) {
                Log.e(TAG, "unable to set data source", e);
                return;
            }

            player.setOnPreparedListener(this);
            player.setOnErrorListener(this);
            player.setOnInfoListener(this);
            player.setOnBufferingUpdateListener(this);
            player.setOnCompletionListener(this);

            play.setEnabled(false);
            player.prepareAsync();
            Log.i(TAG, String.format("preparing: local=%s uri=%s",
                        s.getLocal(), s.getURI()));
        }

        public void stop() {
            Log.i(TAG, String.format("stopping: local=%s uri=%s",
                    s.getLocal(), s.getURI()));

            if(player.isPlaying()) player.stop();
            player.release();
            player = null;

            if(is != null) {
                try {
                    is.close();
                    is = null;
                } catch(IOException e) {
                    throw new RuntimeException("unable to close file", e);
                }
            }

            play.setEnabled(true);
            play.setVisibility(View.VISIBLE);
            pause.setVisibility(View.GONE);
            resume.setVisibility(View.GONE);
            stop.setVisibility(View.GONE);
        }

        public void onCompletion(MediaPlayer m) {
            stop_playing();
        }

        public void onPrepared(MediaPlayer m) {
            m.start();

            play.setVisibility(View.GONE);
            pause.setVisibility(View.VISIBLE);
            resume.setVisibility(View.GONE);
            stop.setVisibility(View.VISIBLE);

            Log.i(TAG, String.format("playing: local=%s uri=%s",
                        s.getLocal(), s.getURI()));
        }

        public boolean onError(MediaPlayer m, int what, int extra) {
            Log.e(TAG, String.format("media error (%d): local=%s uri=%s",
                        what, s.getLocal(), s.getURI()));
            Toast.makeText(ctx, "Can't play: " + s.getTitle(),
                    Toast.LENGTH_SHORT).show();
            return true;
        }

        public boolean onInfo(MediaPlayer m, int what, int extra) {
            Log.d(TAG, String.format("media info (%d -> %d): local=%s uri=%s",
                        what, extra, s.getLocal(), s.getURI()));

            if(what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                Toast.makeText(ctx, "Buffering: " + s.getTitle(),
                        Toast.LENGTH_SHORT).show();
                return true;
            }

            return false;
        }

        public void onBufferingUpdate(MediaPlayer m, int percent) {
            Log.d(TAG, String.format("buffering (%d%%): local=%s uri=%s",
                        percent, s.getLocal(), s.getURI()));
            Toast.makeText(ctx,
                    String.format("Buffering (%d%%): %s",
                        percent, s.getTitle()),
                    Toast.LENGTH_SHORT).show();
        }

        public void uploaded() {
            upload.setVisibility(View.GONE);
        }

        public void merge(Sound o) {
            s.merge(o);

            if(s.getURI() == null && s.getLocal() != null) {
                upload.setVisibility(View.VISIBLE);
            } else {
                upload.setVisibility(View.GONE);
            }
        }
    }

    private class SoundsAdapter extends BaseAdapter {
        ArrayList<SoundItem> ss = new ArrayList<>();

        public void addSounds(Context ctx, Sound... sounds) {
            for(Sound s : sounds) {
                SoundItem t = null;
                for(SoundItem i : ss) {
                    if(Arrays.equals(i.getSound().getSHA1(), s.getSHA1())) {
                        t = i;
                        break;
                    }
                }
                if(t == null) {
                    ss.add(new SoundItem(ctx, s));
                } else {
                    t.merge(s);
                }
            }

            Collections.sort(ss,
                    new Comparator<SoundItem>() {
                        public int compare(SoundItem a, SoundItem b) {
                            return -1*a.getSound().compareTo(b.getSound());
                        }
                    });

            notifyDataSetChanged();
        }

        @Override
        public long getItemId(int i) {
            return ss.get(i).getSound().hashCode();
        }

        @Override
        public Object getItem(int i) { return ss.get(i); }

        @Override
        public int getCount() { return ss.size(); }

        @Override
        public View getView(int i, View v, ViewGroup vg) {
            return ss.get(i).getView(vg);
        }
    }

    private class UploadTask extends AsyncTask<SoundItem, SoundItem, Boolean> {
        private Context ctx = null;

        public UploadTask(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        protected Boolean doInBackground(SoundItem... ss) {
            for(SoundItem si : ss) {
                Sound s = si.getSound();
                Path r = getBaseDir().toPath()
                    .relativize(s.getLocal().toPath().getParent());
                String key = r.toString() + "/" + s.getFilename();
                Log.d(TAG, String.format("uploading: s3://%s/%s", bucket, key));
                s.setURI(Uri.parse(s3.getResourceUrl(bucket, key)));
                s3.putObject(bucket, key, s.getLocal());
                s3.setObjectAcl(bucket, key,
                        CannedAccessControlList.PublicRead);

                key = r.toString() + "/" + s.getMetadata().getName();
                Log.d(TAG, String.format("uploading: s3://%s/%s", bucket, key));
                Log.d(TAG, s.toJSON());
                s3.putObject(bucket, key, s.toJSON());
                s3.setObjectAcl(bucket, key,
                        CannedAccessControlList.PublicRead);

                publishProgress(si);
            }
            return Boolean.TRUE;
        }

        @Override
        protected void onProgressUpdate(SoundItem... ss) {
            for(SoundItem si : ss) {
                Log.i(TAG, "uploaded: " + si.getSound().getURI());
                Toast.makeText(ctx, "Uploaded: " + si.getSound().getTitle(),
                        Toast.LENGTH_SHORT).show();

                si.uploaded();
            }
        }
    }
}
