package io.rootmos.audiojournal;

import static io.rootmos.audiojournal.Common.TAG;

import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.digest.MessageDigestAlgorithms;

class MetadataTemplate {
    private String title = null;
    private String artist = null;
    private String composer = null;

    private File dir = null;
    private String filename = null;

    private String suffix = null;

    public MetadataTemplate(
            String title, String artist, String composer, String suffix) {
        this.title = title;
        this.artist = artist;
        this.composer = composer;
        this.suffix = suffix;
    }

    public void setTargetDir(File dir) { this.dir = dir; }
    public void setFilename(String filename) { this.filename = filename; }

    private String renderString(String template,
            OffsetDateTime time, float length, String title) {
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

    public Sound renderLocalFile(File path, OffsetDateTime time, float length) {
        try {
            String title = renderString(this.title, time, length, null);
            Log.d(TAG, String.format("rendered title: %s", title));

            if(dir != null) {
                File dest = filename == null
                    ? new File(dir, path.getName())
                    : new File(dir, renderString(
                                filename, time, length, title));

                dir.mkdirs();

                Log.i(TAG, String.format("copying: %s -> %s", path, dest));

                path = Files.copy(path.toPath(), dest.toPath()).toFile();
            }

            byte[] sha1 = new DigestUtils(MessageDigestAlgorithms.SHA_1)
                .digest(path);

            AudioFile af = AudioFileIO.read(path);
            Tag t = af.getTag();
            t.setField(FieldKey.TITLE, title);
            t.setField(FieldKey.ARTIST, artist);
            t.setField(FieldKey.COMPOSER, composer);
            t.setField(FieldKey.YEAR,
                    time.format(DateTimeFormatter.ofPattern("%y")));
            af.commit();
            Log.d(TAG, String.format("tagged: %s", path));

            Sound s = new Sound(title, artist, composer, sha1, length);
            s.setLocal(path);
            s.setDateTime(time);

            File m = new File(path.getParentFile(), path.getName().replaceAll(
                        String.format("%s$", suffix), ".json"));

            Files.write(m.toPath(), s.toJSON().getBytes("UTF-8"));
            Log.d(TAG, "metadata written to: " + m);

            return s;
        } catch(Exception e) {
            throw new RuntimeException(
                    "exception while rendering local file: " + path, e);
        }
    }
}
