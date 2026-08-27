package com.worldshare.mod.cloud;

import com.google.gson.JsonSyntaxException;
import com.worldshare.mod.sync.BucketLayout;
import com.worldshare.mod.sync.WorldManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control file is the one remote document every client trusts: it carries the
 * manifest and the session lock together, and it is written in place forever
 * rather than replaced. These cover the parsing edges, because a control file that
 * throws on read is a world nobody can open.
 */
class ControlFileTest {

    @Test
    @DisplayName("a control file round-trips through JSON")
    void roundTripsThroughJson() {
        final ControlFile original = ControlFile.initial(16, Instant.parse("2026-08-27T12:00:00Z"));
        original.manifestOrEmpty().put("level.dat",
                new WorldManifest.Entry("abc123", 4096L, "2026-08-27T12:00:00Z"));

        final ControlFile parsed = ControlFile.fromJson(original.toJson());

        assertEquals(16, parsed.bucketCount);
        assertEquals(1, parsed.manifestOrEmpty().size());
        assertEquals("abc123", parsed.manifestOrEmpty().get("level.dat").sha256);
        assertTrue(parsed.lockOrUnlocked().isUnlocked());
    }

    @Test
    @DisplayName("a control file with no lock reads as unlocked rather than throwing")
    void missingLockReadsAsUnlocked() {
        // "Nobody holds it" is the safe interpretation: worst case someone acquires
        // a lock that was already free. Throwing here would make the world unopenable.
        final ControlFile parsed = ControlFile.fromJson("{\"schemaVersion\":1,\"bucketCount\":8}");
        assertNotNull(parsed.lockOrUnlocked());
        assertTrue(parsed.lockOrUnlocked().isUnlocked());
    }

    @Test
    @DisplayName("a control file with no manifest reads as an empty world")
    void missingManifestReadsAsEmpty() {
        final ControlFile parsed = ControlFile.fromJson("{\"schemaVersion\":1,\"bucketCount\":8}");
        assertNotNull(parsed.manifestOrEmpty());
        assertEquals(0, parsed.manifestOrEmpty().size());
    }

    @Test
    @DisplayName("a missing or nonsensical bucket count falls back to the default")
    void invalidBucketCountFallsBack() {
        // Predates the field, or hand-edited. Anything is better than dividing by
        // zero deep inside the sync engine.
        assertEquals(BucketLayout.DEFAULT_BUCKET_COUNT,
                ControlFile.fromJson("{\"schemaVersion\":1}").bucketCount);
        assertEquals(BucketLayout.DEFAULT_BUCKET_COUNT,
                ControlFile.fromJson("{\"schemaVersion\":1,\"bucketCount\":0}").bucketCount);
        assertEquals(BucketLayout.DEFAULT_BUCKET_COUNT,
                ControlFile.fromJson("{\"schemaVersion\":1,\"bucketCount\":-4}").bucketCount);
    }

    @Test
    @DisplayName("malformed JSON is rejected rather than silently accepted")
    void malformedJsonThrows() {
        assertThrows(JsonSyntaxException.class, () -> ControlFile.fromJson("{not json"));
        assertThrows(JsonSyntaxException.class, () -> ControlFile.fromJson("null"));
    }

    @Test
    @DisplayName("layout agreement is checked on bucket count")
    void layoutMatching() {
        final ControlFile control = ControlFile.initial(8, Instant.now());
        assertTrue(control.matchesLayout(new BucketLayout(8)));
        assertFalse(control.matchesLayout(new BucketLayout(16)));
        assertEquals(8, control.layout().bucketCount());
    }

    // ---------------------------------------------------------------- lock states

    @Test
    @DisplayName("an unlocked session is available and owned by nobody")
    void unlockedIsAvailable() {
        final SessionLock lock = SessionLock.unlocked(Instant.now());

        assertTrue(lock.isUnlocked());
        assertTrue(lock.isAvailable(Instant.now()));
        assertFalse(lock.isOwnedBy("any-machine"),
                "an unlocked lock must not match any machine id");
        assertEquals(SessionLock.STATUS_UNLOCKED, lock.status);
    }

    @Test
    @DisplayName("a held lock is unavailable until it expires")
    void heldLockExpires() {
        final Instant acquired = Instant.parse("2026-08-27T12:00:00Z");
        final SessionLock lock = SessionLock.newAcquired(
                "Grant", "machine-a", acquired, Duration.ofHours(24), 5);

        assertFalse(lock.isUnlocked());
        assertTrue(lock.isOwnedBy("machine-a"));
        assertFalse(lock.isOwnedBy("machine-b"));

        assertFalse(lock.isAvailable(acquired.plus(Duration.ofHours(23))),
                "still held 23h in");
        assertTrue(lock.isAvailable(acquired.plus(Duration.ofHours(25))),
                "stale a day later");
    }

    @Test
    @DisplayName("an unparseable expiry is treated as stale, not as never expiring")
    void badExpiryIsStale() {
        // Fail toward "available". The opposite reading would leave a world locked
        // forever by a corrupt timestamp.
        final SessionLock lock = SessionLock.newAcquired(
                "Grant", "m", Instant.now(), Duration.ofHours(1), 5);
        lock.expiresAt = "not-a-timestamp";

        assertTrue(lock.isExpired(Instant.now()));
        assertTrue(lock.isAvailable(Instant.now()));
    }

    @Test
    @DisplayName("a lock round-trips through JSON with its holder intact")
    void lockRoundTrips() {
        final SessionLock original = SessionLock.newAcquired(
                "Grant", "machine-a", Instant.parse("2026-08-27T12:00:00Z"),
                Duration.ofHours(24), 5);

        final SessionLock parsed = SessionLock.fromJson(original.toJson());

        assertEquals("Grant", parsed.holderName);
        assertTrue(parsed.isOwnedBy("machine-a"));
        assertEquals(SessionLock.STATUS_HOSTING, parsed.status);
        assertEquals(java.util.List.of("Grant"), parsed.playersOnline());
    }
}
