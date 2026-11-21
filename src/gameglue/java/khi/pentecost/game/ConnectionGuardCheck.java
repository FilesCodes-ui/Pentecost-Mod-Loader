package khi.pentecost.game;

import io.netty.channel.embedded.EmbeddedChannel;
import khi.pentecost.core.ConnectionLimiter;

import java.util.ArrayList;
import java.util.List;

public final class ConnectionGuardCheck {
    private static int failures;

    private ConnectionGuardCheck() {}

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        ConnectionLimiter limiter = new ConnectionLimiter();
        EmbeddedChannel listener = new EmbeddedChannel(new ConnectionGuard.Gate(limiter));

        List<EmbeddedChannel> kids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            EmbeddedChannel kid = new EmbeddedChannel();
            kids.add(kid);
            listener.writeInbound(kid);
            expect(listener.readInbound() == kid, "connection " + (i + 1) + " reaches the game");
            expect(kid.isOpen(), "connection " + (i + 1) + " stays open");
        }

        EmbeddedChannel refused = new EmbeddedChannel();
        listener.writeInbound(refused);
        expect(listener.readInbound() == null, "a refused connection never reaches the game");
        expect(!refused.isOpen(), "a refused connection is closed");

        kids.get(0).close();
        EmbeddedChannel again = new EmbeddedChannel();
        listener.writeInbound(again);
        expect(listener.readInbound() == again, "closing a connection frees its slot");
        expect(again.isOpen(), "the new connection stays open");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ConnectionGuardCheck passed");
    }
}
