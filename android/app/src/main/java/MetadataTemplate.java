package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.digest.MessageDigestAlgorithms;

import org.json.JSONTokener;
import org.json.JSONObject;
import org.json.JSONException;

class MetadataTemplate implements Parcelable {
    private UUID id = null;
    private String title = null;
    private String artist = null;
    private String composer = null;
    private String prefix = null;
    private String filename = null;
    private String suffix = null;
    private Format format = null;

    public MetadataTemplate(
            UUID id,
            String title, String artist, String composer, Format format) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.composer = composer;

        this.format = format;
        this.suffix = selectSuffix(this.format);
    }

    public static MetadataTemplate freshEmpty() {
        MetadataTemplate t = new MetadataTemplate(
                UUID.randomUUID(),
                "", "", "",
                Format.MP3);
        t.setPrefix("");
        t.setFilename("%t%s");
        return t;
    }

    private String selectSuffix(Format format) {
        if(format == Format.FLAC) {
            return ".flac";
        } else if(format == Format.MP3) {
            return ".mp3";
        } else {
            throw new RuntimeException("unsupported format");
        }
    }

    public int hashCode() {
        return id.hashCode();
    }

    public UUID getId() { return id; }
    public String getArtist() { return artist; }
    public String getComposer() { return composer; }
    public String getTitle() { return title; }
    public String getSuffix() { return suffix; }
    public String getPrefix() { return prefix; }
    public Format getFormat() { return format; }
    public String getFilename() { return filename; }

    public void setPrefix(String prefix) { this.prefix = prefix; }
    public void setFilename(String filename) { this.filename = filename; }

    @Override
    public int describeContents () { return 0; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(id.toString());
        out.writeString(title);
        out.writeString(artist);
        out.writeString(composer);
        out.writeTypedObject(format, flags);
        out.writeString(prefix);
        out.writeString(filename);
    }

    public static final Parcelable.Creator<MetadataTemplate> CREATOR =
        new Parcelable.Creator<MetadataTemplate>() {
            public MetadataTemplate createFromParcel(Parcel in) {
                MetadataTemplate mt = new MetadataTemplate(
                        UUID.fromString(in.readString()),
                        in.readString(),
                        in.readString(),
                        in.readString(),
                        in.readTypedObject(Format.CREATOR));
                String prefix = in.readString();
                if(prefix != null) mt.setPrefix(prefix);
                mt.setFilename(in.readString());
                return mt;
            }

            public MetadataTemplate[] newArray(int size) {
                return new MetadataTemplate[size];
            }
        };

    private String renderString(String template,
            OffsetDateTime time, String title) {
        String s = template;
        if(artist != null) s = s.replaceAll("%a", artist);
        if(composer != null) s = s.replaceAll("%c", composer);
        if(time != null) {
            s = s.replaceAll("%t", time.withNano(0)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        if(suffix != null) s = s.replaceAll("%s", suffix);
        if(title != null) {
            s = s.replaceAll("%T", title);
            s = s.replaceAll("%-T", title.replace(" ", "-"));
        }
        return s.replaceAll("%%", "%");
    }

    public String renderTitle(OffsetDateTime time) {
        return renderString(this.title, time, null);
    }

    public Sound renderLocalFile(Path dest, Path src,
            OffsetDateTime time, float length) {
        try {
            String title = renderTitle(time);
            Log.d(TAG, String.format("rendered title: %s", title));

            if(dest != null) {
                if(prefix != null) {
                    dest = dest.resolve(prefix);
                }

                if(filename == null) {
                    dest = dest.resolve(src.getFileName());
                } else {
                    dest = dest.resolve(renderString(filename, time, title));
                }

                if(dest.getParent() != null) {
                    Files.createDirectories(dest.getParent());
                }

                Log.i(TAG, String.format("copying: %s -> %s", src, dest));

                dest = Files.copy(src, dest);
            } else {
                dest = src;
            }

            byte[] sha1 = new DigestUtils(MessageDigestAlgorithms.SHA_1)
                .digest(dest);

            AudioFile af = AudioFileIO.read(dest.toFile());
            Tag t = af.getTagOrCreateDefault();
            t.setField(FieldKey.TITLE, title);
            t.setField(FieldKey.ARTIST, artist);
            t.setField(FieldKey.COMPOSER, composer);
            t.setField(FieldKey.YEAR,
                    time.format(DateTimeFormatter.ofPattern("y")));
            af.commit();
            Log.d(TAG, String.format("tagged: %s", dest));

            Sound s = new Sound(title, artist, composer, sha1, length);
            s.setLocal(dest);
            s.setDateTime(time);
            s.setMimeType(format.getMimeType());

            Path m = dest.resolveSibling(dest.getFileName().toString()
                    .replaceAll(String.format("%s$", suffix), ".json"));
            s.setMetadata(m);

            Files.write(m, s.toJSON().getBytes("UTF-8"));
            Log.d(TAG, "metadata written to: " + m);

            return s;
        } catch(Exception e) {
            throw new RuntimeException(
                    "exception while rendering local file: " + dest, e);
        }
    }

    public String toJSON() {
        JSONObject j = new JSONObject();
        try {
            j.put("id", id.toString());
            j.put("title", title);
            j.put("artist", artist);
            j.put("composer", composer);
            j.put("prefix", prefix);
            j.put("filename", filename);
            j.put("format", format.toString());
        } catch(JSONException e) {
            throw new RuntimeException("unable to populate JSON object", e);
        }
        return j.toString();
    }

    static public MetadataTemplate fromJSON(String raw) {
        JSONObject j = null;
        try {
            j = (JSONObject) new JSONTokener(raw).nextValue();
        } catch(JSONException e) {
            throw new RuntimeException("unable to parse object content", e);
        } catch(ClassCastException e) {
            throw new RuntimeException("expected JSON object not present", e);
        }

        MetadataTemplate t = null;
        try {
            t = new MetadataTemplate(
                    UUID.fromString(j.getString("id")),
                    j.getString("title"),
                    j.getString("artist"),
                    j.getString("composer"),
                    Format.fromString(j.getString("format")));
            t.setPrefix(j.getString("prefix"));
            t.setFilename(j.getString("filename"));
        } catch(JSONException e) {
            throw new RuntimeException("illstructured JSON content", e);
        }

        return t;
    }
}
