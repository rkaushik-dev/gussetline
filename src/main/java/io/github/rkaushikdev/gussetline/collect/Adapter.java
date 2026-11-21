package io.github.rkaushikdev.gussetline.collect;

import java.time.LocalDateTime;

/**
 * What every supplier reader is: the bytes of a payload and the moment they arrived, in; a {@link
 * Harvest} out.
 */
@FunctionalInterface
public interface Adapter {

    /**
     * Read a payload.
     *
     * @param payload the payload exactly as the supplier sent it
     * @param observedAt when it arrived, which every offer and quote read from it carries
     * @throws io.github.rkaushikdev.gussetline.GussetlineError when the payload cannot be read at all;
     *     a part of it that cannot be read is a problem on the harvest instead
     */
    Harvest parse(byte[] payload, LocalDateTime observedAt);
}
