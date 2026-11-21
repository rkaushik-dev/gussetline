/**
 * The routes, one class per screen: health, observations, pricing, the suppliers, watches and the
 * worklist.
 *
 * <p>Every route takes what it needs from {@link io.github.rkaushikdev.gussetline.api.Deps}, declares
 * who may call it with {@link io.github.rkaushikdev.gussetline.api.Requires}, and answers with one of
 * the {@link io.github.rkaushikdev.gussetline.api.Schemas}. None of them catches anything: a refusal
 * leaves as the domain raised it and {@link io.github.rkaushikdev.gussetline.api.Errors} gives it its
 * status.
 */
package io.github.rkaushikdev.gussetline.api.routers;
