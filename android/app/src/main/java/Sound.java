package io.rootmos.audiojournal;

import android.net.Uri;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

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

    private Uri uri = null;
    private File local = null;

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

    public void setURI(Uri uri) {
        this.uri = uri;
    }

    public void setLocal(File path) {
        this.local = path;
    }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getComposer() { return composer; }
    public float getDuration() { return duration; }
    public OffsetDateTime getDateTime() { return datetime; }
    public LocalDate getDate() { return date; }
    public byte[] getSHA1() { return sha1; }
    public Uri getURI() { return uri; }
    public File getLocal() { return local; }

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
            s.setURI(Uri.parse(j.getString("url")));
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
            j.put("url", JSONObject.NULL);
            if(local != null) j.put("filename", local.getName());
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
        } catch(JSONException e) {
            throw new RuntimeException("unable to populate JSON object", e);
        }
        return j.toString();
    }
}
