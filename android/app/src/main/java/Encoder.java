package io.rootmos.audiojournal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import net.sourceforge.javaflacencoder.FLACEncoder;
import net.sourceforge.javaflacencoder.FLACFileOutputStream;
import net.sourceforge.javaflacencoder.StreamConfiguration;
import net.sourceforge.javaflacencoder.EncodingConfiguration;

import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;

public abstract class Encoder {
    public abstract void update(short samples[]) throws IOException;
    public abstract void finalize() throws IOException;

    public abstract int getSamplesCaptured();
    public abstract int getSamplesEncoded();

    public static Encoder PCM16(
            MetadataTemplate.Format format,
            Path out,
            int sampleRate) throws IOException {
        if(format == MetadataTemplate.Format.FLAC) {
            final FLACEncoder encoder = new FLACEncoder();
            StreamConfiguration sc = new StreamConfiguration();
            sc.setChannelCount(2);
            sc.setSampleRate(sampleRate);
            sc.setBitsPerSample(16);
            encoder.setStreamConfiguration(sc);

            EncodingConfiguration ec = new EncodingConfiguration();
            ec.setSubframeType(EncodingConfiguration.SubframeType.EXHAUSTIVE);
            encoder.setEncodingConfiguration(ec);

            final FLACFileOutputStream os = new FLACFileOutputStream(
                    out.toFile());
            if(!os.isValid()) {
                throw new RuntimeException("can't open output stream");
            }
            encoder.setOutputStream(os);
            encoder.openFLACStream();

            return new Encoder() {
                private int samples_encoded = 0;
                private int samples_captured = 0;

                public int getSamplesCaptured() { return samples_captured; }
                public int getSamplesEncoded() { return samples_encoded; }

                public void update(short samples[]) throws IOException {
                    int[] is = new int[samples.length];
                    for(int i = 0; i < samples.length; ++i) {
                        is[i] = samples[i];
                    }

                    samples_captured += samples.length;

                    encoder.addSamples(is, is.length/2);

                    int enc;
                    if((enc = encoder.fullBlockSamplesAvailableToEncode()) > 0) {
                        int r = encoder.encodeSamples(enc, false);
                        samples_encoded += r * 2;
                    }
                }

                public void finalize() throws IOException {
                    int s = samples_encoded == samples_captured ? 0 :
                        encoder.samplesAvailableToEncode();
                    int r = encoder.encodeSamples(s, true);
                    if(r < s) {
                        encoder.encodeSamples(s, true);
                    }
                    os.close();
                }
            };
        } else if(format == MetadataTemplate.Format.MP3) {
            final AndroidLame lame = new LameBuilder()
                .setInSampleRate(sampleRate)
                .setOutBitrate(320)
                .build();

            final OutputStream os = Files.newOutputStream(out);

            return new Encoder() {
                private int samples_encoded = 0;
                private int samples_captured = 0;

                public int getSamplesCaptured() { return samples_captured; }
                public int getSamplesEncoded() { return samples_encoded; }

                public void update(short samples[]) throws IOException {
                    samples_captured += samples.length;

                    byte bs[] = new byte[4096];
                    lame.encodeBufferInterLeaved(samples, samples.length/2, bs);

                    os.write(bs);

                    samples_encoded += samples.length;
                }

                public void finalize() throws IOException {
                    byte bs[] = new byte[4096];
                    lame.flush(bs);
                    lame.close();
                    os.write(bs);
                    os.close();
                }
            };
        } else {
            throw new IllegalArgumentException("unsupported format");
        }
    }
}
