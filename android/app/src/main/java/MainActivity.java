package io.rootmos.audiojournal;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String TAG = "AudioJournal";

    private enum State {
        IDLE, RECORDING, PLAYING
    };
    private State state = State.IDLE;

    private TextView status_text;
    private Button start_button;
    private Button stop_button;

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

    private void start_recording() {
        if(state == State.RECORDING) {
            Log.e(TAG, "illegal state transition: recording -> recording");
            return;
        } else if(state == State.PLAYING) {
            stop_playing();
        }

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
