package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

class Settings {
    private Context ctx = null;

    public Settings(Context ctx) {
        this.ctx = ctx;
    }

    public Path getBaseDir() {
        File[] rs = ctx.getExternalMediaDirs();
        if(rs.length == 0) {
            throw new RuntimeException("no media dirs");
        }
        return rs[0].toPath();
    }

    public Path getTakesDir() {
        return getBaseDir().resolve("takes");
    }

    public Path getUpstreamCacheDir() {
        Path cache = ctx.getCacheDir().toPath().resolve("upstream");
        try {
            Files.createDirectories(cache);
        } catch(IOException e) {
            throw new RuntimeException("unable to create cache", e);
        }
        return cache;
    }

    private SharedPreferences getPreferences() {
        return ctx.getSharedPreferences("preferences", Context.MODE_PRIVATE);
    }

    public String getBucketName() {
        return "rootmos-sounds";
    }

    public String getBucketRegion() {
        return "eu-central-1";
    }

    public MetadataTemplate[] loadTemplates() {
        Set<String> ss = getPreferences()
            .getStringSet("templates", new HashSet<String>());
        ArrayList<MetadataTemplate> ms = new ArrayList<>();
        for(String s : ss) {
            ms.add(MetadataTemplate.fromJSON(s));
        }
        return ms.toArray(new MetadataTemplate[0]);
    }

    public void saveTemplates(Collection<MetadataTemplate> ms) {
        HashSet<String> ss = new HashSet<>(ms.size());
        for(MetadataTemplate t : ms) {
            ss.add(t.toJSON());
        }

        getPreferences().edit().putStringSet("templates", ss).apply();
    }

    public UUID getDefaultTemplateId() {
        String s = getPreferences().getString("default_template", null);
        if(s == null) return null;
        return UUID.fromString(s);
    }

    public MetadataTemplate getDefaultTemplate() {
        UUID id = getDefaultTemplateId();
        if(id == null) return null;

        for(MetadataTemplate t : loadTemplates()) {
            if(id.equals(t.getId())) {
                return t;
            }
        }

        throw new RuntimeException("can't find default template: " + id);
    }

    public void setDefaultTemplate(MetadataTemplate t) {
        if(t == null) {
            Log.i(TAG, "resetting default template");
            getPreferences().edit().remove("default_template").apply();
        } else {
            Log.i(TAG, "setting default template: " + t.getId());
            getPreferences().edit()
                .putString("default_template", t.getId().toString()).apply();
        }
    }
}
