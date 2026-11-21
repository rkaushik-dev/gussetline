/**
 * Where the desk keeps what it knows: the schema's conventions, the engine that opens a database and
 * migrates it, and the mapping between the domain and the rows.
 *
 * <p>{@link io.github.rkaushikdev.gussetline.persistence.Base} holds the conventions every table
 * follows: constraint names made from the table and its columns, amounts kept as text ({@link
 * io.github.rkaushikdev.gussetline.persistence.Amount}) and times kept in UTC without a zone ({@link
 * io.github.rkaushikdev.gussetline.persistence.Stamp}). {@link
 * io.github.rkaushikdev.gussetline.persistence.Engine} opens the database a {@link
 * io.github.rkaushikdev.gussetline.config.Settings} names and builds or takes out its schema. {@link
 * io.github.rkaushikdev.gussetline.persistence.Mappers} and {@link
 * io.github.rkaushikdev.gussetline.persistence.Provisions} turn domain objects into rows and back,
 * both directions side by side. The rows themselves are in {@code persistence.models}.
 */
package io.github.rkaushikdev.gussetline.persistence;
