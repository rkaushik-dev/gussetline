package io.github.rkaushikdev.gussetline.api.routers;

import io.github.rkaushikdev.gussetline.api.Deps;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness, readiness and fitness, which are three different questions.
 *
 * <p>Liveness says the process is up. Readiness says it can reach the database, which is what a load
 * balancer should be asking, and answering the first when the second was meant is how a pool of
 * processes with no database behind them stays in rotation.
 *
 * <p>Fitness is the third one and nothing asks it automatically, which is the point. A desk can be up,
 * reachable, and completely useless: no tariff loaded, no rules behind the fares, nothing collected
 * since Tuesday. Every one of those answers readiness with a cheerful yes, because the database is
 * right there and it is empty.
 *
 * <p>So {@code /ready} stays cheap and boolean, because something polls it every few seconds and it
 * must not read a table. {@code /fit} costs several counts and a date comparison, it is for a person
 * or a morning check, and it says which of the things a desk needs are actually there.
 *
 * <p>The counts on readiness were a mistake and they are gone. They read two tables on every poll,
 * which on a busy desk is a query every second for a number nobody was looking at, and they made a
 * broken desk look healthy: two hundred thousand observations is a reassuring number whether or not
 * any of them arrived this month.
 */
@RestController
@Tag(name = "health")
public final class HealthRoutes {

    /** How long the desk may go without collecting before somebody should look. */
    public static final int QUIET_DAYS = 2;

    /** When each run started, the latest first. */
    private static final String LATEST_RUN =
            "select r.startedAt from RunRow r where r.startedAt is not null order by r.startedAt desc";

    /** When each observation was last seen, the latest first. */
    private static final String LAST_SEEN =
            "select o.lastSeen from ObservationRow o where o.lastSeen is not null order by o.lastSeen desc";

    private final Deps deps;

    public HealthRoutes(Deps deps) {
        this.deps = deps;
    }

    /**
     * One thing a desk needs, and whether it has it.
     *
     * @param ok whether the desk has it
     * @param detail what there is, or the command that fixes it when there is nothing
     */
    public record Check(boolean ok, String detail) {

        public Check {
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** Up. Says nothing about whether anything else works. */
    @GetMapping("/health")
    @Operation(summary = "Health", description = "Up. Says nothing about whether anything else works.")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }

    /** Up and able to reach storage. Cheap, because something polls it. */
    @GetMapping("/ready")
    @Operation(summary = "Ready", description = "Up and able to reach storage. Cheap, because something polls it.")
    public ResponseEntity<Map<String, String>> ready() {
        try (Connection connection = deps.database().engine().dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("select 1");
        } catch (SQLException | RuntimeException unreachable) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("status", "not ready");
            body.put("reason", unreachable.getClass().getSimpleName());
            return ResponseEntity.status(503).body(body);
        }
        return ResponseEntity.ok(Map.of("status", "ready"));
    }

    /**
     * Whether this desk can actually do its job, and what is missing if not.
     *
     * <p>Answers 503 when something a desk cannot work without is absent, so a morning check can be one
     * request. Every finding names the command that fixes it, because the person reading this at seven
     * is not the person who wrote it.
     */
    @GetMapping("/fit")
    @Operation(
            summary = "Fit",
            description = "Whether this desk can actually do its job, and what is missing if not. Answers 503"
                    + " when something a desk cannot work without is absent, so a morning check can be one"
                    + " request.")
    public ResponseEntity<Map<String, Object>> fit() {
        Map<String, Check> found = deps.unit(HealthRoutes::checks);
        List<String> missing = new ArrayList<>();
        Map<String, Object> answers = new LinkedHashMap<>();
        found.forEach((name, check) -> {
            if (!check.ok()) {
                missing.add(name);
            }
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("ok", check.ok());
            answer.put("detail", check.detail());
            answers.put(name, answer);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", missing.isEmpty() ? "fit" : "unfit");
        body.put("currency", deps.settings().sellingCurrency());
        body.put("missing", missing);
        body.put("checks", answers);
        return ResponseEntity.status(missing.isEmpty() ? 200 : 503).body(body);
    }

    /**
     * What a desk needs, each with what to do about it.
     *
     * <p>Public, because the command line asks the same questions and two copies of this list would
     * answer differently within a month.
     *
     * <p>Ordered the way somebody would fix them: without fares nothing prices, and a rule with no fare
     * pointing at it is harmless, so the tariff comes first.
     */
    public static Map<String, Check> checks(UnitOfWork work) {
        long fares = work.tariff().countFares();
        long rules = work.rules().count();
        long joins = work.combinability().count();
        Optional<LocalDateTime> latest = latest(work, LATEST_RUN);
        Optional<LocalDateTime> lastSeen = latest(work, LAST_SEEN);

        Map<String, Check> checks = new LinkedHashMap<>();
        checks.put(
                "tariff",
                new Check(fares > 0, fares > 0 ? fares + " fares loaded" : "nothing to price against, run load fares"));
        checks.put(
                "rules",
                new Check(rules > 0, rules > 0 ? rules + " rules loaded" : "no rule permits a change, run load rules"));
        checks.put(
                "combinability",
                new Check(
                        joins > 0,
                        joins > 0 ? joins + " records" : "every round trip refuses, run load combinability"));
        checks.put("collecting", recently(latest, "no run recorded, run collect run"));
        checks.put("observations", recently(lastSeen, "nothing collected, run collect run"));
        return Collections.unmodifiableMap(checks);
    }

    /** {@link #quietBefore(LocalDate, int)} over {@link #QUIET_DAYS}. */
    public static LocalDate quietBefore(LocalDate today) {
        return quietBefore(today, QUIET_DAYS);
    }

    /** The day a desk has to have collected since. For the morning check. */
    public static LocalDate quietBefore(LocalDate today, int days) {
        return today.minusDays(days);
    }

    /** The first stamp a query of stamps, latest first, hands back, or nothing when it hands back none. */
    private static Optional<LocalDateTime> latest(UnitOfWork work, String latestFirst) {
        return work.session()
                .createQuery(latestFirst, LocalDateTime.class)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /**
     * Whether something happened recently enough to count as still happening.
     *
     * <p>A desk that last collected in March is not collecting, however many rows it holds. The count
     * is the reassuring number and the date is the true one.
     */
    private static Check recently(Optional<LocalDateTime> when, String missing) {
        if (when.isEmpty()) {
            return new Check(false, missing);
        }
        LocalDate day = when.get().toLocalDate();
        long quiet = ChronoUnit.DAYS.between(day, LocalDate.now());
        if (quiet > QUIET_DAYS) {
            return new Check(false, "last was " + day + ", " + quiet + " days ago");
        }
        return new Check(true, "last was " + day);
    }
}
