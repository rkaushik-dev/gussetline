package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.persistence.models.RuleRow;
import io.github.rkaushikdev.gussetline.rules.Provision;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.rules.RuleStore;
import io.github.rkaushikdev.gussetline.util.Text;
import jakarta.persistence.EntityManager;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Rules and the provisions inside them, replaced whole or not at all.
 *
 * <p>A rule is refiled rather than amended: the carrier sends the categories it now has, and the ones
 * it no longer sends are gone. So saving one deletes every provision under it first. Merging instead
 * would leave a category 6 the carrier withdrew last month sitting under a rule that no longer has
 * one, and a minimum stay nobody filed is the hardest kind of wrong answer to find, because the rule
 * looks right in every other respect.
 *
 * <p>A provision with no codec is not stored and is counted. That is a real limitation and it is
 * reported rather than swallowed, because a rule stored without its category 15 permits a sale the
 * carrier restricted.
 *
 * <p>A rule is always read with its provisions, in the order they were filed.
 */
public final class RuleRepository {

    /** The tariff a rule is filed in when nobody says otherwise. */
    public static final String DEFAULT_TARIFF = "1";

    private static final String WITH_PROVISIONS = "select r from RuleRow r left join fetch r.provisions";

    private final EntityManager session;

    /** The rules, read and written through {@code session}. */
    public RuleRepository(EntityManager session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * What writing one rule did.
     *
     * @param row the row the rule is kept in
     * @param dropped how many of its provisions could not be stored
     */
    public record Saved(RuleRow row, int dropped) {

        public Saved {
            Objects.requireNonNull(row, "row");
        }
    }

    /**
     * What writing a whole file of rules did.
     *
     * @param rules how many rules were written
     * @param dropped how many provisions across them could not be stored
     */
    public record Filed(int rules, int dropped) {}

    /** The entity manager this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** {@link #save(RuleSet, Optional)} for a rule filed under no load. */
    public Saved save(RuleSet rule) {
        return save(rule, Optional.empty());
    }

    /** {@link #save(RuleSet, Optional)} for a rule filed under a load. */
    public Saved save(RuleSet rule, int loadId) {
        return save(rule, Optional.of(loadId));
    }

    /**
     * Write a rule whole: a rule already held under the same carrier, number and tariff loses every
     * provision it had and takes this one's, and is filed under the load given, or under none.
     *
     * @param loadId the load the rule came in on, when there is one
     * @return the row and how many provisions were dropped for want of a codec
     */
    public Saved save(RuleSet rule, Optional<Integer> loadId) {
        Optional<RuleRow> held = rowFor(rule.carrier(), rule.number(), rule.tariff());
        RuleRow row;
        if (held.isEmpty()) {
            row = new RuleRow();
            row.setCarrier(rule.carrier());
            row.setNumber(rule.number());
            row.setTariffNumber(rule.tariff());
            row.setLoadId(loadId.orElse(null));
            session.persist(row);
        } else {
            row = held.get();
            row.setProvisions(List.of());
            row.setLoadId(loadId.orElse(null));
        }

        int dropped = 0;
        for (Provision provision : rule.provisions()) {
            if (!Provisions.storable(provision)) {
                dropped++;
                continue;
            }
            row.addProvision(Provisions.provisionToRow(provision));
        }
        session.flush();
        return new Saved(row, dropped);
    }

    /** Write a whole file, filed under no load. */
    public Filed saveAll(List<RuleSet> rules) {
        return saveAll(rules, Optional.empty());
    }

    /**
     * Write a whole file, each rule whole.
     *
     * @param loadId the load the file came in on, when there is one
     * @return how many rules were written and how many provisions across them were dropped
     */
    public Filed saveAll(List<RuleSet> rules, Optional<Integer> loadId) {
        int dropped = 0;
        for (RuleSet rule : rules) {
            dropped += save(rule, loadId).dropped();
        }
        return new Filed(rules.size(), dropped);
    }

    /** {@link #get(String, String, String)} in the {@link #DEFAULT_TARIFF}. */
    public Optional<RuleSet> get(String carrier, String number) {
        return get(carrier, number, DEFAULT_TARIFF);
    }

    /** One rule with every provision it holds, or nothing when it is not filed. */
    public Optional<RuleSet> get(String carrier, String number, String tariff) {
        return rowFor(carrier, number, tariff).map(RuleRepository::ruleFrom);
    }

    /** {@link #delete(String, String, String)} in the {@link #DEFAULT_TARIFF}. */
    public boolean delete(String carrier, String number) {
        return delete(carrier, number, DEFAULT_TARIFF);
    }

    /** Drop a rule and every provision under it. Says whether it was there. */
    public boolean delete(String carrier, String number, String tariff) {
        Optional<RuleRow> row = rowFor(carrier, number, tariff);
        row.ifPresent(session::remove);
        return row.isPresent();
    }

    /**
     * Every rule filed for a set of carriers, as the store the engine reads. No carriers at all is an
     * empty store, and asks the database nothing.
     *
     * <p>The provisions come in the same query. A price that touches four rules and loads each one's
     * provisions separately turns one lookup into nine, and the ladder does that for every fare
     * component it considers.
     */
    public RuleStore storeFor(List<String> carriers) {
        RuleStore store = new RuleStore();
        if (carriers.isEmpty()) {
            return store;
        }
        List<String> codes =
                carriers.stream().map(code -> code.toUpperCase(Locale.ROOT)).toList();
        session.createQuery(WITH_PROVISIONS + " where r.carrier in ?1", RuleRow.class)
                .setParameter(1, codes)
                .getResultList()
                .forEach(row -> store.add(ruleFrom(row)));
        return store;
    }

    /** How many rules are held, over every carrier. */
    public long count() {
        return Rows.count(session, RuleRow.class);
    }

    /** How many provisions of each category are stored, for a load report, by category. */
    public Map<Integer, Long> categoriesHeld() {
        Map<Integer, Long> held = new LinkedHashMap<>();
        session.createQuery(
                        "select p.category, count(p) from ProvisionRow p group by p.category order by p.category",
                        Object[].class)
                .getResultList()
                .forEach(row -> held.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue()));
        return Collections.unmodifiableMap(held);
    }

    private Optional<RuleRow> rowFor(String carrier, String number, String tariff) {
        return Rows.oneOrNone(session.createQuery(
                        WITH_PROVISIONS + " where r.carrier = ?1 and r.number = ?2 and r.tariffNumber = ?3",
                        RuleRow.class)
                .setParameter(1, carrier.toUpperCase(Locale.ROOT))
                .setParameter(2, Text.strip(number))
                .setParameter(3, Text.strip(tariff)));
    }

    private static RuleSet ruleFrom(RuleRow row) {
        List<Provision> provisions =
                row.getProvisions().stream().map(Provisions::rowToProvision).toList();
        return new RuleSet(row.getCarrier(), row.getNumber(), provisions, row.getTariffNumber());
    }
}
