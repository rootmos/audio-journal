package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;
import io.rootmos.audiojournal.databinding.ListTemplatesBinding;
import io.rootmos.audiojournal.databinding.TemplateItemBinding;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Collection;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.BaseAdapter;
import androidx.appcompat.app.AppCompatActivity;

public class ListTemplatesActivity extends AppCompatActivity {
    private Settings settings = new Settings(this);
    private ListTemplatesBinding binding = null;
    private TemplatesAdapter ta = new TemplatesAdapter();
    private TemplateItem active_template = null;
    private boolean choosing = false;

    private final int editTemplateRequestId = Utils.freshRequestCode();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ListTemplatesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Log.i(TAG, "creating list templates activity");

        ta.addTemplates(settings.loadTemplates());

        binding.templates.setAdapter(ta);

        setSupportActionBar(binding.appbar.getRoot());
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        choosing = getIntent().getBooleanExtra("choose", choosing);

        if(choosing) {
            setTitle(R.string.choose_template);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_templates_appbar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add:
                add();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "resuming list templates activity");
    }

    private class TemplateItem implements View.OnClickListener {
        private MetadataTemplate t = null;
        private TemplateItemBinding binding = null;

        public TemplateItem(MetadataTemplate t) {
            this.t = t;
        }

        public void update(MetadataTemplate t) {
            this.t = t;

            binding.titleTemplateValue.setText(t.getTitle());
            binding.artistValue.setText(t.getArtist());
            binding.composerValue.setText(t.getComposer());
            binding.prefixValue.setText(t.getPrefix().toString());
            binding.formatValue.setText(t.getFormat().toString());
        }

        public View getView(ViewGroup vg) {
            if(binding != null) return binding.getRoot();

            binding = TemplateItemBinding.inflate(getLayoutInflater());

            update(t);

            binding.getRoot().setOnClickListener(this);
            registerForContextMenu(binding.getRoot());

            return binding.getRoot();
        }

        public View getView() {
            return binding.getRoot();
        }

        @Override
        public void onClick(View w) {
            recordUsingTemplate(t);
        }

    }

    private void recordUsingTemplate(MetadataTemplate t) {
        if(choosing) {
            Intent i = new Intent();
            i.putExtra("template", t);
            setResult(RESULT_OK, i);
            finish();
        } else {
            Intent i = new Intent(this, RecordingActivity.class);
            i.putExtra("template", t);
            startActivity(i);
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

        public void remove(TemplateItem t) {
            ts.remove(t);
            notifyDataSetChanged();
        }

        public TemplateItem lookupByView(View v) {
            for(TemplateItem t : ts) {
                if(t.getView() == v) return t;
            }
            return null;
        }

        public Set<MetadataTemplate> getTemplates() {
            HashSet<MetadataTemplate> ms =
                new HashSet<>(ts.size());
            for(int i = 0; i < ts.size(); ++i) {
                ms.add(ts.get(i).t);
            }
            return ms;
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

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        active_template = ta.lookupByView(v);

        getMenuInflater().inflate(R.menu.template_item_menu, menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem i) {
        switch (i.getItemId()) {
            case R.id.record_using_template:
                recordUsingTemplate(active_template.t);
                return true;
            case R.id.edit_template:
                edit(active_template);
                return true;
            case R.id.delete_template:
                remove(active_template);
                return true;
            case R.id.use_as_default_template:
                settings.setDefaultTemplate(active_template.t);
                return true;
            default:
                return super.onContextItemSelected(i);
        }
    }

    @Override
    public void onActivityResult(int req, int rc, Intent i) {
        if(req == editTemplateRequestId && rc == RESULT_OK) {
            MetadataTemplate t = i.getParcelableExtra("template");
            if(active_template == null) {
                ta.addTemplates(t);
            } else {
                active_template.update(t);
            }

            settings.saveTemplates(ta.getTemplates());

            if(ta.getCount() == 1) {
                settings.setDefaultTemplate(t);
            }
        }
    }

    private void add() {
        active_template = null;

        MetadataTemplate t = MetadataTemplate.freshEmpty();
        Intent I = new Intent(this, EditTemplateActivity.class);
        I.putExtra("template", t);
        I.putExtra("new", true);
        startActivityForResult(I, editTemplateRequestId);
    }

    private void edit(TemplateItem t) {
        Intent I = new Intent(this, EditTemplateActivity.class);
        I.putExtra("template", t.t);
        I.putExtra("new", false);
        startActivityForResult(I, editTemplateRequestId);
    }

    private void remove(TemplateItem t) {
        Log.i(TAG, "removing template: " + t.t.getId());
        ta.remove(t);

        if(ta.getCount() == 0) {
            settings.setDefaultTemplate(null);
        } else {
            if(settings.getDefaultTemplateId().equals(t.t.getId())) {
                settings.setDefaultTemplate(null);
            }
        }

        settings.saveTemplates(ta.getTemplates());
    }
}
