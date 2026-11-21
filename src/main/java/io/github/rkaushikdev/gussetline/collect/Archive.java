package io.github.rkaushikdev.gussetline.collect;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.retention.Kept;
import io.github.rkaushikdev.gussetline.retention.Retention;
import io.github.rkaushikdev.gussetline.util.Json;
import io.github.rkaushikdev.gussetline.util.Text;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The raw archive, which is the only thing that makes an audit arguable.
 *
 * <p>Every payload is written to disk exactly as it arrived, keyed by the hash of its own bytes,
 * before anything tries to parse it. Three reasons, in the order they came up.
 *
 * <p>A supplier disputes a finding. The desk needs the payload it actually sent, not our reading of
 * it, and not the payload it sends today.
 *
 * <p>A parser is wrong. Every payload ever collected can be reparsed without asking anybody for
 * anything, which turns a parser fix from a fortnight of waiting for new data into an afternoon.
 *
 * <p>And the same payload arrives twice, which happens constantly, because most markets do not move
 * between polls. Content addressing means the second copy costs nothing but a manifest line, and the
 * manifest line is what says the market was checked and had not moved. That distinction is
 * invisible if you only keep what changed.
 *
 * <p>Content is gzipped, because fare payloads are repetitive text and compress to about a tenth.
 *
 * <p>Writing is the part that needed care. A content addressed store answers "do we hold this" by
 * asking whether a file exists at the path the hash names. Write that file in place and a process
 * killed partway through leaves a short one sitting at exactly that path. Every later poll then says
 * yes, we hold it, and never fetches the payload again. The audit that needed the supplier's own
 * bytes gets a decompression error, or worse, a truncated document that parses. So a blob is written
 * beside itself and moved into place, which is atomic on both the platforms this runs on, and a
 * reader checks that the bytes it got back still hash to the name it asked for. That check is cheap
 * next to the network call that would otherwise be needed to replace them, and it turns an
 * undetectable wrong answer into a loud one.
 *
 * <p>On disk, under the root: {@code blobs/ab/cd/abcd...}, each blob filed under its digest in two
 * levels of directories named for the digest's first two pairs of characters, and {@code
 * manifest.jsonl}, one sighting per line as a JSON object with its keys in order ({@link Json}). The
 * layout and the digests are fixed, so an existing archive keeps loading.
 *
 * <p>Several threads may store payloads at once. A failure of the disk itself surfaces as an {@link
 * UncheckedIOException}.
 */
public final class Archive {

    /**
     * What a half written blob's name ends with while it is being written. Named so that a sweep can
     * recognise one and so that it can never be mistaken for a digest.
     */
    public static final String PARTIAL = ".partial";

    private static final String BLOBS = "blobs";
    private static final String MANIFEST = "manifest.jsonl";

    /** What the manifest is written as while a prune rewrites it, beside the manifest itself. */
    private static final String REWRITING = "manifest.rewriting";

    /** How many bytes of the hash name a payload. Sixteen is plenty here. */
    private static final int DIGEST_BYTES = 16;

    /**
     * How a finished file is put in place: one move that either happens whole or not at all, onto a
     * name that may already be taken.
     */
    @FunctionalInterface
    interface Mover {

        /** The move every archive makes: an atomic rename. */
        Mover ATOMIC = (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);

        void move(Path source, Path target) throws IOException;
    }

    /**
     * What a scrub took out.
     *
     * @param corrupt blobs removed because they did not read back as what they are filed as
     * @param leftovers part files removed because the write that made them never finished
     */
    public record Scrubbed(int corrupt, int leftovers) {}

    /**
     * What a prune did.
     *
     * @param rows sightings removed from the manifest
     * @param blobs payloads removed because no surviving sighting names them
     * @param kept payloads kept because a surviving sighting still names them, which is the number
     *     worth reading: a payload seen on thirty mornings is one blob and thirty rows
     */
    public record Pruned(int rows, int blobs, int kept) {}

    /**
     * A stored payload handed back for reparsing, with the manifest row that first named it.
     *
     * @param row the manifest row
     * @param payload the payload as it arrived; the record keeps its own copy
     */
    public record Replayed(Map<String, Object> row, byte[] payload) {

        public Replayed {
            row = Collections.unmodifiableMap(new LinkedHashMap<>(row));
            payload = payload.clone();
        }

