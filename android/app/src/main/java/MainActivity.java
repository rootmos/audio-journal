package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

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

public class MainActivity extends Activity implements
    RecordingService.OnStateChangeListener {

    private enum State {
        IDLE, RECORDING, PLAYING
    };
    private State state = State.IDLE;

    private RecordingService.Binder rs = null;
    ServiceConnection sc = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "main activity connected to recording service");
            rs = (RecordingService.Binder)service;
            rs.addStateChangeListener(MainActivity.this);
            applyRecordingState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "main activity disconnected to recording service");
            rs = null;
        }
    };

    private ExtendedFloatingActionButton fab = null;

    private SoundsAdapter sa = new SoundsAdapter();
    private SoundItem active_sound = null;

    private AmazonS3Client s3 = null;

    private Settings settings = new Settings(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.i(TAG, "creating main activity");

        ((ListView)findViewById(R.id.sounds)).setAdapter(sa);

        fab = (ExtendedFloatingActionButton)
            findViewById(R.id.start_stop_recording);
        fab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this,
                            RecordingActivity.class));
            }
        });

        s3 = new AmazonS3Client(AWSAuth.getAuth(),
                Region.getRegion(settings.getBucketRegion()));
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "stopping main activity");
        rs.removeStateChangeListener(this);
        unbindService(sc);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "resuming main activity");

        RecordingService.bind(this, sc);

        new ListSoundsTask(this).execute();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "destroying main activity");
    }

    @Override
    public void recordingStarted(RecordingService.Started started) {
        applyRecordingState();
    }

    @Override
    public void recordingCompleted(Sound s) {
        sa.addSounds(this, s);
        applyRecordingState();
    }

    public void applyRecordingState() {
        Log.d(TAG, "applying recording state: " + rs.isRecording());
        if(rs.isRecording()) {
            if(state == State.PLAYING) {
                stop_playing();
            }
            state = State.RECORDING;
            fab.setText(getText(R.string.stop_recording));
            fab.setIcon(getDrawable(R.drawable.stop_recording));
        } else {
            if(state == State.RECORDING) state = State.IDLE;
            fab.setText(getText(R.string.start_recording));
            fab.setIcon(getDrawable(R.drawable.start_recording));
        }
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

    private class ListSoundsTask extends AsyncTask<Void, Sound, List<Sound>> {
        Context ctx = null;

        public ListSoundsTask(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        protected List<Sound> doInBackground(Void... params) {
            ArrayList<Sound> ss = new ArrayList<>();

            for(Sound s : Sound.scanDir(settings.getBaseDir())) {
                ss.add(s);
                publishProgress(s);
            }

            List<S3ObjectSummary> ol =
                s3.listObjects(settings.getBucketName()).getObjectSummaries();
            for(S3ObjectSummary os : ol) {
                if(!os.getKey().endsWith(".json")) continue;

                Path f = settings.getUpstreamCacheDir().resolve(os.getETag());
                if(!Files.exists(f)) {
                    Log.d(TAG, String.format(
                                "fetching metadata: s3://%s/%s etag=%s",
                                os.getBucketName(), os.getKey(), os.getETag()));
                    s3.getObject(new GetObjectRequest(
                                os.getBucketName(), os.getKey()), f.toFile());
                } else {
                    Log.d(TAG, String.format(
                                "using cached metadata: s3://%s/%s etag=%s",
                                os.getBucketName(), os.getKey(), os.getETag()));
                }

                try {
                    FileInputStream is = new FileInputStream(f.toFile());
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
        private ImageButton share = null;

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
            } else if(w == share) {
                startActivity(s.getShareIntent(ctx));
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

            share = (ImageButton)v.findViewById(R.id.share);
            share.setOnClickListener(this);

            return v;
        }

        public void play() {
            player = new MediaPlayer();

            try {
                if(s.getLocal() != null) {
                    is = new FileInputStream(s.getLocal().toFile());
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

            if(upload != null) {
                if(s.getURI() == null && s.getLocal() != null) {
                    upload.setVisibility(View.VISIBLE);
                } else {
                    upload.setVisibility(View.GONE);
                }
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
                // TODO: use prefix?
                Path r = settings.getBaseDir()
                    .relativize(s.getLocal().getParent());
                String bucket = settings.getBucketName();
                String key = r.toString() + "/" + s.getFilename();
                Log.d(TAG, String.format("uploading: s3://%s/%s", bucket, key));
                s.setURI(Uri.parse(s3.getResourceUrl(bucket, key)));
                s3.putObject(bucket, key, s.getLocal().toFile());
                s3.setObjectAcl(bucket, key,
                        CannedAccessControlList.PublicRead);

                key = r.toString() + "/" + s.getMetadata().getFileName();
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
