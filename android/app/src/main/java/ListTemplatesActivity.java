package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;
import io.rootmos.audiojournal.databinding.ListTemplatesBinding;
import io.rootmos.audiojournal.databinding.TemplateItemBinding;
import java.util.ArrayList;
import java.nio.file.Paths;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

public class ListTemplatesActivity extends Activity {
    private ListTemplatesBinding binding = null;
    private TemplatesAdapter ta = new TemplatesAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ListTemplatesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Log.i(TAG, "creating list templates activity");

        // TODO: read from store
        MetadataTemplate t0 = new MetadataTemplate(
                "Session @ %t", "rootmos", "Gustav Behm", Format.FLAC);
        t0.setPrefix(Paths.get("sessions"));
        t0.setFilename("%t%s");

        MetadataTemplate t1 = new MetadataTemplate(
                "Practice @ %t", "rootmos", "Gustav Behm", Format.MP3);
        t1.setPrefix(Paths.get("practice"));
        t1.setFilename("%t%s");

        ta.addTemplates(t0, t1);
        binding.templates.setAdapter(ta);
    }

    private class TemplateItem implements View.OnClickListener {
        private MetadataTemplate t = null;
        private TemplateItemBinding binding = null;

        public TemplateItem(MetadataTemplate t) {
            this.t = t;
        }

        public View getView(ViewGroup vg) {
            if(binding != null) return binding.getRoot();

            binding = TemplateItemBinding.inflate(getLayoutInflater());

            binding.titleTemplateValue.setText(t.getTitle());
            binding.prefixValue.setText(t.getPrefix().toString());
            binding.formatValue.setText(t.getFormat().toString());

            binding.getRoot().setOnClickListener(this);

            return binding.getRoot();
        }

        @Override
        public void onClick(View w) {
            Intent i = new Intent();
            i.putExtra("template", t);
            setResult(RESULT_OK, i);
            Log.i(TAG, String.format("returning template: hashCode=%d", t.hashCode()));
            finish();
        }
    }

    private class TemplatesAdapter extends BaseAdapter {
        ArrayList<TemplateItem> ts = new ArrayList<>();

        public void addTemplates(MetadataTemplate... ts) {
            for(MetadataTemplate t : ts) {
                this.ts.add(new TemplateItem(t));
            }

            notifyDataSetChanged();
        }

        @Override
        public long getItemId(int i) {
            return ts.get(i).hashCode();
        }

        @Override
        public Object getItem(int i) { return ts.get(i); }

        @Override
        public int getCount() { return ts.size(); }

        @Override
        public View getView(int i, View v, ViewGroup vg) {
            return ts.get(i).getView(vg);
        }
    }
}
