package com.pivotry.blockauto.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Last failed observation only. Encoding/disk I/O happen on explicit export. */
public final class FailureTrace {
    public interface ImageEncoder { void write(Game.Image image,OutputStream output) throws IOException; }
    private final Game.Image before,held,probe;
    private final String metadata;
    public FailureTrace(Game.Image before,Game.Image held,String metadata) {
        this(before,held,metadata,null);
    }
    public FailureTrace(Game.Image before,Game.Image held,String metadata,Game.Image probe) {
        this.before=before;this.held=held;this.metadata=metadata;this.probe=probe;
    }
    public void writeZip(OutputStream output,ImageEncoder encoder) throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("metadata.json"));zip.write(metadata.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("before.png"));encoder.write(before,zip);zip.closeEntry();
            zip.putNextEntry(new ZipEntry("held.png"));encoder.write(held,zip);zip.closeEntry();
            if(probe!=null){zip.putNextEntry(new ZipEntry("probe.png"));encoder.write(probe,zip);zip.closeEntry();}
        }
    }
}
