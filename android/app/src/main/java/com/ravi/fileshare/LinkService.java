package com.ravi.fileshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import fileshare.core.Hexes;
import fileshare.core.Io;
import fileshare.core.Item;
import fileshare.core.Pairing;
import fileshare.core.Session;
import fileshare.core.Tls;
import fileshare.core.Transfer;
import fileshare.core.TransferState;
import fileshare.core.Wire;

/**
 * Owns the listening socket and the transfer loop.
 *
 * The only thing that runs in the background, it only runs while armed, and it
 * stops itself after a spell of inactivity. When it is off there is no socket,
 * no wake lock and no timer.
 */
public final class LinkService extends Service {

    public static final String ACTION_START  = "com.ravi.fileshare.START";
    public static final String ACTION_STOP   = "com.ravi.fileshare.STOP";
    public static final String ACTION_PAUSE  = "com.ravi.fileshare.PAUSE";
    public static final String ACTION_RESUME = "com.ravi.fileshare.RESUME";
    public static final String ACTION_CANCEL = "com.ravi.fileshare.CANCEL";

    private static final String CHANNEL = "localink-link";
    private static final int NOTE_ID = 1;

    /** Disconnects after a minute of nothing, so an idle link costs no battery. */
    private static final long IDLE_TIMEOUT_MS = 60 * 1000;

    public interface Watcher {
        void onState(String status, boolean listening, boolean connected);

        void onLog(String line);

        void onProgress(String id, String name, long done, long total,
                        long bytesPerSec, boolean sending);

        void onPaused(PausedView p);

        void onFinished(String id);

        void onUnpairedByPeer();
    }

    private static volatile Watcher watcher;
    private static volatile boolean running;
    private static volatile LinkService instance;

    public static void setWatcher(Watcher w) {
        watcher = w;
        LinkService svc = instance;
        if (w != null && svc != null) svc.replayState();
    }

    public static boolean isRunning() { return running; }

    public static LinkService get() { return instance; }

    private Vault vault;
    private MediaSink sink;
    private volatile SSLServerSocket server;
    private volatile Thread loopThread;
    private volatile boolean stopping;
    private volatile long lastActivity;

    private volatile String pairCode;
    private volatile long pairExpiresAt;

    private volatile Session live;

    /** Outgoing files that are paused: out of the Outbox until resumed. */
    private final Map<String, Item> pausedOut =
            Collections.synchronizedMap(new LinkedHashMap<String, Item>());

    /**
     * Incoming files that are paused. Display only -- the laptop's queue decides
     * what is offered, so this never affects an accept decision.
     */
    private final Map<String, PausedView> pausedIn =
            Collections.synchronizedMap(new LinkedHashMap<String, PausedView>());

    /** What the progress card shows for a paused transfer. */
    public static final class PausedView {
        public final String id, name;
        public final long done, total;
        public final boolean sending;

        PausedView(String id, String name, long done, long total, boolean sending) {
            this.id = id; this.name = name; this.done = done;
            this.total = total; this.sending = sending;
        }
    }
    private volatile String currentItemId;
    private volatile String currentItemName;
    private volatile boolean currentSending;
    private volatile long currentDone, currentTotal, currentRate;

    private final TransferState state = new TransferState();

