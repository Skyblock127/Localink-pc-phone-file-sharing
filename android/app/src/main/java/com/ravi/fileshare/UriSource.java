package com.ravi.fileshare;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import fileshare.core.Dest;
import fileshare.core.Hexes;
import fileshare.core.Io;
import fileshare.core.Item;
import fileshare.core.Sanitize;

/** Reads files the share sheet or the file picker handed us, as content URIs. */
public final class UriSource implements Io.Source {

    private final Context ctx;

    public UriSource(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public InputStream open(Item it) throws IOException {
        Uri uri = Uri.parse(String.valueOf(it.handle));
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("Android would not open " + it.name
                    + ". Share it again -- the temporary permission may have expired.");
        }
        return new BufferedInputStream(in, Io.BUFFER);
    }

    /**
     * Builds a transfer item from a shared URI.
     *
     * @return null if the URI carries no readable size, which happens with some
     *         streaming providers and means there is nothing sensible to send.
     */
    public static Item describe(Context ctx, Uri uri) {
        String name = null;
        long size = -1;

        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {
            // Fall through to the defaults below.
        } finally {
            if (c != null) c.close();
        }

        if (name == null) {
            String last = uri.getLastPathSegment();
            name = (last == null || last.isEmpty()) ? "shared-file" : last;
        }
        if (size < 0) return null;

        String mime = ctx.getContentResolver().getType(uri);
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";

        Item it = new Item();
        it.name = Sanitize.fileName(name);
        it.size = size;
        it.mime = mime;
        it.handle = uri.toString();
        it.sourceHint = "Phone";
        // The phone always sends into the laptop folder configured there, so the
        // destination code is unused in this direction.
        it.destCode = Dest.DOWNLOADS.code;

        // Stable enough to resume: two different files will not share a URI,
        // a name and a byte count.
        it.id = Hexes.hex(Hexes.sha256(
                (uri.toString() + "|" + it.name + "|" + size).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .substring(0, 32);
        return it;
    }
}
