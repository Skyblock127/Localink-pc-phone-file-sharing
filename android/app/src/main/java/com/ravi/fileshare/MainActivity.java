package com.ravi.fileshare;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

import fileshare.core.Certs;
import fileshare.core.Hexes;
import fileshare.core.Item;

public final class MainActivity extends AppCompatActivity
        implements LinkService.Watcher, OfferGate.Watcher {

    private static final int AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    /** Hotspot and tether state change with no callback to subscribe to. */
    private static final long REFRESH_MS = 2000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /**
     * Kept as entries rather than one blob, so a long press can copy the whole
     * entry even when a long file name has wrapped onto several drawn lines.
     */
    private final List<String> logEntries = new ArrayList<String>();
    private final List<int[]> logRanges = new ArrayList<int[]>();

    private Vault vault;
    private MediaSink partials;

    private View root, header, insetBox, linkScrim;
    private TextView appName, status, statusDot, logView, pairCode, pairCountdown,
            offerTitle, laptopStatus, laptopFingerprint, progressName, progressDetail;
    private MaterialButton connectButton, pairPromptButton, linkButton, clearButton,
            pairButton, unpairButton, acceptSelected, rejectAll,
            pauseButton, resumeButton, cancelButton, copyAllButton;
    private MaterialCardView pairCard, offerCard, progressCard;
    private LinearLayout offerList, activityPanel;
    private View activityHandle;
    private ScrollView scroll, activityScroll;
    private ProgressBar progressBar;

    private final List<CheckBox> offerChecks = new ArrayList<CheckBox>();
    private OfferGate.Pending shownOffer;
    private Runnable countdownTick;
    private ConnectivityManager.NetworkCallback netCallback;

    private String liveId, liveName;
    private boolean livePaused;
    private long hideProgressAt;
    private float lastLogTouchY;

    private int drawerMin;

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        vault = Vault.get(this);
        partials = new MediaSink(this);
        bindViews();
        applyInsets();
        wireActions();
        setUpDrawer();

        askNotificationPermission();
        setEnabled(false);
        unlock();
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        insetBox = findViewById(R.id.insetBox);
        header = findViewById(R.id.header);
        appName = findViewById(R.id.appName);
        status = findViewById(R.id.status);
        statusDot = findViewById(R.id.statusDot);
        scroll = findViewById(R.id.scroll);

        connectButton = findViewById(R.id.connectButton);
        pairPromptButton = findViewById(R.id.pairPromptButton);
        linkButton = findViewById(R.id.linkButton);
        clearButton = findViewById(R.id.clearButton);

        linkScrim = findViewById(R.id.linkScrim);
        laptopStatus = findViewById(R.id.laptopStatus);
        laptopFingerprint = findViewById(R.id.laptopFingerprint);
        pairCard = findViewById(R.id.pairCard);
        pairCode = findViewById(R.id.pairCode);
        pairCountdown = findViewById(R.id.pairCountdown);
        pairButton = findViewById(R.id.pairButton);
        unpairButton = findViewById(R.id.unpairButton);

        progressCard = findViewById(R.id.progressCard);
        progressName = findViewById(R.id.progressName);
        progressDetail = findViewById(R.id.progressDetail);
        progressBar = findViewById(R.id.progressBar);
        pauseButton = findViewById(R.id.pauseButton);
        resumeButton = findViewById(R.id.resumeButton);
        cancelButton = findViewById(R.id.cancelButton);

        offerCard = findViewById(R.id.offerCard);
        offerTitle = findViewById(R.id.offerTitle);
        offerList = findViewById(R.id.offerList);
        acceptSelected = findViewById(R.id.acceptSelected);
        rejectAll = findViewById(R.id.rejectAll);

        activityPanel = findViewById(R.id.activityPanel);
        activityHandle = findViewById(R.id.activityHandle);
        activityScroll = findViewById(R.id.activityScroll);
        logView = findViewById(R.id.log);
        copyAllButton = findViewById(R.id.copyAllButton);
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, new androidx.core.view.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                Insets bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                insetBox.setPadding(bars.left, bars.top, bars.right, 0);
                activityPanel.setPadding(bars.left, 0, bars.right, bars.bottom);
                drawerMin = dp(116) + bars.bottom;
                scroll.setPadding(0, 0, 0, drawerMin + dp(8));
                if (activityPanel.getLayoutParams().height < drawerMin) setDrawerHeight(drawerMin);
                return WindowInsetsCompat.CONSUMED;
            }
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void wireActions() {
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleConnect(); }
        });
        pairPromptButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLinkPanel(); startPairing(); }
        });
        linkButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLinkPanel(); }
        });
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClearPartials(); }
        });
        linkScrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeLinkPanel(); }
        });
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startPairing(); }
        });
        unpairButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmUnpair(); }
        });
        acceptSelected.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { answerOffer(true); }
        });
        rejectAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { answerOffer(false); }
        });

        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LinkService svc = LinkService.get();
                if (svc != null) svc.pauseCurrent();
            }
        });
        resumeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LinkService svc = LinkService.get();
                if (svc != null) svc.resume(liveId);
            }
        });
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmCancel(); }
        });

        copyAllButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copy(allLog(), "All activity copied");
            }
        });

        // Long-press copies only the line under your finger.
        logView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                lastLogTouchY = e.getY();
                return false;
            }
        });
        logView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Layout l = logView.getLayout();
                if (l == null) return false;
                int line = l.getLineForVertical((int) lastLogTouchY);
                int at = l.getLineStart(line);

                // Map the drawn line back to the entry that contains it, so a
                // wrapped file name copies as one piece.
                for (int i = 0; i < logRanges.size(); i++) {
                    int[] r = logRanges.get(i);
                    if (at >= r[0] && at < r[1]) {
                        copy(logEntries.get(i), "Entry copied");
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private void copy(String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || text == null || text.isEmpty()) return;
        cm.setPrimaryClip(ClipData.newPlainText("Localink", text));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Activity drawer
    // ------------------------------------------------------------------

    private void setUpDrawer() {
        activityHandle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawY;
            private int startHeight;
            private boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = e.getRawY();
                        startHeight = activityPanel.getHeight();
                        dragged = false;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        int delta = (int) (downRawY - e.getRawY());
                        if (Math.abs(delta) > dp(4)) dragged = true;
                        setDrawerHeight(startHeight + delta);
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragged) {
                            setDrawerHeight(activityPanel.getHeight() > drawerMin + dp(12)
                                    ? drawerMin : drawerMaxHeight());
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private int drawerMaxHeight() {
        return Math.max(drawerMin, root.getHeight() - header.getBottom() - dp(6));
    }

    /**
     * Resizing keeps the log where it was.
     *
     * A ScrollView clamps its scroll position when it grows or shrinks, so the
     * offset is captured and restored around the layout pass; without that,
     * dragging the drawer throws you back to the top of the log.
     */
    private void setDrawerHeight(int h) {
        int clamped = Math.max(drawerMin, Math.min(drawerMaxHeight(), h));
        ViewGroup.LayoutParams lp = activityPanel.getLayoutParams();
        if (lp.height == clamped) return;

        final int keep = activityScroll.getScrollY();
        lp.height = clamped;
        activityPanel.setLayoutParams(lp);
        activityScroll.post(new Runnable() {
            @Override public void run() { activityScroll.scrollTo(0, keep); }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        absorbSharedFiles(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinkService.setWatcher(this);
        OfferGate.setWatcher(this);
        registerNetworkWatch();
        ui.removeCallbacks(refreshTick);
        ui.post(refreshTick);
        absorbSharedFiles(getIntent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        LinkService.setWatcher(null);
        OfferGate.setWatcher(null);
        unregisterNetworkWatch();
        ui.removeCallbacks(refreshTick);
    }

    @Override
    public void onBackPressed() {
        if (linkScrim.getVisibility() == View.VISIBLE) {
            closeLinkPanel();
            return;
        }
        if (activityPanel.getHeight() > drawerMin + dp(12)) {
            setDrawerHeight(drawerMin);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private void registerNetworkWatch() {
        if (netCallback != null) return;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(@NonNull Network n) { postRefresh(); }
                @Override public void onLost(@NonNull Network n) { postRefresh(); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().build(), netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
    }

    private void unregisterNetworkWatch() {
        if (netCallback == null) return;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) { }
        netCallback = null;
    }

    private void postRefresh() {
        ui.post(new Runnable() {
            @Override public void run() { refresh(); }
        });
    }

    private void refresh() {
        boolean paired = vault.isPaired();
        boolean unlocked = vault.isUnlocked();
        boolean on = LinkService.isRunning();

        pairPromptButton.setVisibility(paired ? View.GONE : View.VISIBLE);
        pairPromptButton.setEnabled(unlocked);
        connectButton.setVisibility(paired ? View.VISIBLE : View.GONE);
        connectButton.setEnabled(unlocked);
        connectButton.setText(on ? "Disconnect" : "Connect");
        linkButton.setVisibility(unlocked ? View.VISIBLE : View.GONE);

        // Counted from storage, so it does not depend on the link being up.
        int unfinished = partials.partialCount();
        boolean busy = liveId != null && !livePaused;
        clearButton.setVisibility(unfinished > 0 ? View.VISIBLE : View.GONE);
        clearButton.setEnabled(!busy);
        clearButton.setAlpha(busy ? 0.4f : 1f);

        laptopStatus.setText(paired ? vault.laptopName() : "No laptop paired");
        laptopFingerprint.setText(paired && vault.laptopFingerprint() != null
                ? Certs.shortFingerprint(vault.laptopFingerprint()) : "");
        unpairButton.setVisibility(paired ? View.VISIBLE : View.GONE);
        pairButton.setText(pairCard.getVisibility() == View.VISIBLE
                ? "Generate new code"
                : (paired ? "Pair a different laptop" : "Pair a laptop"));

        if (hideProgressAt > 0 && System.currentTimeMillis() > hideProgressAt) {
            progressCard.setVisibility(View.GONE);
            hideProgressAt = 0;
            liveId = null;
        }
    }

    private void setStatus(String text, int dotColor) {
        status.setText(text);
        statusDot.setTextColor(dotColor);
    }

    private static final int DOT_OFF = Color.parseColor("#9E9E9E");
    private static final int DOT_READY = Color.parseColor("#E08600");
    private static final int DOT_LIVE = Color.parseColor("#2E9E5B");
    private static final int DOT_BAD = Color.parseColor("#D93A34");

    // ------------------------------------------------------------------
    // Unlock
    // ------------------------------------------------------------------

    private void unlock() {
        if (vault.isUnlocked()) {
            onUnlocked();
            return;
        }

        if (!vault.deviceSecure()) {
            try {
                vault.unlock();
                onUnlocked();
            } catch (Exception e) {
                setStatus("Could not start: " + e.getMessage(), DOT_BAD);
            }
            return;
        }

        BiometricManager bm = BiometricManager.from(this);
        if (bm.canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            setStatus("Set a screen lock, then reopen", DOT_BAD);
            return;
        }

        new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                        try {
                            vault.unlock();
                            onUnlocked();
                        } catch (Exception e) {
                            if (Vault.isKeyInvalidated(e)) {
                                vault.wipeIdentity();
                                setStatus("Keys were reset. Pair again.", DOT_BAD);
                                setEnabled(true);
                            } else {
                                setStatus("Unlock failed: " + e.getMessage(), DOT_BAD);
                            }
                            refresh();
                        }
                    }

                    @Override
                    public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                        setStatus("Locked", DOT_OFF);
                    }
                }).authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Localink")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build());
    }

    private void onUnlocked() {
        setEnabled(true);
        setStatus(vault.isPaired() ? "Not connected" : "Not paired", DOT_OFF);
        refresh();
        absorbSharedFiles(getIntent());
    }

    private void setEnabled(boolean on) {
        connectButton.setEnabled(on);
        pairPromptButton.setEnabled(on);
        linkButton.setEnabled(on);
    }

    // ------------------------------------------------------------------
    // Connecting
    // ------------------------------------------------------------------

    private void toggleConnect() {
        if (LinkService.isRunning()) {
            startService(new Intent(this, LinkService.class).setAction(LinkService.ACTION_STOP));
            setStatus("Not connected", DOT_OFF);
        } else {
            if (!vault.isUnlocked()) { unlock(); return; }
            startLink();
            setStatus("Waiting for laptop", DOT_READY);
        }
        ui.postDelayed(new Runnable() {
            @Override public void run() { refresh(); }
        }, 500);
    }

    private void startLink() {
        try {
            ContextCompat.startForegroundService(this,
                    new Intent(this, LinkService.class).setAction(LinkService.ACTION_START));
        } catch (Exception e) {
            append("Could not start: " + e.getMessage());
            setStatus("Could not start", DOT_BAD);
        }
    }

    // ------------------------------------------------------------------
    // Link panel
    // ------------------------------------------------------------------

    private void openLinkPanel() {
        refresh();
        linkScrim.setVisibility(View.VISIBLE);
    }

    private void closeLinkPanel() {
        linkScrim.setVisibility(View.GONE);
        pairCard.setVisibility(View.GONE);
        if (countdownTick != null) ui.removeCallbacks(countdownTick);
        LinkService svc = LinkService.get();
        if (svc != null) svc.cancelPairing();
        refresh();
    }

    private void startPairing() {
        if (!vault.isUnlocked()) { unlock(); return; }
        if (!LinkService.isRunning()) startLink();

        ui.postDelayed(new Runnable() {
            @Override public void run() {
                LinkService svc = LinkService.get();
                if (svc == null) {
                    Toast.makeText(MainActivity.this, "Link did not start", Toast.LENGTH_LONG).show();
                    return;
                }
                pairCode.setText(svc.startPairing());
                pairCard.setVisibility(View.VISIBLE);
                startCountdown(svc.pairingExpiresAt());
                refresh();
            }
        }, 700);
    }

    private void startCountdown(final long expiresAt) {
        if (countdownTick != null) ui.removeCallbacks(countdownTick);
        countdownTick = new Runnable() {
            @Override public void run() {
                long left = expiresAt - System.currentTimeMillis();
                if (left <= 0) {
                    pairCard.setVisibility(View.GONE);
                    refresh();
                    return;
                }
                pairCountdown.setText(left / 1000 + "s");
                ui.postDelayed(this, 1000);
            }
        };
        ui.post(countdownTick);
    }

    private void confirmUnpair() {
        new AlertDialog.Builder(this)
                .setTitle("Forget " + vault.laptopName() + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        LinkService svc = LinkService.get();
                        if (svc != null) svc.unpairAndNotify();
                        else vault.forgetLaptop();
                        closeLinkPanel();
                    }
                })
                .show();
    }

    private void confirmClearPartials() {
        if (liveId != null && !livePaused) return;   // pause or cancel it first
        final int n = partials.partialCount();
        if (n == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Clear " + n + " unfinished transfer" + (n == 1 ? "?" : "s?"))
                .setMessage("Everything received so far for them is deleted and cannot be resumed.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        LinkService svc = LinkService.get();
                        if (svc != null) svc.clearAllPartials();
                        else partials.clearAllPartials();
                        progressCard.setVisibility(View.GONE);
                        liveId = null;
                        refresh();
                    }
                })
                .show();
    }

    private void confirmCancel() {
        final String id = liveId;
        if (id == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Cancel transfer?")
                .setMessage(liveName + "\n\nWhat has arrived is deleted.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Cancel transfer", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        LinkService svc = LinkService.get();
                        if (svc != null) svc.cancelItem(id);
                        progressCard.setVisibility(View.GONE);
                        liveId = null;
                        refresh();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------
    // Incoming offers
    // ------------------------------------------------------------------

    @Override
    public void onOffer(final OfferGate.Pending p) {
        ui.post(new Runnable() {
            @Override public void run() { showOffer(p); }
        });
    }

    @Override
    public void onOfferCleared() {
        ui.post(new Runnable() {
            @Override public void run() {
                offerCard.setVisibility(View.GONE);
                shownOffer = null;
            }
        });
    }

    private void showOffer(OfferGate.Pending p) {
        shownOffer = p;
        offerChecks.clear();
        offerList.removeAllViews();

        long total = 0;
        LayoutInflater inf = LayoutInflater.from(this);
        for (Item it : p.items) {
            View row = inf.inflate(R.layout.item_offer, offerList, false);
            CheckBox pick = row.findViewById(R.id.pick);
            TextView name = row.findViewById(R.id.name);
            TextView detail = row.findViewById(R.id.detail);

            name.setText(it.name);
            StringBuilder d = new StringBuilder();
            d.append(Hexes.humanBytes(it.size)).append("  →  ").append(it.dest().label);
            if (it.have > 0) d.append("   resuming ").append(Hexes.humanBytes(it.have));
            detail.setText(d.toString());

            offerChecks.add(pick);
            offerList.addView(row);
            total += it.size - it.have;
        }

        offerTitle.setText(p.items.size() + (p.items.size() == 1 ? " file · " : " files · ")
                + Hexes.humanBytes(total));
        offerCard.setVisibility(View.VISIBLE);
    }

    private void answerOffer(boolean accept) {
        OfferGate.Pending p = shownOffer;
        if (p == null) return;
        boolean[] decision = new boolean[p.items.size()];
        for (int i = 0; i < decision.length && i < offerChecks.size(); i++) {
            decision[i] = accept && offerChecks.get(i).isChecked();
        }
        OfferGate.resolve(p, decision);
        offerCard.setVisibility(View.GONE);
        shownOffer = null;
    }

    // ------------------------------------------------------------------
    // Shared-in files
    // ------------------------------------------------------------------

    private void absorbSharedFiles(Intent intent) {
        if (intent == null) return;
        ClipData clip = intent.getClipData();
        if (clip == null || clip.getItemCount() == 0) return;
        intent.setClipData(null);

        List<Item> items = new ArrayList<Item>();
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri == null) continue;
            Item it = UriSource.describe(this, uri);
            if (it != null) items.add(it);
        }
        if (items.isEmpty()) return;

        LinkService.Outbox.add(items);
        long bytes = 0;
        for (Item it : items) bytes += it.size;
        append("Queued " + items.size() + " file(s), " + Hexes.humanBytes(bytes));

        if (!LinkService.isRunning() && vault.isUnlocked() && vault.isPaired()) {
            startLink();
            setStatus("Waiting for laptop", DOT_READY);
        }
        refresh();
    }

    // ------------------------------------------------------------------
    // Service callbacks
    // ------------------------------------------------------------------

    @Override
    public void onState(final String s, final boolean listening, final boolean connected) {
        ui.post(new Runnable() {
            @Override public void run() {
                setStatus(s, connected ? DOT_LIVE : (listening ? DOT_READY : DOT_OFF));
                refresh();
            }
        });
    }

    @Override
    public void onLog(final String line) {
        ui.post(new Runnable() {
            @Override public void run() { append(line); }
        });
    }

    @Override
    public void onProgress(final String id, final String name, final long done, final long total,
                           final long bytesPerSec, final boolean sending) {
        ui.post(new Runnable() {
            @Override public void run() {
                liveId = id;
                liveName = name;
                livePaused = false;
                hideProgressAt = 0;

                progressCard.setVisibility(View.VISIBLE);
                progressName.setText((sending ? "↑  " : "↓  ") + name);
                progressBar.setProgress(total <= 0 ? 0 : (int) (done * 1000L / total));

                String pct = total <= 0 ? "" : String.format(java.util.Locale.US, "%.1f%%",
                        done * 100.0 / total);
                String eta = Hexes.eta(total - done, bytesPerSec);
                progressDetail.setText(pct + "   " + Hexes.humanBytes(done) + " / "
                        + Hexes.humanBytes(total)
                        + (bytesPerSec > 0 ? "   " + Hexes.humanRate(bytesPerSec) : "")
                        + (eta.isEmpty() ? "" : "   " + eta));

                pauseButton.setVisibility(done >= total ? View.GONE : View.VISIBLE);
                resumeButton.setVisibility(View.GONE);
                cancelButton.setVisibility(done >= total ? View.GONE : View.VISIBLE);
                if (done >= total) hideProgressAt = System.currentTimeMillis() + 3000;
            }
        });
    }

    @Override
    public void onPaused(final LinkService.PausedView p) {
        ui.post(new Runnable() {
            @Override public void run() {
                liveId = p.id;
                liveName = p.name;
                livePaused = true;
                hideProgressAt = 0;

                progressCard.setVisibility(View.VISIBLE);
                progressName.setText((p.sending ? "↑  " : "↓  ") + p.name);
                progressBar.setProgress(p.total <= 0 ? 0 : (int) (p.done * 1000L / p.total));
                progressDetail.setText("Paused   " + Hexes.humanBytes(p.done) + " / "
                        + Hexes.humanBytes(p.total));

                pauseButton.setVisibility(View.GONE);
                resumeButton.setVisibility(View.VISIBLE);
                cancelButton.setVisibility(View.VISIBLE);
                refresh();
            }
        });
    }

    @Override
    public void onFinished(final String id) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (id == null || id.equals(liveId)) {
                    progressCard.setVisibility(View.GONE);
                    liveId = null;
                    livePaused = false;
                }
                refresh();
            }
        });
    }

    @Override
    public void onUnpairedByPeer() {
        ui.post(new Runnable() {
            @Override public void run() {
                append("The laptop removed this pairing.");
                refresh();
            }
        });
    }

    /** Only follows the tail when you were already at the bottom. */
    private void append(String line) {
        logEntries.add(line);
        while (logEntries.size() > 300) logEntries.remove(0);

        boolean atBottom = !activityScroll.canScrollVertically(1);

        // A hanging indent, so the bullet sits outside the text block and
        // wrapped lines line up under the first character rather than the dot.
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        logRanges.clear();
        int indent = dp(14);
        for (int i = 0; i < logEntries.size(); i++) {
            if (i > 0) sb.append('\n');
            int start = sb.length();
            sb.append("\u2022  ").append(logEntries.get(i));
            sb.setSpan(new android.text.style.LeadingMarginSpan.Standard(0, indent),
                    start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            logRanges.add(new int[]{start, sb.length()});
        }
        logView.setText(sb);

        if (atBottom) {
            activityScroll.post(new Runnable() {
                @Override public void run() { activityScroll.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    private String allLog() {
        StringBuilder sb = new StringBuilder();
        for (String e : logEntries) sb.append(e).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
    }
}
