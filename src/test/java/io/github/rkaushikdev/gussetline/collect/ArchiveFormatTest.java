package io.github.rkaushikdev.gussetline.collect;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.collect.Archive.Pruned;
import io.github.rkaushikdev.gussetline.util.Text;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The archive's form on disk is fixed: where a blob is filed, what its digest is, and every byte of
 * a manifest line. An archive written before this release therefore keeps loading, and what is
 * written now sits beside what was written then.
 *
 * <p>The archive under {@code archive/raw} in the test resources was written by an earlier release:
 * one payload seen twice and a gateway timeout, with a context that needs escaping. {@code
 * archive/pruned.jsonl} is its manifest after a prune at midnight on 13 May 2025.
 */
class ArchiveFormatTest {

    private static final byte[] FARES = "{\"fares\":[{\"basis\":\"KLWAP7RT\"}]}".getBytes(UTF_8);
    private static final byte[] TIMEOUT = "gateway timeout".getBytes(UTF_8);
    private static final String FARES_DIGEST = "16b3193667b85a4fadb148429fbef5c6";
    private static final String TIMEOUT_DIGEST = "81f748ff678a375012a25c4bc744f663";
    private static final Map<String, Object> FARES_ASKED = Map.of("market", "FRA-NYC", "departure", "2025-06-04");
    private static final Map<String, Object> TIMEOUT_ASKED = Map.of("market", "MUC-CHI", "note", "Zürich ✈ \"quoted\"");

    @TempDir
    Path tmp;

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(ArchiveFormatTest.class.getResource("archive/" + name).toURI());
    }

    /** A copy of the stored archive to work on, so the resources never change. */
    private Path copyOfTheStoredArchive() throws IOException, URISyntaxException {
        Path source = resource("raw");
        Path target = tmp.resolve("raw");
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Files.copy(path, target.resolve(source.relativize(path).toString()));
            }
        }
        return target;
    }

    /** A stored text file's lines, each ending in a line feed whatever the checkout did to them. */
    private static String linesOf(Path file) throws IOException {
        return Text.lines(Files.readString(file, UTF_8)).stream()
                .map(line -> line + "\n")
                .collect(Collectors.joining());
    }

    /** Every blob, as its path under the root. */
    private static List<String> blobsUnder(Archive archive) throws IOException {
        try (Stream<Path> walk = Files.walk(archive.blobs())) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> archive.root().relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void anArchiveWrittenBeforeStillLoads() throws Exception {
        Archive archive = new Archive(copyOfTheStoredArchive());

        assertThat(archive.storedCount()).isEqualTo(2);
        assertThat(archive.get(FARES_DIGEST)).containsExactly(FARES);
        assertThat(archive.get(TIMEOUT_DIGEST)).containsExactly(TIMEOUT);
        assertThat(archive.verify()).isEmpty();

        List<Map<String, Object>> rows = archive.manifest();
        assertThat(rows.stream().map(row -> row.get("seen_at")))
                .containsExactly("2025-05-12T09:00:00", "2025-05-13T09:00:00.123456", "2025-05-12T09:05:00");
        assertThat(rows.stream().map(row -> row.get("fresh"))).containsExactly(true, false, true);
        assertThat(rows.get(2)).containsEntry("status", 504).containsEntry("context", TIMEOUT_ASKED);
        assertThat(archive.replay().map(replayed -> replayed.row().get("digest")))
                .containsExactly(FARES_DIGEST, TIMEOUT_DIGEST);
    }

    @Test
    void aSightingIsFiledAndWrittenTheWayTheStoredOnesAre() throws Exception {
        Archive fresh = new Archive(tmp.resolve("fresh"));
        fresh.put(FARES, "gds", "/search", LocalDateTime.of(2025, 5, 12, 9, 0), FARES_ASKED);
        fresh.put(FARES, "gds", "/search", LocalDateTime.of(2025, 5, 13, 9, 0, 0, 123_456_000), FARES_ASKED);
        fresh.put(TIMEOUT, "ndc", "/v2/offers", LocalDateTime.of(2025, 5, 12, 9, 5), 504, TIMEOUT_ASKED);

        Archive stored = new Archive(copyOfTheStoredArchive());
        assertThat(Files.readString(fresh.manifestPath(), UTF_8)).isEqualTo(linesOf(stored.manifestPath()));
        assertThat(blobsUnder(fresh)).isEqualTo(blobsUnder(stored));
    }

    @Test
    void aPruneRewritesTheManifestInTheFormItWasWrittenIn() throws Exception {
        Archive archive = new Archive(copyOfTheStoredArchive());

        assertThat(archive.prune(LocalDateTime.of(2025, 5, 13, 0, 0))).isEqualTo(new Pruned(2, 1, 1));

        assertThat(Files.readString(archive.manifestPath(), UTF_8)).isEqualTo(linesOf(resource("pruned.jsonl")));
        assertThat(archive.holds(FARES_DIGEST)).isTrue();
        assertThat(archive.holds(TIMEOUT_DIGEST)).isFalse();
        assertThat(archive.root().resolve("manifest.rewriting")).doesNotExist();
    }
}
