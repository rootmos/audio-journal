package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;
import io.rootmos.audiojournal.databinding.ListTemplatesBinding;
import io.rootmos.audiojournal.databinding.TemplateItemBinding;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Random;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MenuItem;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.BaseAdapter;

public class ListTemplatesActivity extends Activity {
    private ListTemplatesBinding binding = null;
    private TemplatesAdapter ta = new TemplatesAdapter();
    private TemplateItem active_template = null;

    private int editTemplateRequestId = new Random().nextInt();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ListTemplatesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Log.i(TAG, "creating list templates activity");

        if(savedInstanceState != null) {
            ArrayList<MetadataTemplate> ts =
                savedInstanceState.getParcelableArrayList("templates");
            ta.addTemplates(ts.toArray(new MetadataTemplate[0]));
        }

        binding.templates.setAdapter(ta);

        binding.add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { add(); } });
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
            returnTemplate(t);
        }

    }

    private void returnTemplate(MetadataTemplate t) {
        Intent i = new Intent();
        i.putExtra("template", t);
        setResult(RESULT_OK, i);
        finish();
    }

    private class TemplatesAdapter extends BaseAdapter {
        ArrayList<TemplateItem> ts = new ArrayList<>();

        public void addTemplates(MetadataTemplate... ts) {
            for(MetadataTemplate t : ts) {
                this.ts.add(new TemplateItem(t));
            }

            notifyDataSetChanged();
        }

        public TemplateItem lookupByView(View v) {
            for(TemplateItem t : ts) {
                if(t.getView() == v) return t;
            }
            return null;
        }

        public ArrayList<MetadataTemplate> getTemplates() {
            ArrayList<MetadataTemplate> ms =
                new ArrayList<>(ts.size());
            for(int i = 0; i < ts.size(); ++i) {
                ms.set(i, ts.get(i).t);
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
                returnTemplate(active_template.t);
                return true;
            case R.id.edit_template: {
                Intent I = new Intent(this, EditTemplateActivity.class);
                I.putExtra("template", active_template.t);
                startActivityForResult(I, editTemplateRequestId);
                return true;
            }
            case R.id.delete_template:
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
        }
    }

    private void add() {
        active_template = null;

        MetadataTemplate t = MetadataTemplate.freshEmpty();
        Intent I = new Intent(this, EditTemplateActivity.class);
        I.putExtra("template", t);
        startActivityForResult(I, editTemplateRequestId);
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);

        savedInstanceState.putParcelableArrayList(
                "templates", ta.getTemplates());
    }
}
