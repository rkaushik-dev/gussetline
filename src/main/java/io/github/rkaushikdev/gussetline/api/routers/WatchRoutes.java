package io.github.rkaushikdev.gussetline.api.routers;

import io.github.rkaushikdev.gussetline.ConflictError;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.api.Deps;
import io.github.rkaushikdev.gussetline.api.Requires;
import io.github.rkaushikdev.gussetline.api.Role;
import io.github.rkaushikdev.gussetline.api.Schemas.DecisionIn;
import io.github.rkaushikdev.gussetline.api.Schemas.MoneyOut;
import io.github.rkaushikdev.gussetline.api.Schemas.RecommendationOut;
import io.github.rkaushikdev.gussetline.api.Schemas.WatchOut;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Watches and the recommendations they raise.
 *
 * <p>The only writes in the whole surface are here, and they are both decisions a person makes: take a
 * recommendation or kill it. Neither changes a number, they change a state and record who did it,
 * which is the shape every write in this service has.
 */
@RestController
@RequestMapping("/watches")
@Requires(Role.READER)
@Tag(name = "watches")
public final class WatchRoutes {

    private final Deps deps;

    public WatchRoutes(Deps deps) {
        this.deps = deps;
    }

    /** One watch as it crosses the wire. Reads the ticket, so the row's unit must still be open. */
    public static WatchOut watchOut(WatchRow row) {
        return new WatchOut(
                row.getSeriesKey(),
                row.getTicket().getNumber(),
                row.getOrigin(),
                row.getDestination(),
                row.getDeparture(),
                row.getCabin(),
                money(row.getThresholdAmount(), row.getThresholdCurrency()),
                row.isActive(),
                row.getRetiredReason(),
                row.getChecks(),
                row.getLastChecked());
    }

    /**
     * One recommendation as it crosses the wire. Reads the watch and its ticket, so the row's unit
     * must still be open.
     */
    public static RecommendationOut recommendationOut(RecommendationRow row) {
        return new RecommendationOut(
                row.getWatch().getTicket().getNumber(),
                row.getWatch().getSeriesKey(),
                row.getRaisedOn(),
                row.getState(),
                money(row.getPaidAmount(), row.getCurrency()),
                money(row.getNewFareAmount(), row.getCurrency()),
                money(row.getPenaltyAmount(), row.getCurrency()),
                money(row.getBenefitAmount(), row.getCurrency()),
                row.getResidual(),
                row.getDecidedOn(),
                row.getDecidedBy());
    }

    /** Every live watch, closest departure first. */
    @GetMapping
    @Operation(summary = "List Watches", description = "Every live watch, closest departure first.")
    public List<WatchOut> listWatches(@RequestParam Optional<LocalDate> on) {
        LocalDate day = on.orElseGet(LocalDate::now);
        return deps.unit(work ->
                work.watches().live(day).stream().map(WatchRoutes::watchOut).toList());
    }

    /** Every watch on one ticket. */
    @GetMapping("/ticket/{number}")
    @Operation(summary = "Watches For Ticket")
    public List<WatchOut> watchesForTicket(@PathVariable String number) {
        return deps.unit(work -> {
            List<WatchRow> found = work.watches().forTicket(number);
            if (found.isEmpty()) {
                throw new NotFoundError("no watches on that ticket", Details.of("number", number));
            }
            return found.stream().map(WatchRoutes::watchOut).toList();
        });
    }

    /** One recommendation, with every number behind it. */
    @GetMapping("/recommendations/{identifier}")
    @Operation(summary = "Read Recommendation")
    public RecommendationOut readRecommendation(@PathVariable long identifier) {
        return deps.unit(work -> recommendationOut(recommendation(work, identifier)));
    }

    /** The desk made the reissue. The only write that ends in a saving. */
    @PostMapping("/recommendations/{identifier}/take")
    @Requires(Role.AGENT)
    @Operation(
            summary = "Take Recommendation",
            description = "The desk made the reissue. The only write that ends in a saving.")
    public RecommendationOut takeRecommendation(
            @PathVariable long identifier, @Valid @RequestBody DecisionIn decision) {
        return deps.unit(work -> settle(work, identifier, "taken", decision));
    }

    /** The desk looked and said no. Recorded, and not a failure. */
    @PostMapping("/recommendations/{identifier}/kill")
    @Requires(Role.AGENT)
    @Operation(
            summary = "Kill Recommendation",
            description = "The desk looked and said no. Recorded, and not a failure.")
    public RecommendationOut killRecommendation(
            @PathVariable long identifier, @Valid @RequestBody DecisionIn decision) {
        return deps.unit(work -> settle(work, identifier, "killed", decision));
    }

    private static RecommendationRow recommendation(UnitOfWork work, long identifier) {
        RecommendationRow row = identifier < Integer.MIN_VALUE || identifier > Integer.MAX_VALUE
                ? null
                : work.session().find(RecommendationRow.class, (int) identifier);
        if (row == null) {
            throw new NotFoundError("no such recommendation", Details.of("id", identifier));
        }
        return row;
    }

    private static RecommendationOut settle(UnitOfWork work, long identifier, String state, DecisionIn decision) {
        RecommendationRow row = recommendation(work, identifier);
        if (!row.getState().equals("open")) {
            throw new ConflictError(
                    "this recommendation has already been settled",
                    Details.of("state", row.getState(), "by", row.getDecidedBy()));
        }
        row.setState(state);
        row.setDecidedOn(LocalDate.now());
        row.setDecidedBy(decision.by());
        row.setNote(decision.note());
        work.commit();
        return recommendationOut(row);
    }

    private static MoneyOut money(BigDecimal amount, String currency) {
        return new MoneyOut(amount.toString(), currency);
    }
}
