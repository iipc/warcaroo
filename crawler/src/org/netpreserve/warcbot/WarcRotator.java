package org.netpreserve.warcbot;

import org.netpreserve.jwarc.WarcCompression;
import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.jwarc.Warcinfo;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static java.nio.file.StandardOpenOption.*;

public class WarcRotator implements Closeable {
    private final static DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private final String filenamePrefix;
    private final SecureRandom random = new SecureRandom();
    private final Path directory;
    private WarcWriter warcWriter;
    private String filename;

    public WarcRotator(Path directory, String filenamePrefix) {
        this.directory = directory;
        this.filenamePrefix = filenamePrefix;
    }

    private void open() throws IOException {
        filename = filenamePrefix + "-" + DATE_FORMAT.format(Instant.now()) + "-" + randomId() + ".warc.gz";
        warcWriter = new WarcWriter(FileChannel.open(directory.resolve(filename),
                WRITE, CREATE, TRUNCATE_EXISTING), WarcCompression.GZIP);
        Warcinfo warcinfo = new Warcinfo.Builder()
                .filename(filename)
                .fields(Map.of("software", List.of("warcbot"),
                        "format", List.of("WARC File Format 1.0"),
                        "conformsTo", List.of("https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.0/")))
                .build();
        warcWriter.write(warcinfo);
    }

    public WarcWriter get() throws IOException {
        if (warcWriter == null) {
            open();
        }
        return warcWriter;
    }

    private String randomId() {
        String alphabet = "ABCDFGHJKLMNPQRSTVWXYZabcdfghjklmnpqrstvwxyz0123456789";
        var sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        if (warcWriter != null) {
            warcWriter.close();
            warcWriter = null;
        }
    }

    public String filename() {
        return filename;
    }
}
