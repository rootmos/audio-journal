package io.rootmos.audiojournal;

import android.net.Uri;

import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.json.JSONTokener;
import org.json.JSONObject;
import org.json.JSONException;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

class Sound {
    private Uri uri = null;
    private String title = null;
    private byte[] sha1 = null;

    public Sound(String title, byte[] sha1, Uri uri) {
        this.title = title;
        this.sha1 = sha1;
        this.uri = uri;
    }

    public String getTitle() { return title; }
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

        try {
            String t = j.getString("title");
            byte[] sha1 = Hex.decodeHex(j.getString("sha1"));
            Uri uri = Uri.parse(j.getString("url"));
            return new Sound(t, sha1, uri);
        } catch(DecoderException e) {
            throw new RuntimeException("unable to hex decode", e);
        } catch(JSONException e) {
            throw new RuntimeException("illstructured JSON content", e);
        }
    }
}
