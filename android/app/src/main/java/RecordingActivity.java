package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class RecordingActivity extends Activity implements
    RecordingService.OnProgressListener,
    RecordingService.OnStateChangeListener {

    private TextView status_text = null;
    private Button stop_button = null;

    private RecordingService.Binder rs = null;
    ServiceConnection sc = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "recording activity connected to recording service");
            rs = (RecordingService.Binder)service;
            if(!rs.isRecording()) {
                finish();
            } else {
                rs.addProgressListener(RecordingActivity.this);
                rs.addStateChangeListener(RecordingActivity.this);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "recording activity disconnected to recording service");
            rs = null;
        }
    };

    public void recordingProgress(float s) {
        status_text.setText(String.format("Recording: %s",
                    Utils.formatDuration(s)));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_recording);

        status_text = (TextView)findViewById(R.id.status);

        stop_button = (Button)findViewById(R.id.stop);
        stop_button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                rs.stop();
            }
        });

        Log.i(TAG, "creating recording activity");
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "destroying recording activity");
    }

    @Override
    public void recordingStarted() {
    }

    @Override
    public void recordingCompleted(Sound s) {
        finish();
    }
}
