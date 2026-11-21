/**
 * Every table the desk keeps, one entity each, with every constraint and index named as {@link
 * io.github.rkaushikdev.gussetline.persistence.Base} says.
 *
 * <p><b>Reference tables</b> ({@link io.github.rkaushikdev.gussetline.persistence.models.AirportRow},
 * {@link io.github.rkaushikdev.gussetline.persistence.models.CarrierRow}, {@link
 * io.github.rkaushikdev.gussetline.persistence.models.InterlineRow}, {@link
 * io.github.rkaushikdev.gussetline.persistence.models.RateRow}, {@link
 * io.github.rkaushikdev.gussetline.persistence.models.MileageRow}) are the ones the desk edits by
 * hand. They change slowly, they are small enough to load whole, and they are the only tables an
 * analyst is allowed to write to directly, which is why every one of them carries a source column
 * saying where the row came from. A rate keyed in by hand and a rate loaded from the quarterly file
 * are not the same evidence, and an audit that quotes one has to be able to say which.
 *
 * <p><b>Tariff tables</b> are loaded and never edited. A fare filing is replaced whole rather than
 * updated, because the tariff sends a complete file and a partial update leaves rows nobody can
 * account for. So every row carries the load it came in on, and a load is a row of its own with a
 * stamp on it ({@link io.github.rkaushikdev.gussetline.persistence.models.LoadRow}). Retiring a load
 * means deleting the fares that name it, and proving what a ticket was priced against means reading
 * the load that was current on the ticketing date. Updating rows in place works until a supplier
 * disputes a price from four months ago and the desk cannot say what was in the tariff that day.
 *
 * <p><b>Market tables</b> hold what the collectors saw, and the observations are the table that
 * grows. Nine thousand watched itineraries at two polls a week with a dozen offers each is a bit over
 * a million rows a year, which is nothing for a database and everything for a query with no index
 * on it. So the series key is stored on the row rather than derived at read time: it is redundant
 * with the market and the date, and it is the column every report groups by. And the fingerprint is
 * unique per series: the same offer seen twice does not make a second row, it moves the last seen
 * stamp on the first one, which keeps the table at a million rows a year instead of nine.
 *
 * <p><b>Desk tables</b> hold everything that has a person behind it: tickets, the watches on them,
 * the recommendations a watch produced, and what the desk did about each one. These are the only
 * rows edited after they are written, because a recommendation moves through states and a ticket
 * gets reissued. A recommendation carries a copy of the numbers rather than a pointer to the
 * observation it came from. An observation is a reading of a market at a moment and the market
 * moves; a recommendation is a statement the desk made to a customer and it has to still say the
 * same thing in a year when the observation has been rolled off. Losing that distinction means a
 * savings report that changes every time it is run.
 *
 * <p>Rows hold what the columns hold: codes and states as text, amounts as decimals, days as dates
 * and stamps as UTC wall clock times. The mappers turn them into domain objects. A nullable column
 * reads back as an {@link java.util.Optional}; the generated id and the two stamps are null until
 * the row is first written. A child row of a parent with a generated id carries the parent's id as
 * a column of its own, which is the side that is written, and its parent as a read only reference:
 * adding a child through its parent's {@code add} method sets both, and the id column follows the
 * parent when the child is first written.
 */
package io.github.rkaushikdev.gussetline.persistence.models;
