package io.github.rkaushikdev.gussetline.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * One issued key, by its hash.
 *
 * <p>A key is stored as a hash and never as itself, so a leaked configuration file does not hand
 * anybody a working key. The desk generates one, writes down the plain text once, and configures the
 * hash.
 *
 * @param name who the key was issued to, which every refusal names so a log says who
 * @param digest the key's {@link Security#fingerprint}
 * @param role what the key may do
 */
public record ApiKey(String name, String digest, Role role) {

    public ApiKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(role, "role");
    }

    /** A reader's key. */
    public ApiKey(String name, String digest) {
        this(name, digest, Role.READER);
    }

    /**
     * Whether a presented key is this one. The comparison takes the same time wherever the two first
     * differ: the keys are short, and an early return on the first wrong character is a measurable
     * difference over enough requests.
     */
    public boolean matches(String presented) {
        return MessageDigest.isEqual(
                digest.getBytes(StandardCharsets.UTF_8),
                Security.fingerprint(presented).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code anna as agent}. */
    @Override
    public String toString() {
        return name + " as " + role.value();
    }
}
