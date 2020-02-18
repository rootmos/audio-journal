package io.rootmos.audiojournal;

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
}
