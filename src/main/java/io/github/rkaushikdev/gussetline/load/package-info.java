/**
 * Reading filed tariff data into the stores the pricing engine reads.
 *
 * <p>Separate from the collectors on purpose. A collector fetches what a supplier is selling today;
 * a loader reads what a carrier has filed, which arrives as a file, on a schedule, and describes the
 * future as well as the present.
 *
 * <p>Every feed is read and applied in two calls. Reading a file works out all of it without
 * touching a store, so a dry run can count everything and list every bad record; applying puts what
 * was read into a store, and {@code loadInto} does both for the ordinary case, handing back a
 * {@link io.github.rkaushikdev.gussetline.load.Loaded}.
 */
package io.github.rkaushikdev.gussetline.load;
