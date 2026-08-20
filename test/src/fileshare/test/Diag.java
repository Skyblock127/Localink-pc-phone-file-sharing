package fileshare.test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import fileshare.core.Session;
import fileshare.pc.Gateways;

/**
 * Prints what the laptop can see, and whether the phone is listening.
 *
 * Run with: build-pc.bat diag
 */
public final class Diag {

    public static void main(String[] args) {
        System.out.println("Local network interfaces");
        System.out.println("  " + Gateways.describeLocalNetworks());
        System.out.println();

        List<InetAddress> candidates = Gateways.candidates(null);
        System.out.println("Addresses the app would try for the phone");
        if (candidates.isEmpty()) {
            System.out.println("  (none) -- this laptop is not on any private network.");
            System.out.println("  Connect to the phone's hotspot, or turn on USB tethering.");
            return;
        }

        for (InetAddress a : candidates) {
            System.out.print("  " + a.getHostAddress() + " ... ");
            System.out.flush();

            boolean reachable = false;
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(a, Session.PORT), 1500);
                s.close();
                reachable = true;
            } catch (Exception e) {
                System.out.print(e.getClass().getSimpleName());
            }
            System.out.println(reachable
                    ? "FileShare is listening here"
                    : "  (nothing on port " + Session.PORT + ")");
        }

        System.out.println();
        System.out.println("If every line says nothing is listening, open FileShare on the");
        System.out.println("phone, unlock it, and tap Ready to receive. The phone only listens");
        System.out.println("while it is armed.");
    }
}
