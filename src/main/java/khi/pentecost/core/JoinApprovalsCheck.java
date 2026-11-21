package khi.pentecost.core;

import khi.pentecost.core.JoinApprovals.Decision;
import khi.pentecost.core.JoinApprovals.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class JoinApprovalsCheck {
    private static int failures;

    private JoinApprovalsCheck() {}

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    public static void main(String[] args) {
        AtomicLong now = new AtomicLong(1000);
        List<Request> asked = new ArrayList<>();
        JoinApprovals a = new JoinApprovals(now::get, asked::add);

        UUID alex = new UUID(1, 1);
        expect(a.check(alex, "Alex") == Decision.ASK, "a stranger is turned away and asked about");
        expect(asked.size() == 1 && asked.get(0).name().equals("Alex"), "the host is asked once");
        expect(a.check(alex, "Alex") == Decision.ASK, "trying again is still turned away");
        expect(asked.size() == 1, "trying again does not ask again");
        expect(a.pending().size() == 1, "one person is waiting");
        expect(!a.approve(new UUID(9, 9)), "someone who never asked cannot be approved");
        expect(a.approve(alex), "a waiting person can be approved");
        expect(a.check(alex, "Alex") == Decision.ALLOW, "an approved account gets in");
        expect(a.pending().isEmpty(), "nobody is waiting after the answer");

        UUID bo = new UUID(2, 2);
        a.check(bo, "Bo");
        a.deny(bo);
        expect(a.check(bo, "Bo") == Decision.DENY, "a refused account stays refused");
        expect(asked.size() == 2, "a refused account does not ask again");

        UUID cy = new UUID(3, 3);
        a.check(cy, "Cy");
        now.addAndGet(JoinApprovals.ASK_MS + 1);
        expect(a.pending().isEmpty(), "an unanswered request is dropped");
        expect(a.check(cy, "Cy") == Decision.ASK, "and a new try asks again");
        expect(asked.size() == 4, "the host is asked again after the old request expired");

        List<Request> asked2 = new ArrayList<>();
        JoinApprovals full = new JoinApprovals(now::get, asked2::add);
        for (int i = 0; i < JoinApprovals.MAX_PENDING; i++) {
            expect(full.check(new UUID(5, i), "P" + i) == Decision.ASK, "waiting person " + i);
        }
        expect(full.check(new UUID(5, 999), "Late") == Decision.FULL, "too many waiting turns more away");
        expect(asked2.size() == JoinApprovals.MAX_PENDING, "the host is not asked about the extra person");
        full.deny(new UUID(5, 0));
        expect(full.check(new UUID(5, 999), "Late") == Decision.ASK, "a free place lets the next one ask");

        String n = JoinApprovals.clean("Al ex<b>!!!averyveryverylongname");
        expect(n.length() <= 16 && n.matches("[A-Za-z0-9_]+"), "names are cleaned for showing: " + n);
        expect(JoinApprovals.clean("<<>>").equals("unknown"), "an empty name is shown as unknown");
        expect(JoinApprovals.clean(null).equals("unknown"), "a missing name is shown as unknown");

        JoinApprovals many = new JoinApprovals(now::get, r -> { });
        for (int i = 0; i < JoinApprovals.MAX_DENIED + 100; i++) {
            many.deny(new UUID(7, i));
        }
        expect(many.check(new UUID(7, 0), "Old") == Decision.ASK, "the oldest refusals are forgotten so memory stays bounded");
        expect(many.check(new UUID(7, JoinApprovals.MAX_DENIED + 99), "New") == Decision.DENY, "recent refusals are kept");

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("JoinApprovalsCheck passed");
    }
}