        /** A copy of the payload, so the record itself never changes. */
        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Replayed that && row.equals(that.row) && Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(row, Arrays.hashCode(payload));
        }

        /** The row and how many bytes the payload holds. */
        @Override
        public String toString() {
            return row + " with " + payload.length + " bytes";
        }
    }

    private final Path root;
    private final Path blobs;
    private final Path manifestPath;
    private final Mover mover;

    /**
     * The archive under {@code root}, made if it is not there yet. Opening one that is there keeps
     * everything in it.
     *
     * @throws UncheckedIOException when the directories cannot be made
     */
    public Archive(Path root) {
        this(root, Mover.ATOMIC);
    }

    /** The archive under {@code root}, moving finished files into place with {@code mover}. */
    Archive(Path root, Mover mover) {
        this.root = root;
        this.blobs = root.resolve(BLOBS);
        this.manifestPath = root.resolve(MANIFEST);
        this.mover = mover;
        try {
            Files.createDirectories(blobs);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** The key a payload is stored under: the first sixteen bytes of its SHA-256, in lower case hex. */
    public static String digestOf(byte[] payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload);
            return HexFormat.of().formatHex(hash, 0, DIGEST_BYTES);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", missing);
        }
    }

    /** The directory everything lives under. */
    public Path root() {
        return root;
    }

    /** The directory the payloads are filed under. */
    public Path blobs() {
        return blobs;
    }

    /** The file the sightings are written to, one line each. */
    public Path manifestPath() {
        return manifestPath;
    }

    /**
     * Whether this payload is here. Existence only, because it is hot.
     *
     * <p>Every poll asks this once per payload, so it stays a stat call. What it cannot tell you is
     * whether the bytes are still the bytes, which is what {@link #verify} is for.
     *
     * @throws ValidationError when the digest is too short to be one
     */
    public boolean holds(String digest) {
        return Files.exists(pathFor(digest));
    }

    /** Store a payload from a plain success with no context, and record that it was seen. */
    public Sighting put(byte[] payload, String supplier, String url, LocalDateTime seenAt) {
        return put(payload, supplier, url, seenAt, Sighting.OK, Map.of());
    }

    /** Store a payload from a plain success, and record that it was seen. */
    public Sighting put(byte[] payload, String supplier, String url, LocalDateTime seenAt, Map<String, ?> context) {
        return put(payload, supplier, url, seenAt, Sighting.OK, context);
    }

    /**
     * Store a payload and record that it was seen.
     *
     * <p>A payload we already hold is not written again and the sighting is marked not fresh, which
     * is how the coverage report tells a market that did not move from one that was never polled. An
     * answer that was not a success is archived too, with its status.
     *
     * @throws UncheckedIOException when the payload or its manifest line cannot be written. A payload
     *     that fails on its way into place leaves nothing filed under its digest
     */
    public Sighting put(
            byte[] payload, String supplier, String url, LocalDateTime seenAt, int status, Map<String, ?> context) {
        String digest = digestOf(payload);
        Path target = pathFor(digest);
        boolean fresh = !Files.exists(target);
        try {
            if (fresh) {
                write(target, payload);
            }
            Sighting sighting = new Sighting(
                    digest, supplier, url, seenAt, status, fresh, payload.length, new LinkedHashMap<>(context));
            byte[] line = (Json.write(sighting.asMap()) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(manifestPath, line, CREATE, APPEND, WRITE);
            return sighting;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * The payload as it arrived, checked against the name it is under.
     *
     * <p>A blob that does not hash to its own name is not returned. Handing back bytes a supplier did
     * not send is worse than handing back nothing, because the whole reason anybody reads this store
     * is to settle an argument about what was sent.
     *
     * @throws ValidationError naming the digest when nothing is stored under it, when what is stored
     *     will not decompress, or when it decompresses to something that is not what it is filed as
     */
    public byte[] get(String digest) {
        Path target = pathFor(digest);
        if (!Files.exists(target)) {
            throw new ValidationError("nothing stored under that digest", Details.of("digest", digest));
        }
        byte[] payload;
        try {
            payload = decompressed(Files.readAllBytes(target));
        } catch (IOException unreadable) {
            ValidationError refusal =
                    new ValidationError("stored payload will not decompress", Details.of("digest", digest));
            refusal.initCause(unreadable);
            throw refusal;
        }
        if (!digestOf(payload).equals(digest)) {
            throw new ValidationError("stored payload is not what it is filed as", Details.of("digest", digest));
        }
        return payload;
    }

    /**
     * Every stored payload read back and checked. Returns the digests of the bad ones, in path order.
     * A part file is not a payload that failed its check, and is left alone.
     *
     * <p>Slow on purpose and not on any hot path. Run it after a machine goes down mid poll, or on a
     * schedule if the disk is old.
     */
    public List<String> verify() {
        List<String> bad = new ArrayList<>();
        for (Path blob : files(name -> !name.endsWith(PARTIAL))) {
            String digest = blob.getFileName().toString();
            try {
                get(digest);
            } catch (ValidationError refused) {
                bad.add(digest);
            }
        }
        return List.copyOf(bad);
    }

    /**
     * Take out what cannot be read and what was never finished.
     *
     * <p>Removing a corrupt blob is the repair: the digest goes back to being one we do not hold, so
     * the next poll fetches it again.
     */
    public Scrubbed scrub() {
        List<String> corrupt = verify();
        try {
            for (String digest : corrupt) {
                Files.deleteIfExists(pathFor(digest));
            }
            List<Path> leftovers = files(name -> name.endsWith(PARTIAL));
            for (Path leftover : leftovers) {
                Files.deleteIfExists(leftover);
            }
            return new Scrubbed(corrupt.size(), leftovers.size());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * Every sighting in the manifest, in the order they were written, each as the row its line holds.
     * A fresh archive has none.
     *
     * @throws IllegalArgumentException when a line is not a JSON object
     */
    public List<Map<String, Object>> manifest() {
        if (!Files.exists(manifestPath)) {
            return List.of();
        }
        String text;
        try {
            text = Files.readString(manifestPath, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Text.lines(text)) {
            if (!Text.isBlank(line)) {
                rows.add(Json.readObject(line));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Every sighting of one payload, in the order they were written.
     *
     * @throws NoSuchElementException when a manifest row carries no digest
     */
    public List<Map<String, Object>> sightingsOf(String digest) {
        return manifest().stream()
                .filter(row -> digest.equals(field(row, "digest")))
                .toList();
    }

    /**
     * Every stored payload once, for reparsing after a parser is fixed, with the manifest row that
     * first named it, in manifest order.
     *
     * <p>Each payload is read as the stream reaches it, so a whole store is never held at once. A
     * payload that fails its check stops the stream rather than being reparsed into observations
     * nobody can trace.
     *
     * @throws ValidationError from the stream, when a payload does not read back as what it is filed as
     */
    public Stream<Replayed> replay() {
        return replaying(row -> true);
    }

    /** Every stored payload one supplier sent, once each, as {@link #replay()} gives them. */
    public Stream<Replayed> replay(String supplier) {
        return replaying(row -> supplier.equals(field(row, "supplier")));
    }

    /**
     * Drop the sightings from before a moment, and any payload nothing still names.
     *
     * <p>A sighting with no stamp is kept rather than guessed at ({@link Retention#keepable}). A
     * payload seen on thirty mornings is one blob and thirty rows, and deleting it because one row is
     * old would take the other twenty nine with it, so a blob goes only when no surviving row names it.
     *
     * <p>The manifest is rewritten whole. It is append only everywhere else, so this is the one place
     * that may not be interrupted, and it writes beside the file and moves it into place rather than
     * truncating.
     *
     * @throws NoSuchElementException when a manifest row carries no digest
     */
    public Pruned prune(LocalDateTime before) {
        List<Map<String, Object>> rows = manifest();
        Kept<Map<String, Object>> kept = Retention.keepable(rows, before);
        int droppedRows = rows.size() - kept.rows().size();

        Set<String> unnamed = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            unnamed.add(Text.plain(field(row, "digest")));
        }
        unnamed.removeAll(kept.named());

        try {
            int removed = 0;
            for (String digest : unnamed) {
                Path target = pathFor(digest);
                if (Files.exists(target)) {
                    Files.delete(target);
                    removed++;
                }
            }
            if (droppedRows > 0) {
                Path beside = root.resolve(REWRITING);
                writeDurably(beside, out -> {
                    for (Map<String, Object> row : kept.rows()) {
                        out.write((Json.write(row) + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                });
                mover.move(beside, manifestPath);
            }
            return new Pruned(droppedRows, removed, kept.named().size());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** Finished payloads. A part file is not one and is not counted. */
    public int storedCount() {
        return files(name -> !name.endsWith(PARTIAL)).size();
    }

    /** {@code 12 payloads under /var/gussetline/raw}. */
    @Override
    public String toString() {
        return storedCount() + " payloads under " + root;
    }

    /**
     * Where a digest is filed: {@code blobs/ab/cd/abcd...}.
     *
     * @throws ValidationError when the digest is too short to be one
     */
    public Path pathFor(String digest) {
        if (Text.length(digest) < 4) {
            throw new ValidationError("not a digest", Details.of("digest", digest));
        }
        return blobs.resolve(Text.slice(digest, 0, 2))
                .resolve(Text.slice(digest, 2, 4))
                .resolve(digest);
    }

    /**
     * Put a blob at its path, or leave nothing at that path at all.
     *
     * <p>Beside and then moved, because a file written in place and interrupted leaves a short one
     * under the name of the full payload, and this store answers "do we hold it" by looking for that
     * name.
     *
     * <p>The temporary file goes in the same directory rather than a system temp dir, since a move
     * across filesystems is a copy and a copy can be interrupted, which is the thing being avoided.
     *
     * <p>Its name carries a random tail because the pool stores payloads from several threads at once
     * and two markets that came back identical are two writers heading for one path. Content
     * addressing is what makes that harmless: whoever gets there first wrote the same bytes, so a
     * writer that finds the target already taken drops its own copy rather than fighting for it.
     * Windows will not move a file onto one another process holds open, and there is nothing to gain
     * by winning.
     */
    private void write(Path target, byte[] payload) throws IOException {
        Files.createDirectories(target.getParent());
        String tail = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Path beside = target.resolveSibling(target.getFileName() + "." + tail + PARTIAL);
        byte[] packed = compressed(payload);
        writeDurably(beside, out -> out.write(packed));

        if (Files.exists(target)) {
            Files.deleteIfExists(beside);
            return;
        }
        try {
            mover.move(beside, target);
        } catch (IOException failed) {
            Files.deleteIfExists(beside);
            if (!Files.exists(target)) {
                throw failed;
            }
        }
    }

    /** What goes into a file that is written whole. */
    @FunctionalInterface
    private interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    /** Write a file whole, and make sure it is on the disk before anything moves it into place. */
    private static void writeDurably(Path file, Content content) throws IOException {
        try (FileChannel channel = FileChannel.open(file, CREATE, TRUNCATE_EXISTING, WRITE)) {
            OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel));
            content.writeTo(out);
            out.flush();
            channel.force(true);
        }
    }

    private List<Path> files(Predicate<String> named) {
        if (!Files.isDirectory(blobs)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(blobs)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> named.test(path.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private Stream<Replayed> replaying(Predicate<Map<String, Object>> wanted) {
        Map<Object, Map<String, Object>> first = new LinkedHashMap<>();
        for (Map<String, Object> row : manifest()) {
            if (wanted.test(row)) {
                first.putIfAbsent(field(row, "digest"), row);
            }
        }
        return first.values().stream().map(row -> new Replayed(row, get(Text.plain(row.get("digest")))));
    }

    /** A field every manifest row carries; a row without it is not a sighting. */
    private static Object field(Map<String, Object> row, String name) {
        if (!row.containsKey(name)) {
            throw new NoSuchElementException("a manifest row without a " + name + ": " + row);
        }
        return row.get(name);
    }

    private static byte[] compressed(byte[] payload) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream(payload.length / 4 + 64);
        try (GZIPOutputStream out = new Thorough(packed)) {
            out.write(payload);
        }
        return packed.toByteArray();
    }

    /**
     * The payload inside a stored blob. An empty file holds no compressed member at all and reads as
     * no bytes, so it fails the digest check rather than decompression.
     */
    private static byte[] decompressed(byte[] stored) throws IOException {
        if (stored.length == 0) {
            return stored;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(stored))) {
            return in.readAllBytes();
        }
    }

    /**
     * Gzip at the highest compression level. A payload is written once and read rarely, so the
     * smallest blob is worth the extra time.
     */
    private static final class Thorough extends GZIPOutputStream {

        Thorough(OutputStream out) throws IOException {
            super(out);
            def.setLevel(Deflater.BEST_COMPRESSION);
        }
    }
}
