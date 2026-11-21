package io.github.rkaushikdev.gussetline.api;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.util.Text;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * API keys and what each one is allowed to do.
 *
 * <p>Keys are compared in constant time and stored as hashes; see {@link ApiKey}. What a route needs
 * is declared with {@link Requires}, and {@link #caller} and {@link #require} are the two checks every
 * request to such a route goes through, in that order.
 */
public final class Security {

    /** The request header a key is presented in. */
    public static final String HEADER = "X-Api-Key";

    /** Who a request is when the service checks no keys: somebody allowed to do everything. */
    static final ApiKey ANONYMOUS = new ApiKey("anonymous", "", Role.OPERATOR);

    private Security() {}

    /** The stored form of a key, the hexadecimal SHA-256 of it without surrounding white space. Never the key itself. */
    public static String fingerprint(String key) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(Text.strip(key).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("every Java runtime has SHA-256", missing);
        }
    }

    /**
     * The key behind a request, or a refusal.
     *
     * @param ring the keys the service accepts; none, or a ring switched off, lets everybody in as
     *     {@code anonymous}, with every role
     * @param presented what the request sent in {@link #HEADER}, empty when it sent nothing
     * @throws Unauthorised when no key was presented, naming the {@code header}, or the key is not one
     *     the ring holds
     */
    public static ApiKey caller(Optional<KeyRing> ring, String presented) {
        if (ring.isEmpty() || !ring.get().isEnabled()) {
            return ANONYMOUS;
        }
        if (presented.isEmpty()) {
            throw new Unauthorised("no api key was presented", Details.of("header", HEADER));
        }
        return ring.get().find(presented).orElseThrow(() -> new Unauthorised("that api key is not one of ours"));
    }

    /**
     * The key again, when its role covers the one asked for.
     *
     * @throws Forbidden naming the {@code key}, the role it {@code has} and the role it {@code needs}
     */
    public static ApiKey require(ApiKey key, Role role) {
        if (!key.role().covers(role)) {
            throw new Forbidden(
                    "this key does not cover that",
                    Details.of("key", key.name(), "has", key.role().value(), "needs", role.value()));
        }
        return key;
    }
}
