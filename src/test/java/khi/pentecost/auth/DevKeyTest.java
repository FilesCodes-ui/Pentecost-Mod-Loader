package khi.pentecost.auth;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevKeyTest {
    private static final UUID OWNER = UUID.fromString("808323d1-0221-40cc-b69b-f8bcd0d16112");

    @Test
    void publicHalfShipsWithTheGame() {
        assertNotNull(DevKey.publicKey(), "pentecost/dev-key.cer is missing from the resources");
    }

    @Test
    void anythingButTheRealSignatureFails() {
        byte[] c = DevKey.challenge();
        assertFalse(DevKey.verify(DevKey.proofMessage(c, OWNER, "badge"), new byte[384]));
        assertFalse(DevKey.verify(DevKey.proofMessage(c, OWNER, "badge"), null));
    }

    @Test
    void proofsAreBoundToChallengeAccountAndPurpose() {
        Assumptions.assumeTrue(DevKey.held(), "This computer does not hold the developer key");
        byte[] c = DevKey.challenge();
        byte[] sig = DevKey.prove(c, OWNER, "badge");
        assertNotNull(sig);
        assertTrue(DevKey.verify(DevKey.proofMessage(c, OWNER, "badge"), sig));
        assertFalse(DevKey.verify(DevKey.proofMessage(DevKey.challenge(), OWNER, "badge"), sig), "another challenge");
        assertFalse(DevKey.verify(DevKey.proofMessage(c, UUID.randomUUID(), "badge"), sig), "another account");
        assertFalse(DevKey.verify(DevKey.proofMessage(c, OWNER, "admin"), sig), "another purpose");
    }
}
