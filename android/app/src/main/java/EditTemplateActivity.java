package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;
import io.rootmos.audiojournal.databinding.TemplateItemEditBinding;

import java.nio.file.Paths;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;

public class EditTemplateActivity extends AppCompatActivity {
    private TemplateItemEditBinding binding = null;

    private MetadataTemplate t = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = TemplateItemEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Log.i(TAG, "creating edit template activity");

        t = getIntent().getParcelableExtra("template");

        Log.i(TAG, "editing template: " + t.getId());

        binding.artistValue.setText(t.getArtist());
        binding.composerValue.setText(t.getComposer());
        binding.titleTemplateValue.setText(t.getTitle());
        binding.prefixValue.setText(t.getPrefix().toString());
        binding.filenameValue.setText(t.getFilename());
        binding.autoUploadValue.setChecked(t.getAutoUpload());

        if(t.getFormat() == Format.FLAC) {
            binding.formatValue.check(binding.formatValueFlac.getId());
        } else if(t.getFormat() == Format.MP3) {
            binding.formatValue.check(binding.formatValueMp3.getId());
        } else {
            throw new RuntimeException("unsupported format");
        }

        setSupportActionBar(binding.appbar.getRoot());
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        if(getIntent().getBooleanExtra("new", false)) {
            setTitle(R.string.add_template);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.template_item_edit_appbar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.done:
                done();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "stopping edit template activity");
    }

    private void done() {
        Log.i(TAG, "done editing template: " + t.getId());

        Format f = null;
        int fi = binding.formatValue.getCheckedRadioButtonId();
        if(fi == binding.formatValueFlac.getId()) {
            f = Format.FLAC;
        } else if(fi == binding.formatValueMp3.getId()) {
            f = Format.MP3;
        } else {
            throw new RuntimeException("unsupported format");
        }

        MetadataTemplate n = new MetadataTemplate(
                t.getId(),
                binding.titleTemplateValue.getText().toString(),
                binding.artistValue.getText().toString(),
                binding.composerValue.getText().toString(),
                f,
                binding.autoUploadValue.isChecked(),
                binding.prefixValue.getText().toString(),
                binding.filenameValue.getText().toString());

        Intent i = new Intent();
        i.putExtra("template", n);
        setResult(RESULT_OK, i);
        finish();
    }
}
