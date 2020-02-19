package io.rootmos.audiojournal;

import android.net.Uri;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.json.JSONTokener;
import org.json.JSONObject;
import org.json.JSONException;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

class Sound {
    private Uri uri = null;
    private byte[] sha1 = null;
    private String title = null;
    private String artist = null;
    private String composer = null;
    private float duration = 0;

    private OffsetDateTime datetime = null;
    private LocalDate date = null;

    public Sound(String title, String artist, String composer,
            byte[] sha1, Uri uri, float duration) {
        this.title = title;
        this.artist = artist;
        this.composer = composer;
        this.sha1 = sha1;
        this.uri = uri;
        this.duration = duration;
    }

    public void setDateTime(OffsetDateTime dt) {
        datetime = dt;
        date = dt.toLocalDate();
    }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getComposer() { return composer; }
    public float getDuration() { return duration; }
    public OffsetDateTime getDateTime() { return datetime; }
    public LocalDate getDate() { return date; }
    public byte[] getSHA1() { return sha1; }
    public Uri getURI() { return uri; }

    public int hashCode() {
        return ByteBuffer.wrap(sha1).getInt();
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
            Uri uri = Uri.parse(j.getString("url"));

            s = new Sound(t, a, c, sha1, uri, d);
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
}
