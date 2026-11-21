/**
 * Fare rules: the numbered categories a fare's rule files, one class per category, and the framework
 * that runs them.
 *
 * <p>Every published fare points at a rule, and a rule is a numbered set of provisions. Category 5
 * says how far in advance it has to be bought, category 6 how long the passenger has to stay,
 * category 16 what a change costs. The numbers are the tariff's and they are stable, so they are
 * {@link io.github.rkaushikdev.gussetline.rules.Category} and not names invented here.
 *
 * <p>The part that catches people is the third answer. A provision does not pass or fail, it passes,
 * fails, or does not apply, and the difference between failing and not applying is the difference
 * between a fare that may not be sold and a fare with no such condition. A rule set with no category
 * 5 in it has no advance purchase requirement, and treating that as a failure would refuse every
 * unrestricted fare in the tariff.
 *
 * <p>Provisions are evaluated against a {@link io.github.rkaushikdev.gussetline.rules.Context} and
 * never against each other. Where one category genuinely depends on another, the dependency is in
 * the context, which is why the context carries the itinerary rather than a summary of it.
 */
package io.github.rkaushikdev.gussetline.rules;
