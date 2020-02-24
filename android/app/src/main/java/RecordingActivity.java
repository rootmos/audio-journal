package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ProgressBar;

import androidx.core.app.ActivityCompat;

public class RecordingActivity extends Activity implements
    RecordingService.OnProgressListener,
    RecordingService.OnStateChangeListener {

    private TextView status_text = null;
    private TextView clipped_samples = null;
    private View recordingMetadata = null;
    private Button start_button = null;
    private Button stop_button = null;

    private ProgressBar currentGain = null;
    private ProgressBar maxGain = null;

    private Set<String> granted_permissions = new HashSet<>();

    private Settings settings = new Settings(this);

    private RecordingService.Binder rs = null;
    ServiceConnection sc = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "recording activity connected to recording service");
            rs = (RecordingService.Binder)service;
            rs.addProgressListener(RecordingActivity.this);
            rs.addStateChangeListener(RecordingActivity.this);

            applyRecordingState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "recording activity disconnected to recording service");
            rs = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "creating recording activity");

        setContentView(R.layout.activity_recording);

        status_text = (TextView)findViewById(R.id.status);
        status_text.setAutoSizeTextTypeWithDefaults(
                TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);

        clipped_samples = (TextView)findViewById(R.id.clipped_samples);

        start_button = (Button)findViewById(R.id.start_recording);
        start_button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                MetadataTemplate template = new MetadataTemplate(
                        "Session @ %t", "rootmos", "Gustav Behm", ".flac");
                template.setTargetDir(new File(settings.getBaseDir(), "sessions"));
                template.setFilename("%t%s");

                RecordingService.start(RecordingActivity.this,
                        template, settings.getTakesDir());
            }
        });

        stop_button = (Button)findViewById(R.id.stop_recording);
        stop_button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                rs.stop();
            }
        });

        currentGain = (ProgressBar)findViewById(R.id.current_gain);
        maxGain = (ProgressBar)findViewById(R.id.max_gain);

        recordingMetadata = findViewById(R.id.recording_metadata);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "stopping recording activity");
        rs.removeProgressListener(this);
        rs.removeStateChangeListener(this);
        unbindService(sc);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "resuming recording activity");

        RecordingService.bind(this, sc);

        ensurePermissionGranted(Manifest.permission.RECORD_AUDIO);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "destroying recording activity");
    }

    private void ensurePermissionGranted(String... permissions) {
        if(!granted_permissions.containsAll(Arrays.asList(permissions))) {
            ActivityCompat.requestPermissions(this, permissions, 0);
        } else {
            for(String p : permissions) {
                Log.d(TAG, "permission already granted: " + p);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        for(int i = 0; i < permissions.length; ++i) {
            if(grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "was granted: " + permissions[i]);
                granted_permissions.add(permissions[i]);
            }
        }
    }

    @Override
    public void recordingProgress(RecordingService.Progress p) {
        status_text.setText(Utils.formatDurationLong(p.getSeconds()));
        currentGain.setProgress(p.getGainPercent());
        maxGain.setProgress(p.getMaxGainPercent());
        clipped_samples.setText(String.format("%d", p.getClippedSamples()));
    }

    @Override
    public void recordingStarted() {
        applyRecordingState();
    }

    @Override
    public void recordingCompleted(Sound s) {
        applyRecordingState();

        finish();
    }

    public void applyRecordingState() {
        Log.d(TAG, "applying recording state: " + rs.isRecording());
        if(rs.isRecording()) {
            recordingMetadata.setVisibility(View.VISIBLE);
            start_button.setVisibility(View.GONE);
            stop_button.setVisibility(View.VISIBLE);
        } else {
            recordingMetadata.setVisibility(View.INVISIBLE);
            start_button.setVisibility(View.VISIBLE);
            stop_button.setVisibility(View.GONE);
        }
    }
}
