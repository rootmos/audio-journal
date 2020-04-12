package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;
import io.rootmos.audiojournal.databinding.ListTemplatesBinding;
import io.rootmos.audiojournal.databinding.TemplateItemBinding;
import java.util.ArrayList;
import java.nio.file.Paths;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.BaseAdapter;
import android.view.View;
import android.view.ViewGroup;

public class ListTemplatesActivity extends Activity {
    private ListTemplatesBinding binding = null;
    private TemplatesAdapter ta = new TemplatesAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ListTemplatesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Log.i(TAG, "creating list templates activity");

        final MetadataTemplate template = new MetadataTemplate(
                "Session @ %t", "rootmos", "Gustav Behm", Format.MP3);
        template.setPrefix(Paths.get("sessions"));
        template.setFilename("%t%s");

        ta.addTemplates(template);
        binding.templates.setAdapter(ta);
    }

    private class TemplateItem {
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

            return binding.getRoot();
        }
    }

    private class TemplatesAdapter extends BaseAdapter {
        ArrayList<TemplateItem> ts = new ArrayList<>();

        public void addTemplates(MetadataTemplate... ts) {
            for(MetadataTemplate t : ts) {
                TemplateItem ti = new TemplateItem(t);
                this.ts.add(ti);
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
