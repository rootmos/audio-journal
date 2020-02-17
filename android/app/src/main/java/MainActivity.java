package io.rootmos.audiojournal;

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.media.AudioRecord;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;

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

    private Set<String> granted_permissions = new HashSet<>();

    private AudioRecord recorder = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status_text = (TextView)findViewById(R.id.status);
        start_button = (Button)findViewById(R.id.start);
        stop_button = (Button)findViewById(R.id.stop);

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

        recorder = new AudioRecord.Builder().build();
        recorder.startRecording();

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

        recorder.stop();

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
}