    // ------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : String.valueOf(intent.getAction());

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pauseCurrent();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            String id = intent == null ? null : intent.getStringExtra("id");
            resume(id != null ? id : firstPausedId());
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            String id = intent == null ? null : intent.getStringExtra("id");
            cancelItem(id != null ? id : firstPausedId());
            return START_NOT_STICKY;
        }

        if (running) return START_NOT_STICKY;

        vault = Vault.get(this);
        sink = new MediaSink(this);

        if (!vault.isUnlocked()) {
            log("Cannot start: app is locked.");
            stopSelf();
            return START_NOT_STICKY;
        }

        createChannel();
        try {
            startForeground(NOTE_ID, idleNotification());
        } catch (Exception e) {
            log("Android refused to start the link: " + e.getMessage());
            state("Could not start: " + e.getMessage(), false, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        instance = this;
        running = true;
        stopping = false;
        lastActivity = System.currentTimeMillis();

        sink.sweepStale(MediaSink.PARTIAL_LIFETIME_MS);

        loopThread = new Thread(new Runnable() {
            @Override public void run() { serveLoop(); }
        }, "localink-link");
        loopThread.setDaemon(true);
        loopThread.start();

        state("Waiting for laptop", true, false);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        stopping = true;
        running = false;
        instance = null;
        OfferGate.cancelAll();
        SSLServerSocket s = server;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
        Thread t = loopThread;
        if (t != null) t.interrupt();
        state("Not connected", false, false);
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------
    // Listening
    // ------------------------------------------------------------------

    private void serveLoop() {
        try {
            SSLContext ctx = Tls.context(vault.key(), vault.cert());
            SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory()
                    .createServerSocket(Session.PORT);
            ss.setNeedClientAuth(true);
            ss.setSoTimeout(5000);
            server = ss;
            log("Listening on " + Session.PORT + " (" + myAddresses() + ")");
        } catch (Exception e) {
            log("Could not listen: " + e.getMessage());
            state("Could not start", false, false);
            stopSelf();
            return;
        }

        while (!stopping) {
            SSLSocket sock;
            try {
                sock = (SSLSocket) server.accept();
            } catch (java.net.SocketTimeoutException te) {
                if (System.currentTimeMillis() - lastActivity > IDLE_TIMEOUT_MS) {
                    log("Idle for a minute, disconnecting.");
                    shutdown();
                    return;
                }
                expirePairCodeIfNeeded();
                continue;
            } catch (IOException e) {
                if (!stopping) log("Accept failed: " + e.getMessage());
                break;
            }

            try {
                handle(sock);
            } catch (Exception e) {
                log("Disconnected: " + shortMessage(e));
            } finally {
                try { sock.close(); } catch (IOException ignored) { }
                clearLive();
                state(running ? "Waiting for laptop" : "Not connected", running, false);
            }
        }
    }

    private void handle(SSLSocket sock) throws Exception {
        InetAddress remote = sock.getInetAddress();
        InetAddress local = sock.getLocalAddress();
        String refusal = refuseReason(remote, local);
        if (refusal != null) {
            log("Refused " + remote.getHostAddress() + ": " + refusal);
            return;
        }

        lastActivity = System.currentTimeMillis();
        String code = activePairCode();

        Session sess;
        try {
            sess = Session.serve(sock, vault.deviceName(), vault, code);
        } catch (Session.BadPairingCode e) {
            pairCode = null;
            pairExpiresAt = 0;
            log("Wrong pairing code.");
            state("Pairing failed", running, false);
            return;
        }

        if (sess == null) {
            log("Ignored unknown device " + remote.getHostAddress());
            return;
        }

        if (code != null) {
            pairCode = null;
            pairExpiresAt = 0;
            log("Paired with " + sess.peerName);
        }

        state("Connected to " + sess.peerName, true, true);
        log("Connected to " + sess.peerName);

        live = sess;
        try {
            runRounds(sess);
        } catch (Transfer.Unpaired e) {
            vault.forgetLaptop();
            state.clear();
            log("The laptop removed this pairing.");
            Watcher w = watcher;
            if (w != null) w.onUnpairedByPeer();
            state("Not paired", running, false);
        } finally {
            live = null;
            sess.closeQuietly();
        }
    }

    private void runRounds(Session sess) throws IOException {
        Io.Source source = new UriSource(this);
        final boolean[] sending = {false};

        // Files that reached a conclusion this round. Only what is left over goes
        // back on the queue when a connection drops, so nothing is sent twice.
        final java.util.Set<String> settled =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

        Io.Events events = new Io.Events() {
            @Override public void onStart(Item it, long from, boolean snd) {
                lastActivity = System.currentTimeMillis();
                currentItemId = it.id;
                currentItemName = it.name;
                currentSending = snd;
                currentTotal = it.size;
                currentDone = from;
                log((snd ? "Sending " : "Receiving ") + it.name
                        + (from > 0 ? ", resuming from " + Hexes.humanBytes(from) : ""));
            }

            @Override public void onBytes(Item it, long done, long total, long bps) {
                lastActivity = System.currentTimeMillis();
                currentDone = done;
                currentTotal = total;
                currentRate = bps;
                Watcher w = watcher;
                if (w != null) w.onProgress(it.id, it.name, done, total, bps, sending[0]);
                pushProgressNotification(it.name, done, total, bps, sending[0]);
            }

            @Override public void onDone(Item it, boolean ok, String msg) {
                lastActivity = System.currentTimeMillis();
                settled.add(it.id);
                state.forget(it.id);
                pausedIn.remove(it.id);
                clearLive();
                log((ok ? (sending[0] ? "Sent " : "Saved ") : "Failed ") + it.name
                        + (ok && !sending[0] ? " to " + it.dest().label : (ok ? "" : " - " + msg)));
                Watcher w = watcher;
                if (w != null) w.onFinished(it.id);
                showIdleNotification();
            }

            @Override public void onPaused(Item it, long done, long total, boolean snd) {
                settled.add(it.id);
                if (snd) pausedOut.put(it.id, it);
                PausedView p = new PausedView(it.id, it.name, done, total, snd);
                pausedIn.put(it.id, p);
                clearLive();
                log("Paused " + it.name + " at " + Hexes.humanBytes(done));
                Watcher w = watcher;
                if (w != null) w.onPaused(p);
                showPausedNotification(p);
            }

            @Override public void onCancelled(Item it) {
                settled.add(it.id);
                state.forget(it.id);
                pausedIn.remove(it.id);
                pausedOut.remove(it.id);
                if (sink != null) sink.discardPartial(it.id);
                clearLive();
                log("Cancelled " + it.name);
                Watcher w = watcher;
                if (w != null) w.onFinished(it.id);
                showIdleNotification();
            }

            @Override public void log(String m) { LinkService.this.log(m); }
        };

        Io.Approver approver = new Io.Approver() {
            @Override public boolean[] decide(List<Item> offered) {
                List<Item> ask = new ArrayList<Item>();
                List<Integer> at = new ArrayList<Integer>();
                boolean[] answer = state.preDecide(offered, ask, at);

                if (ask.isEmpty()) return answer;

                showNotification("Files waiting", ask.size() + " from your laptop");
                boolean[] chosen = OfferGate.ask(ask);
                for (int i = 0; i < chosen.length && i < at.size(); i++) {
                    answer[at.get(i).intValue()] = chosen[i];
                    if (chosen[i]) state.noteAccepted(ask.get(i).id);
                    else state.noteDeclined(ask.get(i).id);
                }
                return answer;
            }
        };

        while (!stopping && running) {
            for (String id : Transfer.takeResumeRequests(sess)) {
                state.noteAccepted(id);
                Item back = pausedOut.remove(id);
                if (back != null) Outbox.add(java.util.Collections.singletonList(back));
                pausedIn.remove(id);
                Watcher w = watcher;
                if (w != null) w.onFinished(id);
            }

            sending[0] = false;
            Transfer.receiveRound(sess, sink, approver, events, state.control);

            sending[0] = true;
            List<Item> out = Outbox.drain();
            settled.clear();
            try {
                Map<String, Io.Outcome> res =
                        Transfer.sendRound(sess, out, source, events, state.control);
                for (Item it : out) {
                    Io.Outcome o = res.get(it.id);
                    if (o == Io.Outcome.PAUSED) pausedOut.put(it.id, it);
                }
            } catch (IOException e) {
                // Put back only what did not finish. Returning the whole batch
                // would re-send files that already arrived; returning nothing
                // would silently drop the rest of the queue.
                List<Item> back = new ArrayList<Item>();
                for (Item it : out) {
                    if (settled.contains(it.id)) continue;
                    if (pausedOut.containsKey(it.id)) continue;
                    back.add(it);
                }
                Outbox.putBack(back);
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------
    // Pause, resume, cancel
    // ------------------------------------------------------------------

    /** Pauses whatever is moving. The partial and its offset survive. */
    public void pauseCurrent() {
        String id = currentItemId;
        if (id != null) state.requestPause(id);
    }

    /** Restarts a paused transfer, on this device and on the laptop. */
    public void resume(String id) {
        if (id == null) return;
        state.noteAccepted(id);
        pausedIn.remove(id);
        Session s = live;
        if (s != null) s.requestResume(id);
        Item back = pausedOut.remove(id);
        if (back != null) Outbox.add(java.util.Collections.singletonList(back));
        log("Resuming.");
        showIdleNotification();
        Watcher w = watcher;
        if (w != null) w.onFinished(id);
    }

    /** Drops a transfer and deletes what had arrived. Works live or paused. */
    public void cancelItem(String id) {
        if (id == null) return;
        if (id.equals(currentItemId)) {
            state.requestCancel(id);   // in flight: the engine tidies up
            return;
        }
        PausedView p = pausedIn.remove(id);
        state.noteDeclined(id);
        pausedOut.remove(id);
        if ((p == null || !p.sending) && sink != null) sink.discardPartial(id);
        log("Cancelled " + (p == null ? "transfer" : p.name));
        showIdleNotification();
        Watcher w = watcher;
        if (w != null) w.onFinished(id);
    }

    public String firstPausedId() {
        PausedView p = firstPaused();
        return p == null ? null : p.id;
    }

    public PausedView firstPaused() {
        synchronized (pausedIn) {
            for (PausedView p : pausedIn.values()) return p;
        }
        return null;
    }

    /** Deletes every half-finished incoming file. */
    public int clearAllPartials() {
        state.clear();
        pausedOut.clear();
        pausedIn.clear();
        int n = sink == null ? 0 : sink.clearAllPartials();
        log("Cleared " + n + " unfinished transfer" + (n == 1 ? "" : "s"));
        showIdleNotification();
        Watcher w = watcher;
        if (w != null) w.onFinished(null);
        return n;
    }


    public int partialCount() {
        return sink == null ? 0 : sink.partialCount();
    }

    private void clearLive() {
        currentItemId = null;
        currentItemName = null;
        currentDone = 0;
        currentTotal = 0;
        currentRate = 0;
    }

    /** Re-sends whatever the screen needs after it returns to the foreground. */
    private void replayState() {
        Watcher w = watcher;
        if (w == null) return;
        if (currentItemId != null) {
            w.onProgress(currentItemId, currentItemName, currentDone, currentTotal,
                    currentRate, currentSending);
        } else {
            PausedView p = firstPaused();
            if (p != null) w.onPaused(p);
        }
    }

    // ------------------------------------------------------------------
    // Source filtering
    // ------------------------------------------------------------------

    private String refuseReason(InetAddress remote, InetAddress local) {
        if (!(remote instanceof Inet4Address)) return "not IPv4";
        if (!isPrivateV4(remote.getAddress())) return "public address";
        if (local instanceof Inet4Address && isWifiClientAddress(local)) {
            return "arrived on Wi-Fi rather than the hotspot";
        }
        return null;
    }

    private static boolean isPrivateV4(byte[] b) {
        if (b.length != 4) return false;
        int a0 = b[0] & 0xff, a1 = b[1] & 0xff;
        return (a0 == 10)
                || (a0 == 192 && a1 == 168)
                || (a0 == 172 && a1 >= 16 && a1 <= 31)
                || (a0 == 169 && a1 == 254);
    }

    /**
     * Is this one of our own Wi-Fi client addresses?
     *
     * Tests the socket's local address, so it is exact, and requires
     * NET_CAPABILITY_INTERNET: while the hotspot runs, Android also reports the
     * local-only tethering network as TRANSPORT_WIFI with the very subnet every
     * laptop connects from, and matching on transport alone would reject the one
     * connection this app exists to serve.
     */
    private boolean isWifiClientAddress(InetAddress local) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null) continue;
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (local.equals(la.getAddress())) return true;
                }
            }
        } catch (Exception ignored) {
            // If we cannot tell, allow it: the pinned-key handshake is the real
            // gate and this must never be what blocks the laptop.
        }
        return false;
    }

    private String myAddresses() {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address)) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(a.getHostAddress());
                }
            }
        } catch (Exception e) {
            return "unknown";
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    // ------------------------------------------------------------------
    // Pairing
    // ------------------------------------------------------------------

    /** Generating a new code replaces the old one, so only the newest can work. */
    public String startPairing() {
        String code = Pairing.newCode();
        pairCode = code;
        pairExpiresAt = System.currentTimeMillis() + Pairing.VALID_MS;
        lastActivity = System.currentTimeMillis();
        return code;
    }

    public void cancelPairing() {
        pairCode = null;
        pairExpiresAt = 0;
    }

    public long pairingExpiresAt() { return pairExpiresAt; }

    private String activePairCode() {
        expirePairCodeIfNeeded();
        return pairCode;
    }

    private void expirePairCodeIfNeeded() {
        if (pairCode != null && System.currentTimeMillis() > pairExpiresAt) {
            pairCode = null;
            pairExpiresAt = 0;
        }
    }

    public void unpairAndNotify() {
        vault.forgetLaptop();
        state.clear();
        state("Not paired", running, false);

        final Session s = live;
        if (s == null) return;

        // Called from a dialog button on the main thread, where Android forbids
        // network I/O outright. Best effort, and nothing waits on it.
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    s.send(Wire.UNPAIR);
                } catch (IOException ignored) { }
                s.closeQuietly();
            }
        }, "unpair-notify").start();
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Transfers",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent serviceIntent(String action, String id, int requestCode) {
        Intent i = new Intent(this, LinkService.class).setAction(action);
        if (id != null) i.putExtra("id", id);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Builder base() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setColor(0xFF3A51FA)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
    }

    private Notification.Action action(String label, String intentAction, String id, int code) {
        return new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                label, serviceIntent(intentAction, id, code)).build();
    }

    private Notification idleNotification() {
        return base()
                .setContentTitle("Localink")
                .setContentText("Waiting for your laptop")
                .addAction(action("Stop", ACTION_STOP, null, 1))
                .build();
    }

    /**
     * One line of file name by default, expandable to the whole thing, with the
     * bar and percentage always visible, and the same controls the app offers.
     */
    private void pushProgressNotification(String name, long done, long total,
                                          long bytesPerSec, boolean sending) {
        int pct = total <= 0 ? 0 : (int) (done * 100L / total);
        String line = pct + "%   " + Hexes.humanBytes(done) + " / " + Hexes.humanBytes(total)
                + (bytesPerSec > 0 ? "   " + Hexes.humanRate(bytesPerSec) : "");

        post(base()
                .setContentTitle((sending ? "Sending  " : "Receiving  ") + name)
                .setContentText(line)
                .setStyle(new Notification.BigTextStyle().bigText(name + "\n" + line))
                .setProgress(100, pct, false)
                .addAction(action("Pause", ACTION_PAUSE, null, 2))
                .addAction(action("Stop", ACTION_STOP, null, 1))
                .build());
    }

    private void showPausedNotification(PausedView p) {
        int pct = p.total <= 0 ? 0 : (int) (p.done * 100L / p.total);
        post(base()
                .setContentTitle("Paused  " + p.name)
                .setContentText(pct + "%   " + Hexes.humanBytes(p.done) + " / "
                        + Hexes.humanBytes(p.total))
                .setStyle(new Notification.BigTextStyle().bigText(p.name))
                .setProgress(100, pct, false)
                .addAction(action("Resume", ACTION_RESUME, p.id, 3))
                .addAction(action("Cancel", ACTION_CANCEL, p.id, 4))
                .build());
    }

    private void showIdleNotification() {
        PausedView p = firstPaused();
        if (p != null) showPausedNotification(p);
        else post(idleNotification());
    }

    private void showNotification(String title, String text) {
        post(base().setContentTitle(title).setContentText(text)
                .addAction(action("Stop", ACTION_STOP, null, 1))
                .build());
    }

    private void post(Notification n) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && running) nm.notify(NOTE_ID, n);
    }

    // ------------------------------------------------------------------

    private void state(String status, boolean listening, boolean connected) {
        Watcher w = watcher;
        if (w != null) w.onState(status, listening, connected);
    }

    private void log(String line) {
        Watcher w = watcher;
        if (w != null) w.onLog(line);
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------

    /** Files waiting to go to the laptop, queued by the share sheet. */
    public static final class Outbox {
        private Outbox() {}

        private static final List<Item> ITEMS = new ArrayList<Item>();

        /** Ignores anything already queued: a duplicate would be sent twice. */
        public static void add(List<Item> items) {
            synchronized (ITEMS) {
                for (Item it : items) {
                    boolean already = false;
                    for (Item q : ITEMS) {
                        if (q.id.equals(it.id)) { already = true; break; }
                    }
                    if (!already) ITEMS.add(it);
                }
            }
        }

        public static List<Item> drain() {
            synchronized (ITEMS) {
                List<Item> out = new ArrayList<Item>(ITEMS);
                ITEMS.clear();
                return out;
            }
        }

        public static void putBack(List<Item> items) {
            synchronized (ITEMS) { ITEMS.addAll(0, items); }
        }

        public static int size() {
            synchronized (ITEMS) { return ITEMS.size(); }
        }
    }
}
