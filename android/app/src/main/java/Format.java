package io.rootmos.audiojournal;

import android.os.Parcel;
import android.os.Parcelable;

public enum Format implements Parcelable {
    FLAC(1), MP3(2);

    private int v;

    private Format(int v) {
        this.v = v;
    }

    private static Format valueOf(int v) {
        if(v == FLAC.v) {
            return FLAC;
        } else if(v == MP3.v) {
            return MP3;
        } else {
            throw new RuntimeException("unsupported format: " + v);
        }
    }

    public String getMimeType() {
        if(v == FLAC.v) {
            return "audio/x-flac";
        } else if(v == MP3.v) {
            return "audio/mpeg";
        } else {
            throw new RuntimeException("unsupported format: " + v);
        }
    }

    static public Format guessBasedOnFilename(String f) {
        if(f.toLowerCase().endsWith(".flac")) {
            return Format.FLAC;
        } else if(f.toLowerCase().endsWith(".mp3")) {
            return Format.MP3;
        } else {
            throw new IllegalArgumentException(
                    "unable to guess format of: " + f);
        }
    }

    @Override
    public int describeContents () { return 0; }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(v);
    }

    public static final Parcelable.Creator<Format> CREATOR =
        new Parcelable.Creator<Format>() {
            public Format createFromParcel(Parcel in) {
                return valueOf(in.readInt());
            }

            public Format[] newArray(int size) {
                return new Format[size];
            }
        };
}
