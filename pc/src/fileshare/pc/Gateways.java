package fileshare.pc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out where the phone is.
 *
 * On a phone hotspot or USB tether the phone IS this machine's gateway, so there
 * is no discovery protocol and no mDNS: we just need the gateway address. With
 * both paths up at once there are two of them on different subnets, and which one
 * Windows picks as the default route depends on interface metrics, so we collect
 * every candidate and let the dialer race them.
 *
 * Handing back a wrong address is harmless. Identity is decided by the pinned
 * certificate, so a candidate that is not the phone simply fails the handshake.
 */
public final class Gateways {
    private Gateways() {}

    private static final Pattern DEFAULT_ROUTE =
            Pattern.compile("^\\s*0\\.0\\.0\\.0\\s+0\\.0\\.0\\.0\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+");

    public static List<InetAddress> candidates(String manualHost) {
        Set<String> seen = new LinkedHashSet<String>();
        List<InetAddress> out = new ArrayList<InetAddress>();

        // A manually entered address always goes first.
        if (manualHost != null && !manualHost.trim().isEmpty()) {
            add(out, seen, manualHost.trim());
        }

        for (String g : routeTableGateways()) add(out, seen, g);
        for (String g : subnetGuesses()) add(out, seen, g);

        return out;
    }

    /**
     * Parse "route print -4" for default routes.
     *
     * Only the numeric columns are read, never the headings, so this works the
     * same on a non-English Windows install.
     */
    private static List<String> routeTableGateways() {
        List<String> out = new ArrayList<String>();
        try {
            ProcessBuilder pb = new ProcessBuilder("route", "print", "-4");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = DEFAULT_ROUTE.matcher(line);
                    if (m.find()) {
                        String gw = m.group(1);
                        if (!"0.0.0.0".equals(gw)) out.add(gw);
                    }
                }
            } finally {
                r.close();
            }
            p.waitFor();
        } catch (Exception e) {
            // Fall through to the subnet guesses below.
        }
        return out;
    }

    /**
     * Guess from our own addresses, as a backstop if the route table is unhelpful.
     *
     * Android puts the hotspot gateway at x.y.z.1 and, historically, the USB
     * tether gateway at 192.168.42.129, so both are worth trying on any /24 we
     * are sitting on.
     */
    private static List<String> subnetGuesses() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    byte[] b = a.getAddress();
                    if (!isPrivate(b)) continue;
                    if (ia.getNetworkPrefixLength() < 16) continue;

                    out.add((b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".1");
                    out.add((b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".129");
                }
            }
        } catch (Exception e) {
            // Nothing useful to do; the dialer will report that it found nothing.
        }
        return out;
    }

    /**
     * RFC1918 and link-local only.
     *
     * This app has no business dialling a public address, and refusing to is one
     * comparison. It also means a stray default route out to the internet never
     * turns into a connection attempt against some stranger's machine.
     */
    private static boolean isPrivate(byte[] b) {
        int a0 = b[0] & 0xff, a1 = b[1] & 0xff;
        if (a0 == 10) return true;
        if (a0 == 192 && a1 == 168) return true;
        if (a0 == 172 && a1 >= 16 && a1 <= 31) return true;
        if (a0 == 169 && a1 == 254) return true;
        return false;
    }

    private static void add(List<InetAddress> out, Set<String> seen, String host) {
        if (host == null || host.isEmpty() || !seen.add(host)) return;
        try {
            InetAddress a = InetAddress.getByName(host);
            if (a instanceof Inet4Address && isPrivate(a.getAddress())) out.add(a);
        } catch (Exception e) {
            // Unresolvable entry; skip it.
        }
    }

    /** Human-readable summary for the status bar when nothing is reachable. */
    public static String describeLocalNetworks() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(ni.getDisplayName()).append(' ').append(ia.getAddress().getHostAddress());
                }
            }
        } catch (Exception e) {
            return "(could not read network interfaces)";
        }
        return sb.length() == 0 ? "(no network)" : sb.toString();
    }
}
