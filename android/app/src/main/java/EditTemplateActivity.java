package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;
import io.rootmos.audiojournal.databinding.TemplateItemEditBinding;

import java.nio.file.Paths;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.content.Intent;
import android.view.View;

public class EditTemplateActivity extends Activity {
    private TemplateItemEditBinding binding = null;

    private MetadataTemplate t = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = TemplateItemEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Log.i(TAG, "creating edit template activity");

        t = getIntent().getParcelableExtra("template");

        binding.artistValue.setText(t.getArtist());
        binding.composerValue.setText(t.getComposer());
        binding.titleTemplateValue.setText(t.getTitle());
        binding.prefixValue.setText(t.getPrefix().toString());
        binding.filenameValue.setText(t.getFilename());

        if(t.getFormat() == Format.FLAC) {
            binding.formatValue.check(binding.formatValueFlac.getId());
        } else if(t.getFormat() == Format.MP3) {
            binding.formatValue.check(binding.formatValueMp3.getId());
        } else {
            throw new RuntimeException("unsupported format");
        }

        binding.done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { done(); } });
    }

    private void done() {
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
                binding.titleTemplateValue.getText().toString(),
                binding.artistValue.getText().toString(),
                binding.composerValue.getText().toString(),
                f);
        n.setPrefix(Paths.get(binding.prefixValue.getText().toString()));
        n.setFilename(binding.filenameValue.getText().toString());

        Intent i = new Intent();
        i.putExtra("template", n);
        setResult(RESULT_OK, i);
        finish();
    }
}
