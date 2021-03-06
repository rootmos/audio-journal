package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;

import android.net.Uri;
import android.util.Log;
import android.content.Intent;
import android.content.Context;
import androidx.core.content.FileProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONTokener;
import org.json.JSONObject;
import org.json.JSONException;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

class Sound implements Comparable<Sound> {
    private byte[] sha1 = null;
    private String title = null;
    private String artist = null;
    private String composer = null;
    private float duration = 0;
    private String filename = null;
    private String mimeType = null;

    private Uri uri = null;
    private Path local = null;
    private Path metadata = null;

    private OffsetDateTime datetime = null;
    private LocalDate date = null;

    public Sound(String title, String artist, String composer,
            byte[] sha1, float duration) {
        this.title = title;
        this.artist = artist;
        this.composer = composer;
        this.sha1 = sha1;
        this.duration = duration;
    }

    public void setDateTime(OffsetDateTime dt) {
        datetime = dt;
        date = dt.toLocalDate();
    }

    public void setLocal(Path  path) { this.local = path; }
    public void setURI(Uri uri) { this.uri = uri; }
    public void setMetadata(Path metadata) { this.metadata = metadata; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getComposer() { return composer; }
    public float getDuration() { return duration; }
    public OffsetDateTime getDateTime() { return datetime; }
    public LocalDate getDate() { return date; }
    public byte[] getSHA1() { return sha1; }
    public Uri getURI() { return uri; }
    public Path getLocal() { return local; }
    public String getFilename() { return filename; }
    public Path getMetadata() { return metadata; }

    public int hashCode() {
        return ByteBuffer.wrap(sha1).getInt();
    }

    public int compareTo(Sound o) {
        if(date.isEqual(o.date)) {
            if(datetime != null && o.datetime != null) {
                if(datetime.isEqual(o.datetime)) {
                    return 0;
                } else {
                    return datetime.isBefore(o.datetime) ? -1 : 1;
                }
            } else {
                return 0;
            }
        } else {
            return date.isBefore(o.date) ?  -1 : 1;
        }
    }

    public void merge(Sound o) {
        if(!Arrays.equals(sha1, o.sha1)) {
            throw new RuntimeException("merging sounds with different hashes");
        }

        if(o.filename != null) filename = o.filename;
        if(o.local != null) local = o.local;
        if(o.uri != null) uri = o.uri;
        if(o.metadata != null) metadata = o.metadata;
    }

    static public List<Sound> scanDir(Path d) {
        final ArrayList<Sound> ss = new ArrayList<>();
        try {
            Files.walkFileTree(d, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path p,
                        BasicFileAttributes attrs) throws IOException {
                    Log.d(TAG, "considering: " + p);
                    if(p.toFile().getName().endsWith(".json")) {
                        ss.add(fromLocalFile(p));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch(IOException e) {
            Log.e(TAG, "exception while scanning: " + d, e);
            return null;
        }
        return ss;
    }

    static public Sound fromLocalFile(Path m) throws FileNotFoundException {
        Log.d(TAG, "reading local metadata: " + m);
        Sound s = fromInputStream(new FileInputStream(m.toFile()));
        s.metadata = m;
        Path p = m.getParent().resolve(s.filename);
        if(Files.exists(p)) {
            s.local = p;
        } else {
            Log.w(TAG, "corresponding audio file not found: " + p);
        }
        return s;
    }

    static public Sound fromInputStream(InputStream is) {
        String raw = null;
        try {
            raw = Utils.stringFromInputStream(is);
        } catch(IOException e) {
            throw new RuntimeException("unable to read object content", e);
        }

        return fromJSON(raw);
    }

    static public Sound fromJSON(String raw) {
        JSONObject j = null;
        try {
            j = (JSONObject) new JSONTokener(raw).nextValue();
        } catch(JSONException e) {
            throw new RuntimeException("unable to parse object content", e);
        } catch(ClassCastException e) {
            throw new RuntimeException("expected JSON object not present", e);
        }

        Sound s = null;
        try {
            String t = j.getString("title");
            String a = j.getString("artist");
            String c = j.getString("composer");
            float d = (float)j.getDouble("length");
            byte[] sha1 = Hex.decodeHex(j.getString("sha1"));

            s = new Sound(t, a, c, sha1, d);

            if(!j.isNull("url")) {
                String u = j.getString("url");
                s.uri = Uri.parse(u);
            }
            s.filename = j.getString("filename");

            s.mimeType = j.optString("mimetype");
            if(s.mimeType == null || s.mimeType == "") {
                s.mimeType = Format.guessBasedOnFilename(s.filename)
                    .getMimeType();
            }
        } catch(DecoderException e) {
            throw new RuntimeException("unable to hex decode", e);
        } catch(JSONException e) {
            throw new RuntimeException("illstructured JSON content", e);
        }

        try {
            String d = j.getString("date");
            try {
                s.datetime = OffsetDateTime.parse(d);
                s.date = s.datetime.toLocalDate();
            } catch(DateTimeParseException e) {
                s.date = LocalDate.parse(d);
            }
        } catch(JSONException e) {
            throw new RuntimeException("illstructured date field", e);
        }

        return s;
    }

    public String toJSON() {
        JSONObject j = new JSONObject();
        try {
            j.put("title", title);
            j.put("sha1", Hex.encodeHexString(sha1));
            j.put("url", uri != null ? uri.toString() : JSONObject.NULL);
            if(local != null) {
                j.put("filename", local.getFileName());
            } else if(filename != null) {
                j.put("filename", filename);
            }
            j.put("artist", artist);
            j.put("composer", composer);
            if(datetime != null) {
                j.put("date", datetime.format(
                            DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            } else {
                j.put("date", date.format(DateTimeFormatter.ISO_DATE));
            }
            j.put("year", date.getYear());
            j.put("length", duration);
            j.put("mimetype", mimeType);
        } catch(JSONException e) {
            throw new RuntimeException("unable to populate JSON object", e);
        }
        return j.toString();
    }

    public Intent getShareIntent(Context ctx) {
        Intent i = new Intent(Intent.ACTION_VIEW);

        Uri u = null;
        if(local != null) {
            u = FileProvider.getUriForFile(ctx,
                    ctx.getApplicationContext().getPackageName() + ".provider",
                    local.toFile());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if(uri != null) {
            u = uri.normalizeScheme();
        }
        i.setDataAndType(u, mimeType);

        return i;
    }
}
