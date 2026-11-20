package io.github.rkaushikdev.gussetline.collect;

import static io.github.rkaushikdev.gussetline.collect.Archive.PARTIAL;
import static io.github.rkaushikdev.gussetline.collect.Archive.digestOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.collect.Archive.Replayed;
import io.github.rkaushikdev.gussetline.collect.Archive.Scrubbed;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectArchiveTest {

    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 5, 12, 9, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2025, 5, 12, 15, 30);
    private static final byte[] PAYLOAD = bytes("{\"fares\":[{\"basis\":\"KLWAP7RT\"}]}");
    private static final String URL = "/search";

    @TempDir
    Path tmp;

    private Archive archive;

    @BeforeEach
    void open() {
        archive = new Archive(tmp.resolve("raw"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }

    private static byte[] gzip(byte[] payload) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(packed)) {
            out.write(payload);
        }
        return packed.toByteArray();
    }

    private static byte[] gunzip(byte[] packed) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            return in.readAllBytes();
        }
    }

    /** Every unfinished write under the blobs. */
    private List<Path> partials() throws IOException {
        try (Stream<Path> walk = Files.walk(archive.blobs())) {
            return walk.filter(path -> path.getFileName().toString().endsWith(PARTIAL))
                    .toList();
        }
    }

    /** Leave a part file beside a blob, the way an interrupted write does. */
    private void interruptedWriteBeside(Sighting sighting) throws IOException {
        Path blob = archive.pathFor(sighting.digest());
        Files.write(blob.resolveSibling(blob.getFileName() + PARTIAL), bytes("half a payload"));
    }

    @Test
    void aDigestIsStableAndDependsOnTheBytes() {
        assertThat(digestOf(bytes("one"))).isEqualTo(digestOf(bytes("one")));
        assertThat(digestOf(bytes("one"))).isNotEqualTo(digestOf(bytes("two")));
        assertThat(digestOf(bytes("one"))).hasSize(32);
    }

    @Test
    void storingAPayloadWritesItAndMarksItFresh() {
        Sighting sighting = archive.put(bytes("{\"fares\":[]}"), "gds", "/search", SEEN);
        assertThat(sighting.fresh()).isTrue();
        assertThat(sighting.size()).isEqualTo(12);
        assertThat(archive.holds(sighting.digest())).isTrue();
        assertThat(archive.get(sighting.digest())).containsExactly(bytes("{\"fares\":[]}"));
    }

    @Test
    void theSamePayloadAgainIsNotWrittenTwice() {
        Sighting first = archive.put(bytes("same"), "gds", "/search", SEEN);
        Sighting second = archive.put(bytes("same"), "gds", "/search", LATER);
        assertThat(first.digest()).isEqualTo(second.digest());
        assertThat(first.fresh()).isTrue();
        assertThat(second.fresh()).isFalse();
        assertThat(archive.storedCount()).isEqualTo(1);
    }

    @Test
    void bothSightingsAreRecordedEvenWhenThePayloadRepeats() {
        archive.put(bytes("same"), "gds", "/search", SEEN);
        archive.put(bytes("same"), "gds", "/search", LATER);
        List<Map<String, Object>> rows = archive.manifest();
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(row -> row.get("fresh"))).containsExactly(true, false);
    }

    @Test
    void aDifferentPayloadGetsItsOwnBlob() {
        archive.put(bytes("one"), "gds", "/search", SEEN);
        archive.put(bytes("two"), "gds", "/search", LATER);
        assertThat(archive.storedCount()).isEqualTo(2);
    }

    @Test
    void thePayloadIsStoredCompressed() throws IOException {
        byte[] payload = bytes("x".repeat(5000));
        Sighting sighting = archive.put(payload, "gds", "/search", SEEN);
        byte[] onDisk = Files.readAllBytes(archive.pathFor(sighting.digest()));
        assertThat(onDisk.length).isLessThan(payload.length);
        assertThat(gunzip(onDisk)).containsExactly(payload);
    }

    @Test
    void readingADigestWeDoNotHoldIsRefused() {
        assertThatThrownBy(() -> archive.get("0".repeat(32))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aDigestThatIsNotOneIsRefused() {
        assertThatThrownBy(() -> archive.holds("ab")).isInstanceOf(ValidationError.class);
    }

    @Test
    void theManifestOfAFreshArchiveIsEmpty() {
        assertThat(archive.manifest()).isEmpty();
    }

    @Test
    void theContextRidesAlongOnTheSighting() {
        archive.put(bytes("body"), "gds", "/search", SEEN, Map.of("market", "FRA-JFK", "departure", "2025-06-01"));
        assertThat(archive.manifest().get(0).get("context")).asInstanceOf(MAP).containsEntry("market", "FRA-JFK");
    }

    @Test
    void aNonTwoHundredResponseIsArchivedToo() {
        Sighting sighting = archive.put(bytes("gateway timeout"), "gds", "/search", SEEN, 504, Map.of());
        assertThat(sighting.status()).isEqualTo(504);
        assertThat(archive.manifest().get(0).get("status")).isEqualTo(504);
    }

    @Test
    void theSightingsOfOnePayloadCanBeListed() {
        archive.put(bytes("same"), "gds", "/search", SEEN);
        archive.put(bytes("other"), "gds", "/search", SEEN);
        archive.put(bytes("same"), "gds", "/search", LATER);
        String digest = digestOf(bytes("same"));
        assertThat(archive.sightingsOf(digest)).hasSize(2);
    }

    @Test
    void replayGivesEveryDistinctPayloadOnce() {
        archive.put(bytes("one"), "gds", "/a", SEEN);
        archive.put(bytes("one"), "gds", "/a", LATER);
        archive.put(bytes("two"), "ndc", "/b", SEEN);
        assertThat(archive.replay()
                        .map(replayed -> new String(replayed.payload(), UTF_8))
                        .sorted())
                .containsExactly("one", "two");
    }

    @Test
    void replayCanBeNarrowedToOneSupplier() {
        archive.put(bytes("one"), "gds", "/a", SEEN);
        archive.put(bytes("two"), "ndc", "/b", SEEN);
        assertThat(archive.replay("ndc").map(replayed -> new String(replayed.payload(), UTF_8)))
                .containsExactly("two");
    }

    @Test
    void replayHandsBackTheManifestRowWithThePayload() {
        archive.put(bytes("one"), "gds", "/a", SEEN, Map.of("market", "FRA-JFK"));
        Replayed first = archive.replay().findFirst().orElseThrow();
        assertThat(first.row().get("context")).asInstanceOf(MAP).containsEntry("market", "FRA-JFK");
        assertThat(first.payload()).containsExactly(bytes("one"));
    }

    @Test
    void anArchiveReopenedOnTheSameDirectoryKeepsEverything() {
        Archive first = new Archive(tmp.resolve("raw"));
        Sighting sighting = first.put(bytes("kept"), "gds", "/a", SEEN);
        Archive second = new Archive(tmp.resolve("raw"));
        assertThat(second.holds(sighting.digest())).isTrue();
        assertThat(second.get(sighting.digest())).containsExactly(bytes("kept"));
        assertThat(second.manifest()).hasSize(1);
    }

    @Test
    void aSightingReadsBackInOneLine() {
        Sighting sighting = new Sighting("abcdef0123", "gds", "/a", SEEN);
        assertThat(sighting).hasToString("2025-05-12 09:00 gds new abcdef01");
        Sighting stale = new Sighting("abcdef0123", "gds", "/a", SEEN, Sighting.OK, false, 0, Map.of());
        assertThat(stale.toString()).contains("same");
    }

    @Test
    void anArchiveReadsBackWithItsSize() {
        archive.put(bytes("one"), "gds", "/a", SEEN);
        assertThat(archive.toString()).startsWith("1 payloads under ");
    }

    @Test
    void aStoredPayloadIsCheckedAgainstTheNameItIsUnder() throws IOException {
        // The whole reason anybody reads this store is to settle an argument
        // about what a supplier sent. Handing back bytes they did not send is
        // worse than handing back nothing.
        Sighting sighting = archive.put(PAYLOAD, "gds", URL, SEEN);
        Files.write(archive.pathFor(sighting.digest()), gzip(bytes("something else entirely")));

        assertThatThrownBy(() -> archive.get(sighting.digest()))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not what it is filed as");
    }

    @Test
    void aPayloadThatWillNotDecompressSaysSoRatherThanRaisingAGzipError() throws IOException {
        Sighting sighting = archive.put(PAYLOAD, "gds", URL, SEEN);
        Files.write(archive.pathFor(sighting.digest()), bytes("truncated, and not gzip"));

        assertThatThrownBy(() -> archive.get(sighting.digest()))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("will not decompress");
    }

    /** One move a watched archive made. */
    private record Move(Path source, Path target) {}

    @Test
    void aBlobIsMovedIntoPlaceRatherThanWrittenInPlace() {
        List<Move> moves = new ArrayList<>();
        Archive watched = new Archive(tmp.resolve("raw"), (source, target) -> {
            moves.add(new Move(source, target));
            Archive.Mover.ATOMIC.move(source, target);
        });

        Sighting sighting = watched.put(PAYLOAD, "gds", URL, SEEN);

        assertThat(moves).hasSize(1);
        assertThat(moves.get(0).source().toString()).endsWith(PARTIAL);
        assertThat(moves.get(0).target()).isEqualTo(watched.pathFor(sighting.digest()));
    }

    @Test
    void aWriteThatDiesBeforeTheMoveLeavesNothingUnderTheDigest() {
        // The failure this is all for. A short file under the name of the full
        // payload answers yes to holds, so the payload is never fetched again
        // and nothing ever reports it.
        IOException down = new IOException("the machine went down");
        Archive dying = new Archive(tmp.resolve("raw"), (source, target) -> {
            throw down;
        });

        assertThatThrownBy(() -> dying.put(PAYLOAD, "gds", URL, SEEN))
                .isInstanceOf(UncheckedIOException.class)
                .cause()
                .isSameAs(down);

        assertThat(archive.holds(digestOf(PAYLOAD))).isFalse();
    }

    @Test
    void nothingIsLeftBehindByAWriteThatFinished() throws IOException {
        archive.put(PAYLOAD, "gds", URL, SEEN);
        assertThat(partials()).isEmpty();
    }

    @Test
    void aStoreThatIsIntactVerifiesClean() {
        archive.put(PAYLOAD, "gds", URL, SEEN);
        archive.put(bytes("another payload"), "ndc", URL, SEEN);
        assertThat(archive.verify()).isEmpty();
    }

    @Test
    void verifyNamesThePayloadsThatDoNotReadBack() throws IOException {
        Sighting good = archive.put(PAYLOAD, "gds", URL, SEEN);
        Sighting bad = archive.put(bytes("another payload"), "ndc", URL, SEEN);
        Files.write(archive.pathFor(bad.digest()), bytes("not gzip at all"));

        assertThat(archive.verify()).containsExactly(bad.digest());
        assertThat(archive.verify()).doesNotContain(good.digest());
    }

    @Test
    void verifyLeavesAnUnfinishedWriteAlone() throws IOException {
        // A part file is not a payload that failed its check. It is one that was
        // never finished, and it is not filed under a digest at all.
        Sighting sighting = archive.put(PAYLOAD, "gds", URL, SEEN);
        interruptedWriteBeside(sighting);
        assertThat(archive.verify()).isEmpty();
    }

    @Test
    void scrubbingTakesOutWhatCannotBeRead() throws IOException {
        Sighting bad = archive.put(bytes("another payload"), "ndc", URL, SEEN);
        Files.write(archive.pathFor(bad.digest()), bytes("not gzip at all"));

        assertThat(archive.scrub()).isEqualTo(new Scrubbed(1, 0));
        assertThat(archive.holds(bad.digest())).isFalse();
    }

    @Test
    void scrubbingACorruptPayloadLetsTheNextPollFetchItAgain() throws IOException {
        // Deleting it is the repair. The digest goes back to being one the desk
        // does not hold, so the collector stops believing it has the payload.
        Sighting sighting = archive.put(PAYLOAD, "gds", URL, SEEN);
        Files.write(archive.pathFor(sighting.digest()), bytes("not gzip at all"));
        archive.scrub();

        Sighting again = archive.put(PAYLOAD, "gds", URL, SEEN);
        assertThat(again.fresh()).isTrue();
        assertThat(archive.get(again.digest())).containsExactly(PAYLOAD);
    }

    @Test
    void scrubbingTakesOutAWriteThatWasInterrupted() throws IOException {
        Sighting sighting = archive.put(PAYLOAD, "gds", URL, SEEN);
        interruptedWriteBeside(sighting);

        assertThat(archive.scrub()).isEqualTo(new Scrubbed(0, 1));
        assertThat(archive.get(sighting.digest())).containsExactly(PAYLOAD);
    }

    @Test
    void scrubbingAHealthyStoreDoesNothingAtAll() {
        archive.put(PAYLOAD, "gds", URL, SEEN);
        assertThat(archive.scrub()).isEqualTo(new Scrubbed(0, 0));
        assertThat(archive.storedCount()).isEqualTo(1);
    }

    @Test
    void anUnfinishedWriteIsNotCountedAsAStoredPayload() throws IOException {
        Sighting sighting = archive.put(PAYLOAD, "gds", URL, SEEN);
        interruptedWriteBeside(sighting);
        assertThat(archive.storedCount()).isEqualTo(1);
    }

    @Test
    void replayingOverACorruptPayloadRefusesRatherThanReparsingIt() throws IOException {
        // A parser fix is rerun over the whole store, and a blob that decompresses
        // to something else would be reparsed into observations nobody can trace.
        Sighting sighting = archive.put(PAYLOAD, "gds", URL, SEEN);
        Files.write(archive.pathFor(sighting.digest()), gzip(bytes("something else")));

        assertThatThrownBy(() -> archive.replay().toList()).isInstanceOf(ValidationError.class);
    }

    @Test
    void twoThreadsStoringTheSamePayloadDoNotFightOverIt() throws Exception {
        // The pool stores payloads from several threads at once, and two markets
        // that came back identical are two writers heading for one path.
        List<Sighting> seen = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Sighting>> futures = new ArrayList<>();
            for (int writer = 0; writer < 8; writer++) {
                futures.add(pool.submit(() -> archive.put(PAYLOAD, "gds", URL, SEEN)));
            }
            for (Future<Sighting> future : futures) {
                seen.add(future.get());
            }
        }

        assertThat(seen.stream().map(Sighting::digest).distinct()).hasSize(1);
        assertThat(archive.get(seen.get(0).digest())).containsExactly(PAYLOAD);
        assertThat(archive.storedCount()).isEqualTo(1);
        assertThat(partials()).isEmpty();
    }

    @Test
    void aSecondWriterLeavesThePayloadThatIsAlreadyThere() throws IOException {
        // Content addressing is what makes the race harmless. Whoever got there
        // first wrote the same bytes, so there is nothing to win.
        Sighting first = archive.put(PAYLOAD, "gds", URL, SEEN);
        byte[] written = Files.readAllBytes(archive.pathFor(first.digest()));
        archive.put(PAYLOAD, "ndc", URL, LATER);
        assertThat(Files.readAllBytes(archive.pathFor(first.digest()))).containsExactly(written);
    }
}
