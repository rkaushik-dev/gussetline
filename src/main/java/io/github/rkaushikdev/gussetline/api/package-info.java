/**
 * The HTTP surface the desk's own screens talk to.
 *
 * <p>{@link io.github.rkaushikdev.gussetline.api.GussetlineApplication#run} builds the service around
 * one settings object, one database and, when the desk issues keys, one {@link
 * io.github.rkaushikdev.gussetline.api.KeyRing}. The routes live in {@code api.routers}; what they may
 * ask for is {@link io.github.rkaushikdev.gussetline.api.Deps}, who may call them is {@link
 * io.github.rkaushikdev.gussetline.api.Requires}, and every failure that leaves the service is shaped
 * by {@link io.github.rkaushikdev.gussetline.api.Errors}.
 */
package io.github.rkaushikdev.gussetline.api;
