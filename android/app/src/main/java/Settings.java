package io.rootmos.audiojournal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;

import android.content.Context;

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

    public String getBucketName() {
        return "rootmos-sounds";
    }

    public String getBucketRegion() {
        return "eu-central-1";
    }
}
