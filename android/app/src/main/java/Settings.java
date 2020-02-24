package io.rootmos.audiojournal;

import java.io.File;

import android.content.Context;

class Settings {
    private Context ctx = null;

    public Settings(Context ctx) {
        this.ctx = ctx;
    }

    public File getBaseDir() {
        File[] rs = ctx.getExternalMediaDirs();
        if(rs.length == 0) {
            throw new RuntimeException("no media dirs");
        }
        return rs[0];
    }

    public File getTakesDir() {
        return new File(getBaseDir(), "takes");
    }

    public File getUpstreamCacheDir() {
        File cache = new File(ctx.getCacheDir(), "upstream");
        cache.mkdirs();
        return cache;
    }

    public String getBucketName() {
        return "rootmos-sounds";
    }

    public String getBucketRegion() {
        return "eu-central-1";
    }
}
