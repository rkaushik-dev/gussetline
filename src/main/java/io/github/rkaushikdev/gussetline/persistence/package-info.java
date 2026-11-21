/**
 * Where the desk keeps what it knows: the schema's conventions, the engine that opens a database and
 * migrates it, the mapping between the domain and the rows, and the units of work and repositories
 * everything above reads and writes through. Nothing above this layer knows what database it is.
 *
 * <p>{@link io.github.rkaushikdev.gussetline.persistence.Database} opens {@link
 * io.github.rkaushikdev.gussetline.persistence.UnitOfWork}s: one entity manager and one transaction
 * each, rolled back on close unless committed, with every repository sharing them. The repositories
 * are the only place a query is written. {@link io.github.rkaushikdev.gussetline.persistence.Book}
 * reads the desk's open recommendations and {@link
 * io.github.rkaushikdev.gussetline.persistence.Snapshot} everything a price reads, each in one place
 * so the callers that need them cannot drift apart.
 *
 * <p>{@link io.github.rkaushikdev.gussetline.persistence.Base} holds the conventions every table
 * follows: constraint names made from the table and its columns, amounts kept as text ({@link
 * io.github.rkaushikdev.gussetline.persistence.Amount}) and times kept in UTC without a zone ({@link
 * io.github.rkaushikdev.gussetline.persistence.Stamp}). {@link
 * io.github.rkaushikdev.gussetline.persistence.Engine} opens the database a {@link
 * io.github.rkaushikdev.gussetline.config.Settings} names and builds or takes out its schema. {@link
 * io.github.rkaushikdev.gussetline.persistence.Mappers} and {@link
 * io.github.rkaushikdev.gussetline.persistence.Provisions} turn domain objects into rows and back,
 * both directions side by side, and {@link io.github.rkaushikdev.gussetline.persistence.DeskMap} does
 * the same for the desk's tickets, watches and recommendations. The rows themselves are in {@code
 * persistence.models}.
 */
package io.github.rkaushikdev.gussetline.persistence;
