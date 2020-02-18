package io.rootmos.audiojournal;

import android.net.Uri;

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

    public MetadataTemplate(String title, String artist, String composer) {
        this.title = title;
        this.artist = artist;
        this.composer = composer;
    }

    public void setTargetDir(File dir) { this.dir = dir; }
    public void setFilename(String filename) { this.filename = filename; }

    private String renderString(String template,
            OffsetDateTime time, float length) {
        String s = template;
        if(artist != null) s = s.replaceAll("%a", artist);
        if(composer != null) s = s.replaceAll("%c", composer);
        if(time != null) {
            s = s.replaceAll("%t",
                    time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        return s.replaceAll("%%", "%");
    }

    public Sound renderLocalFile(File path, OffsetDateTime time, float length) {
        try {
            if(dir != null) {
                File dest;
                if(filename == null) {
                    dest = new File(dir, path.getName());
                } else {
                    dest = new File(dir, renderString(filename, time, length));
                }

                dir.mkdirs();

                path = Files.copy(path.toPath(), dest.toPath()).toFile();
            }

            byte[] sha1 = new DigestUtils(MessageDigestAlgorithms.SHA_1)
                .digest(path);

            String title = renderString(this.title, time, length);

            AudioFile af = AudioFileIO.read(path);
            Tag t = af.getTag();
            t.setField(FieldKey.TITLE, title);
            t.setField(FieldKey.ARTIST, artist);
            t.setField(FieldKey.COMPOSER, composer);
            t.setField(FieldKey.YEAR,
                    time.format(DateTimeFormatter.ofPattern("%y")));
            af.commit();

            return new Sound(title, sha1, Uri.fromFile(path));
        } catch(Exception e) {
            throw new RuntimeException(
                    "exception while rendering local file: " + path, e);
        }
    }
}
