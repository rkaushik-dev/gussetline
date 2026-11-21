package io.github.rkaushikdev.gussetline.rules;

/**
 * One filed condition inside a rule.
 *
 * <p>Each category the desk reads has its own provision types in this package, from eligibility in
 * category 1 to voluntary changes in category 31. The type is open, because a rule may carry a
 * condition the desk reads without storing, and storage refuses the kinds it has no columns for by
 * name.
 *
 * <p>A provision reads the {@link Context} and nothing else. It never looks at another provision.
 */
public interface Provision {

    /** The category the provision is filed under. */
    Category category();

    /** What the provision decides about the journey: passes, fails, or does not apply. */
    Outcome evaluate(Context context);
}
