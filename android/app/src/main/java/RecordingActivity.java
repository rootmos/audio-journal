package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;
import io.rootmos.audiojournal.databinding.ActivityRecordingBinding;

import java.io.File;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
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
import android.widget.TextView;

import androidx.core.app.ActivityCompat;

public class RecordingActivity extends Activity implements
    RecordingService.OnProgressListener,
    RecordingService.OnStateChangeListener {

    private ActivityRecordingBinding binding = null;

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

        binding = ActivityRecordingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.status.duration.setAutoSizeTextTypeWithDefaults(
                TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);

        final MetadataTemplate template = new MetadataTemplate(
                "Session @ %t", "rootmos", "Gustav Behm", Format.MP3);
        template.setPrefix(Paths.get("sessions"));
        template.setFilename("%t%s");

        binding.status.titleTemplateValue.setText(template.getTitle());
        binding.status.formatValue.setText(template.getFormat().toString());

        if(template.getPrefix() != null) {
            binding.status.prefixValue.setText(template.getPrefix().toString());
            binding.status.prefix.setVisibility(View.VISIBLE);
        }

        binding.start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                RecordingService.start(RecordingActivity.this, template,
                        settings.getBaseDir(),
                        settings.getTakesDir());
            }
        });

        binding.stop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                rs.stop();
            }
        });
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
        binding.status.duration.setText(
                Utils.formatDurationLong(p.getSeconds()));
        binding.status.currentGain.setProgress(p.getGainPercent());
        binding.status.maxGain.setProgress(p.getMaxGainPercent());
        binding.status.clippedSamplesValue.setText(
                String.format("%d", p.getClippedSamples()));
    }

    @Override
    public void recordingStarted(RecordingService.Started started) {
        applyRecordingState();

        binding.status.titleValue.setText(started.getTitle());
        binding.status.title.setVisibility(View.VISIBLE);

        binding.status.dateValue.setText(started.getTime().withNano(0)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        binding.status.date.setVisibility(View.VISIBLE);
    }

    @Override
    public void recordingCompleted(Sound s) {
        applyRecordingState();

        finish();
    }

    public void applyRecordingState() {
        Log.d(TAG, "applying recording state: " + rs.isRecording());
        if(rs.isRecording()) {
            binding.status.clippedSamples.setVisibility(View.VISIBLE);
            binding.status.titleTemplate.setVisibility(View.GONE);
            binding.start.setVisibility(View.GONE);
            binding.stop.setVisibility(View.VISIBLE);
        } else {
            binding.status.clippedSamples.setVisibility(View.GONE);
            binding.status.title.setVisibility(View.GONE);
            binding.status.date.setVisibility(View.GONE);
            binding.start.setVisibility(View.VISIBLE);
            binding.stop.setVisibility(View.GONE);
        }
    }
}
