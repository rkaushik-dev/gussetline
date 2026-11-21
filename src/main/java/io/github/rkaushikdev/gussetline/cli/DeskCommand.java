package io.github.rkaushikdev.gussetline.cli;

import java.util.concurrent.Callable;

/**
 * One command the desk can be asked to run. It is called once, after the root has read the settings
 * and begun the desk, and it answers the code the run exits with.
 */
interface DeskCommand extends Callable<Integer> {

    /** Do the work and answer the exit code: {@link Desk#OK}, or what went wrong. */
    @Override
    Integer call();
}
