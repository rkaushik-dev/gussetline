/**
 * The reprice desk: tickets, watches, and what to do about a drop.
 *
 * <p>A {@link io.github.rkaushikdev.gussetline.desk.Ticket} is a plan that has been paid for, read
 * coupon by coupon against today. A {@link io.github.rkaushikdev.gussetline.desk.Watch} keeps looking
 * at one ticket's market and reports a drop only once it has held. {@link
 * io.github.rkaushikdev.gussetline.desk.Reprice} decides what a held drop is actually worth, once the
 * fare's change rules and its fee have had their say. A {@link
 * io.github.rkaushikdev.gussetline.desk.Recommendation} is what the desk then told a customer, {@link
 * io.github.rkaushikdev.gussetline.desk.Worklist} is the order an agent works the open ones in, and
 * {@link io.github.rkaushikdev.gussetline.desk.Session} runs a whole night of it in the order that
 * wastes least.
 */
package io.github.rkaushikdev.gussetline.desk;
