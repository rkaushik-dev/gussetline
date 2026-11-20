package io.github.rkaushikdev.gussetline.collect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** What a whole run intends to do, across every supplier, in the order it will do it. */
public final class RunPlan {

    private final List<Task> tasks = new ArrayList<>();

    /** A plan with nothing in it yet. */
    public RunPlan() {}

    /** A plan of these tasks, in this order. */
    public RunPlan(List<Task> tasks) {
        tasks.forEach(this::add);
    }

    /** Every task, in plan order. */
    public List<Task> tasks() {
        return List.copyOf(tasks);
    }

    /** Put a task at the end of the plan. */
    public void add(Task task) {
        tasks.add(Objects.requireNonNull(task, "task"));
    }

    /** The tasks for one supplier, in plan order. */
    public List<Task> forSupplier(String name) {
        return tasks.stream().filter(task -> task.supplier().equals(name)).toList();
    }

    /** The keys of every market the plan touches, each once, in alphabetical order. */
    public List<String> markets() {
        return tasks.stream()
                .map(task -> task.market().key())
                .distinct()
                .sorted()
                .toList();
    }

    /** How many tasks the plan holds. */
    public int size() {
        return tasks.size();
    }

    /** {@code 4 tasks over 2 markets}. */
    @Override
    public String toString() {
        return tasks.size() + " tasks over " + markets().size() + " markets";
    }
}
