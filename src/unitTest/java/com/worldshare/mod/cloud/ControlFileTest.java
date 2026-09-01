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

    // -------------------------------------------------------------- layout version

    @Test
    @DisplayName("a control file with no layoutVersion reads as the old layout")
    void missingLayoutVersionMeansOldLayout() {
        // The bug this exists for, and it got past review twice.
        //
        // ControlFile has a public no-arg constructor, so Gson builds it that way
        // and the field initialisers RUN - a key absent from the JSON is left at
        // whatever the field declaration says, not at 0. Initialising layoutVersion
        // to the current version therefore made every world written before the field
        // existed claim to be current, silently disabling the check whose entire job
        // is to stop a changed bucket mapping losing files.
        final ControlFile parsed = ControlFile.fromJson(
                "{\"schemaVersion\":1,\"bucketCount\":16}");

        assertNotNull(parsed);
        assertEquals(ControlFile.LAYOUT_VERSION_BEFORE_VERSIONING, parsed.layoutVersion,
                "a control file that never mentioned a layout must not claim the current one");
    }

    @Test
    @DisplayName("layoutVersion survives a round trip")
    void layoutVersionRoundTrips() {
        final ControlFile control = ControlFile.initial(16, Instant.now());
        assertEquals(BucketLayout.LAYOUT_VERSION, control.layoutVersion,
                "a brand new world is written with the current layout");

        final ControlFile parsed = ControlFile.fromJson(control.toJson());
        assertEquals(BucketLayout.LAYOUT_VERSION, parsed.layoutVersion);
    }

    @Test
    @DisplayName("touching a control file does not promote its layout version")
    void touchDoesNotClaimTheCurrentLayout() {
        // The second bug, which was worse than the first: touch() runs on every
        // control-file write, and taking the session lock is a write, as is every
        // heartbeat. Stamping the version here promoted an old world to the current
        // layout merely by opening it - no archive repacked, the check permanently
        // unable to fire, and the evidence erased.
        //
        // Only the write that publishes a manifest for freshly packed archives may
        // make that claim. See SyncEngine.commitControl.
        final ControlFile old = ControlFile.fromJson(
                "{\"schemaVersion\":1,\"bucketCount\":16}");

        old.touch(Instant.now());

        assertEquals(ControlFile.LAYOUT_VERSION_BEFORE_VERSIONING, old.layoutVersion,
                "writing the lock or a heartbeat says nothing about how the buckets are laid out");
    }

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
