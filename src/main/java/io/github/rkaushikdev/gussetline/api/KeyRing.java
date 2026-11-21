package io.github.rkaushikdev.gussetline.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every key the service will accept.
 *
 * <p>A ring that is switched off lets everything through, exactly as a service with no ring at all
 * does, which is what a test and a laptop want. Requests read the ring as they arrive, so a key issued
 * or a ring switched off while the service runs counts from the next request on.
 */
public final class KeyRing {

    private final List<ApiKey> keys = new CopyOnWriteArrayList<>();
    private volatile boolean enabled;

    /** An empty ring, switched on: it refuses every key until one is issued. */
    public KeyRing() {
        this(List.of(), true);
    }

    /** A ring holding the keys given, switched on or off. */
    public KeyRing(List<ApiKey> keys, boolean enabled) {
        this.keys.addAll(keys);
        this.enabled = enabled;
    }

    /** {@link #issue(String, String, Role)} for a reader. */
    public ApiKey issue(String name, String key) {
        return issue(name, key, Role.READER);
    }

    /** Take a key into the ring by its hash, and hand back what was stored. */
    public ApiKey issue(String name, String key, Role role) {
        ApiKey made = new ApiKey(name, Security.fingerprint(key), role);
        keys.add(made);
        return made;
    }

    /**
     * The key that matches, checking every one of them.
     *
     * <p>Every key is compared even after a match, so the time taken does not depend on where in the
     * ring the key sits.
     */
    public Optional<ApiKey> find(String presented) {
        ApiKey found = null;
        for (ApiKey key : keys) {
            if (key.matches(presented)) {
                found = key;
            }
        }
        return Optional.ofNullable(found);
    }

    /** The keys held, in the order they were issued. */
    public List<ApiKey> keys() {
        return List.copyOf(keys);
    }

    /** Whether the ring is checked at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Switch the ring on, or off so that it lets everything through. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** How many keys the ring holds. */
    public int size() {
        return keys.size();
    }
}
