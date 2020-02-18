package io.rootmos.audiojournal;

import java.io.File;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.AsyncTask;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;

import net.sourceforge.javaflacencoder.FLACEncoder;
import net.sourceforge.javaflacencoder.FLACFileOutputStream;
import net.sourceforge.javaflacencoder.StreamConfiguration;
import net.sourceforge.javaflacencoder.EncodingConfiguration;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;

public class MainActivity extends Activity {
    private static final String TAG = "AudioJournal";

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

    private Set<String> granted_permissions = new HashSet<>();

    private RecordTask recorder = null;

    private AmazonS3Client s3 = null;

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

        new ListSoundsTask().execute();
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


        File[] rs = getExternalMediaDirs();
        if(rs.length == 0) {
            throw new RuntimeException("no media dirs");
        }

        recorder = new RecordTask(rs[0]);
        recorder.execute();

        state = State.RECORDING;
        status_text.setText("Recording!");
        stop_button.setEnabled(true);
        start_button.setEnabled(false);

        Log.i(TAG, "state: recording");
    }

    private void stop_recording() {
        if(state != State.RECORDING) {
            Log.e(TAG, "trying to stop recording in non-recording state");
            return;
        }

        recorder.cancel(false);

        state = State.IDLE;
        status_text.setText("Not recording");
        stop_button.setEnabled(false);
        start_button.setEnabled(true);

        Log.i(TAG, "state: recording -> idle");
    }

    private void stop_playing() {
        if(state != State.PLAYING) {
            Log.e(TAG, "trying to stop playing in non-playing state");
            return;
        }

        state = State.IDLE;
        Log.i(TAG, "state transition: playing -> idle");
    }

    private class RecordTask extends AsyncTask<Void, Float, Boolean> {
        private AudioRecord recorder = null;
        private FLACEncoder encoder = null;
        private File baseDir = null;
        private FLACFileOutputStream out = null;

        public RecordTask(File baseDir) {
            this.baseDir = baseDir;
        };

        @Override
        protected void onPreExecute() {
            int sampleRate = 48000;

            int minBufSize = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            Log.d(TAG, "recommended minimum buffer size: " + minBufSize);

            int bufSize = Math.max(122880, minBufSize);

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

            OffsetDateTime time = OffsetDateTime.now();

            String fn = time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                + ".flac";
            File path = new File(baseDir, fn);
            try {
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
        protected Boolean doInBackground(Void... params) {
            final int channels = recorder.getChannelCount();
            final int sampleRate = recorder.getSampleRate();

            // TODO: make the chunk size configurable
            float seconds = 0;
            short[] samples = new short[1024*channels];
            while(!isCancelled()) {
                int r = recorder.read(samples, 0, samples.length,
                        AudioRecord.READ_BLOCKING);
                if(r < 0) {
                    Log.e(TAG, "audio recording failed: " + r);
                    return Boolean.FALSE;
                }
                Log.d(TAG, String.format("read %d samples of audio", r));

                int[] is = new int[r];
                for(int i = 0; i < is.length; ++i) {
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
                    Log.d(TAG, String.format("encoded %d samples of audio", r));
                }

                seconds += (float)r / (channels * sampleRate);
                publishProgress(seconds);
            }

            try {
                int s = encoder.samplesAvailableToEncode();
                int r = encoder.encodeSamples(s, true);
                if(r < s) {
                    Log.w(TAG, "trying to encode end one more time");
                    encoder.encodeSamples(s, true);
                }
            } catch(IOException e) {
                throw new RuntimeException("can't encode samples", e);
            }

            return Boolean.TRUE;
        }

        private void cleanup() {
            Log.e(TAG, "releasing audio recorder");
            recorder.stop();
            recorder.release();
            recorder = null;

            try {
                out.close();
            } catch(IOException e) {
                throw new RuntimeException("can't close output stream", e);
            }
        }

        @Override
        protected void onCancelled() {
            cleanup();
        }

        @Override
        protected void onPostExecute(Boolean res) {
            cleanup();
        }

        @Override
        protected void onProgressUpdate(Float... values) {
            status_text.setText(String.format("Recording: %.2fs", values[0]));
        }
    }

    private class ListSoundsTask extends AsyncTask<Void, Sound, List<Sound>> {
        @Override
        protected List<Sound> doInBackground(Void... params) {
            List<S3ObjectSummary> ol =
                s3.listObjects("rootmos-sounds").getObjectSummaries();
            ArrayList<Sound> ss = new ArrayList<>(ol.size());
            for(S3ObjectSummary os : ol) {
                S3Object o = s3.getObject(os.getBucketName(), os.getKey());
                String ct = o.getObjectMetadata().getContentType();
                if(ct.equals("application/json")) {
                    Sound s = Sound.fromInputStream(o.getObjectContent());
                    ss.add(s);
                    publishProgress(s);
                }
            }
            return ss;
        }

        @Override
        protected void onProgressUpdate(Sound... sounds) {
            sa.addSounds(sounds);
        }
    }

    private class SoundsAdapter extends BaseAdapter {
        ArrayList<Sound> ss = new ArrayList<>();

        public void addSounds(Sound[] ss) {
            for(Sound s : ss) this.ss.add(s);
            notifyDataSetChanged();
        }

        @Override
        public long getItemId(int i) { return ss.get(i).getSHA1().hashCode(); }

        @Override
        public Object getItem(int i) { return ss.get(i); }

        @Override
        public int getCount() { return ss.size(); }

        @Override
        public View getView(int i, View v, ViewGroup c) {
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.sounds_item, c, false);
            }

            Sound s = ss.get(i);

            ((TextView)v.findViewById(R.id.title)).setText(s.getTitle());

            return v;
        }
    }
}
