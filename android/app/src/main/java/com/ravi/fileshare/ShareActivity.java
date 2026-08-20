package com.ravi.fileshare;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * The share-sheet entry point.
 *
 * This is the whole reason the app needs no storage permission: Android hands
 * over a content URI with a read grant scoped to exactly the files you picked,
 * rather than the app being given the run of your storage.
 *
 * It does no work itself. It re-packs the URIs into an intent for the main
 * screen and hands the grant along, because a read grant survives only as long
 * as the activity holding it -- and this one closes immediately, whereas the
 * main screen stays open while the transfer runs.
 */
public final class ShareActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        List<Uri> uris = collect(getIntent());
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing shareable in that", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Intent forward = new Intent(this, MainActivity.class);
        forward.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        ClipData clip = ClipData.newUri(getContentResolver(), "files", uris.get(0));
        for (int i = 1; i < uris.size(); i++) {
            clip.addItem(new ClipData.Item(uris.get(i)));
        }
        forward.setClipData(clip);

        startActivity(forward);
        finish();
    }

    private static List<Uri> collect(Intent intent) {
        List<Uri> out = new ArrayList<Uri>();
        if (intent == null) return out;
        String action = intent.getAction();

        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) {
                for (Uri u : many) {
                    if (u != null) out.add(u);
                }
            }
        }

        // Some apps put the payload in ClipData instead of EXTRA_STREAM.
        if (out.isEmpty() && intent.getClipData() != null) {
            ClipData c = intent.getClipData();
            for (int i = 0; i < c.getItemCount(); i++) {
                Uri u = c.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        }
        return out;
    }
}
