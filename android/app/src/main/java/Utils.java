package io.rootmos.audiojournal;

import java.math.BigDecimal;
import java.math.RoundingMode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;

public class Utils {
    public static String stringFromInputStream(InputStream is)
            throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        byte[] bs = new byte[1024];
        int l;

        while((l = is.read(bs)) > 0) os.write(bs, 0, l);

        return os.toString("UTF-8");
    }

    public static String formatDuration(float seconds) {
        int s = new BigDecimal(seconds).setScale(0, RoundingMode.HALF_UP)
            .intValue();
        int m = s/60;
        int h = m/60;
        m -= h*60;
        s -= h*3600 + m*60;
        if(h == 0) {
            return String.format("%d:%02d", m, s);
        } else {
            return String.format("%d:%02d:%02d", h, m, s);
        }
    }

    public static String formatDurationLong(float seconds) {
        int ms = new BigDecimal(seconds*1000).setScale(3, RoundingMode.HALF_UP)
            .intValue();
        int s = ms/1000;
        int m = s/60;
        int h = m/60;
        m -= h*60;
        s -= h*3600 + m*60;
        ms -= h*3600000 + m*60000 + s*1000;
        return String.format("%d:%02d:%02d.%03d", h, m, s, ms);
    }
}
