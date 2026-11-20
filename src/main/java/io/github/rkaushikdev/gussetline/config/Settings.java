package io.github.rkaushikdev.gussetline.config;

import io.github.rkaushikdev.gussetline.ConfigError;
import io.github.rkaushikdev.gussetline.Details;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Every knob the desk can change without a deploy, with the values this run will use.
 *
 * <p>Settings are read from the environment once, at start up, and never again. A value that can
 * change under a running collector produces a run whose first half used one budget and whose
 * second half used another, and nothing in the report says so.
 *
 * <p>Every setting has a default that works. A desk that has just been cloned runs the whole thing
 * against a file database with no environment at all, which is what makes the end to end test a
 * real test rather than a mock. The defaults are the {@code DEFAULT_} constants, and {@link
 * #Settings()} is all of them together.
 *
 * <p>Two settings are equal when every value is, amounts compared by value whatever their scale:
 * a credit discount of {@code 0.8} is the same setting as {@code 0.80}.
 *
 * @param databaseUrl the JDBC URL of the database
 * @param archiveRoot where raw supplier payloads are kept
 * @param sellingCurrency what the desk quotes in, three letters
 * @param countryOfSale the country the desk sells in, as its two letter code
 * @param dailyQuota calls per supplier per day; never negative
 * @param ratePerSecond calls per supplier per second; positive
 * @param burst how many calls may go at once before the rate applies; at least one
 * @param requestTimeout seconds before a supplier call is given up on
 * @param retryAttempts how many times a call is tried, the first included; at least one
 * @param breakerThreshold failures in a row before a supplier's breaker opens
 * @param breakerCooldown seconds an open breaker waits before it lets one call through
 * @param materialDifference the smallest difference between two totals that counts as a finding
 * @param watchThreshold what a fall in the fare has to beat when no rule gives a change fee
 * @param creditDiscount what a unit of carrier credit is worth against a unit of cash, between
 *     nothing and one
 * @param userAgent what the collectors call themselves to a supplier
 * @param honourRobots whether a supplier's robots file is obeyed
 * @param dryRun whether a collection only shows what it would poll, sending nothing
 */
public record Settings(
        String databaseUrl,
        Path archiveRoot,
        String sellingCurrency,
        String countryOfSale,
        int dailyQuota,
        double ratePerSecond,
        int burst,
        double requestTimeout,
        int retryAttempts,
        int breakerThreshold,
        double breakerCooldown,
        BigDecimal materialDifference,
        BigDecimal watchThreshold,
        BigDecimal creditDiscount,
        String userAgent,
        boolean honourRobots,
        boolean dryRun) {

    /** What every variable the settings read starts with. */
    public static final String PREFIX = "GUSSETLINE_";

    /**
     * Every setting the environment can carry, unprefixed, in the order the settings command prints
     * them.
     *
     * <p>Written out rather than derived from the components, because the README lists these by
     * name and a test walks both lists: a setting renamed in one place and not the other is then a
     * named failure rather than a knob that quietly stops working.
     */
    public static final List<String> ENVIRONMENT_NAMES = List.of(
            "DATABASE_URL",
            "ARCHIVE_ROOT",
            "SELLING_CURRENCY",
            "COUNTRY_OF_SALE",
            "DAILY_QUOTA",
            "RATE_PER_SECOND",
            "BURST",
            "REQUEST_TIMEOUT",
            "RETRY_ATTEMPTS",
            "BREAKER_THRESHOLD",
            "BREAKER_COOLDOWN",
            "MATERIAL_DIFFERENCE",
            "WATCH_THRESHOLD",
            "CREDIT_DISCOUNT",
            "USER_AGENT",
            "HONOUR_ROBOTS",
            "DRY_RUN");

    /** A file database under {@code var/} beside the working directory. */
    public static final String DEFAULT_DATABASE_URL = "jdbc:h2:file:./var/gussetline";

    public static final Path DEFAULT_ARCHIVE_ROOT = Path.of("var/raw");
    public static final String DEFAULT_SELLING_CURRENCY = "EUR";
    public static final String DEFAULT_COUNTRY_OF_SALE = "DE";
    public static final int DEFAULT_DAILY_QUOTA = 1000;
    public static final double DEFAULT_RATE_PER_SECOND = 1.0;
    public static final int DEFAULT_BURST = 5;
    public static final double DEFAULT_REQUEST_TIMEOUT = 10.0;
    public static final int DEFAULT_RETRY_ATTEMPTS = 3;
    public static final int DEFAULT_BREAKER_THRESHOLD = 5;
    public static final double DEFAULT_BREAKER_COOLDOWN = 60.0;
    public static final BigDecimal DEFAULT_MATERIAL_DIFFERENCE = new BigDecimal("1.00");
    public static final BigDecimal DEFAULT_WATCH_THRESHOLD = new BigDecimal("50.00");
    public static final BigDecimal DEFAULT_CREDIT_DISCOUNT = new BigDecimal("0.80");
    public static final String DEFAULT_USER_AGENT = "gussetline/1.0";
    public static final boolean DEFAULT_HONOUR_ROBOTS = true;
    public static final boolean DEFAULT_DRY_RUN = false;

    private static final String IN_MEMORY = "jdbc:h2:mem:";
    private static final String H2 = "jdbc:h2:";

    /**
     * Refuses settings that cannot describe a working desk.
     *
     * @throws ConfigError when a quota is negative, a rate is not positive, a burst or the attempts
     *     are below one, a credit is worth less than nothing or more than cash, or the currency is
     *     not three characters
     */
    public Settings {
        Objects.requireNonNull(databaseUrl, "databaseUrl");
        Objects.requireNonNull(archiveRoot, "archiveRoot");
        Objects.requireNonNull(sellingCurrency, "sellingCurrency");
        Objects.requireNonNull(countryOfSale, "countryOfSale");
        Objects.requireNonNull(materialDifference, "materialDifference");
        Objects.requireNonNull(watchThreshold, "watchThreshold");
        Objects.requireNonNull(creditDiscount, "creditDiscount");
        Objects.requireNonNull(userAgent, "userAgent");
        if (dailyQuota < 0) {
            throw new ConfigError("a quota is not negative", Details.of("value", dailyQuota));
        }
        if (ratePerSecond <= 0) {
            throw new ConfigError("a rate is positive", Details.of("value", ratePerSecond));
        }
        if (burst < 1) {
            throw new ConfigError("a burst holds at least one", Details.of("value", burst));
        }
        if (retryAttempts < 1) {
            throw new ConfigError("at least one attempt", Details.of("value", retryAttempts));
        }
        if (creditDiscount.signum() < 0 || creditDiscount.compareTo(BigDecimal.ONE) > 0) {
            throw new ConfigError("a credit is worth between nothing and cash");
        }
        if (sellingCurrency.codePointCount(0, sellingCurrency.length()) != 3) {
            throw new ConfigError("a currency code is three letters", Details.of("value", sellingCurrency));
        }
    }

    /** Every setting at its default. */
    public Settings() {
        this(
                DEFAULT_DATABASE_URL,
                DEFAULT_ARCHIVE_ROOT,
                DEFAULT_SELLING_CURRENCY,
                DEFAULT_COUNTRY_OF_SALE,
                DEFAULT_DAILY_QUOTA,
                DEFAULT_RATE_PER_SECOND,
                DEFAULT_BURST,
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_RETRY_ATTEMPTS,
                DEFAULT_BREAKER_THRESHOLD,
                DEFAULT_BREAKER_COOLDOWN,
                DEFAULT_MATERIAL_DIFFERENCE,
                DEFAULT_WATCH_THRESHOLD,
                DEFAULT_CREDIT_DISCOUNT,
                DEFAULT_USER_AGENT,
                DEFAULT_HONOUR_ROBOTS,
                DEFAULT_DRY_RUN);
    }

    /** Settings built a setting at a time, starting from the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Read the settings from the process environment. Every value has a default that works. */
    public static Settings fromEnv() {
        return fromEnv(System.getenv());
    }

    /**
     * Read the settings from {@code environment}, which stands in for the process environment.
     *
     * <p>The currency and the country of sale are upper cased; everything else is taken as set.
     *
     * @throws ConfigError naming the variable when one is set to something that cannot be read, or
     *     when the values read describe settings that cannot work
     */
    public static Settings fromEnv(Map<String, String> environment) {
        Environment values = new Environment(environment);
        return new Settings(
                values.text("DATABASE_URL", DEFAULT_DATABASE_URL),
                Path.of(values.text("ARCHIVE_ROOT", DEFAULT_ARCHIVE_ROOT.toString())),
                values.text("SELLING_CURRENCY", DEFAULT_SELLING_CURRENCY).toUpperCase(Locale.ROOT),
                values.text("COUNTRY_OF_SALE", DEFAULT_COUNTRY_OF_SALE).toUpperCase(Locale.ROOT),
                values.wholeNumber("DAILY_QUOTA", DEFAULT_DAILY_QUOTA),
                values.number("RATE_PER_SECOND", DEFAULT_RATE_PER_SECOND),
                values.wholeNumber("BURST", DEFAULT_BURST),
                values.number("REQUEST_TIMEOUT", DEFAULT_REQUEST_TIMEOUT),
                values.wholeNumber("RETRY_ATTEMPTS", DEFAULT_RETRY_ATTEMPTS),
                values.wholeNumber("BREAKER_THRESHOLD", DEFAULT_BREAKER_THRESHOLD),
                values.number("BREAKER_COOLDOWN", DEFAULT_BREAKER_COOLDOWN),
                values.amount("MATERIAL_DIFFERENCE", DEFAULT_MATERIAL_DIFFERENCE),
                values.amount("WATCH_THRESHOLD", DEFAULT_WATCH_THRESHOLD),
                values.amount("CREDIT_DISCOUNT", DEFAULT_CREDIT_DISCOUNT),
                values.text("USER_AGENT", DEFAULT_USER_AGENT),
                values.flag("HONOUR_ROBOTS", DEFAULT_HONOUR_ROBOTS),
                values.flag("DRY_RUN", DEFAULT_DRY_RUN));
    }

    /** What a caller may set, fully prefixed, in the order of {@link #ENVIRONMENT_NAMES}. */
    public static List<String> environmentNames() {
        return ENVIRONMENT_NAMES.stream().map(name -> PREFIX + name).toList();
    }

    /** {@link #whatIsSet(Map)} for the process environment. */
    public static Map<String, String> whatIsSet() {
        return whatIsSet(System.getenv());
    }

    /**
     * Which of the settings {@code environment} actually has, and to what, in the order of {@link
     * #environmentNames()}.
     *
     * <p>For the settings command, which is the first thing anybody checks when a run does something
     * surprising. A variable that is not set does not appear: the point is to see what was chosen,
     * and a screen of defaults buries it.
     */
    public static Map<String, String> whatIsSet(Map<String, String> environment) {
        Map<String, String> chosen = new LinkedHashMap<>();
        for (String name : environmentNames()) {
            String value = environment.get(name);
            if (value != null) {
                chosen.put(name, value);
            }
        }
        return Collections.unmodifiableMap(chosen);
    }

    /** Whether the database is H2, in this process or in a file beside it. */
    public boolean isH2() {
        return databaseUrl.startsWith(H2);
    }

    /** Whether the database lives in memory and goes when the process does. */
    public boolean inMemory() {
        return databaseUrl.startsWith(IN_MEMORY);
    }

    /** A copy pointed at another database, for a test or a dry run. */
    public Settings withDatabase(String url) {
        return new Settings(
                url,
                archiveRoot,
                sellingCurrency,
                countryOfSale,
                dailyQuota,
                ratePerSecond,
                burst,
                requestTimeout,
                retryAttempts,
                breakerThreshold,
                breakerCooldown,
                materialDifference,
                watchThreshold,
                creditDiscount,
                userAgent,
                honourRobots,
                dryRun);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Settings that
                && databaseUrl.equals(that.databaseUrl)
                && archiveRoot.equals(that.archiveRoot)
                && sellingCurrency.equals(that.sellingCurrency)
                && countryOfSale.equals(that.countryOfSale)
                && dailyQuota == that.dailyQuota
                && Double.compare(ratePerSecond, that.ratePerSecond) == 0
                && burst == that.burst
                && Double.compare(requestTimeout, that.requestTimeout) == 0
                && retryAttempts == that.retryAttempts
                && breakerThreshold == that.breakerThreshold
                && Double.compare(breakerCooldown, that.breakerCooldown) == 0
                && materialDifference.compareTo(that.materialDifference) == 0
                && watchThreshold.compareTo(that.watchThreshold) == 0
                && creditDiscount.compareTo(that.creditDiscount) == 0
                && userAgent.equals(that.userAgent)
                && honourRobots == that.honourRobots
                && dryRun == that.dryRun;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                databaseUrl,
                archiveRoot,
                sellingCurrency,
                countryOfSale,
                dailyQuota,
                ratePerSecond,
                burst,
                requestTimeout,
                retryAttempts,
                breakerThreshold,
                breakerCooldown,
                materialDifference.stripTrailingZeros(),
                watchThreshold.stripTrailingZeros(),
                creditDiscount.stripTrailingZeros(),
                userAgent,
                honourRobots,
                dryRun);
    }

    /**
     * The settings in one line: where the database is, as {@code memory} or the last part of its
     * URL, and what the desk sells in: {@code gussetline against memory, selling in EUR}.
     */
    @Override
    public String toString() {
        String where = inMemory() ? "memory" : databaseUrl.substring(databaseUrl.lastIndexOf('/') + 1);
        return "gussetline against " + where + ", selling in " + sellingCurrency;
    }

    /**
     * Settings named one at a time, each starting at its default. {@link #build()} checks them
     * together, the way the constructor does.
     */
    public static final class Builder {

        private String databaseUrl = DEFAULT_DATABASE_URL;
        private Path archiveRoot = DEFAULT_ARCHIVE_ROOT;
        private String sellingCurrency = DEFAULT_SELLING_CURRENCY;
        private String countryOfSale = DEFAULT_COUNTRY_OF_SALE;
        private int dailyQuota = DEFAULT_DAILY_QUOTA;
        private double ratePerSecond = DEFAULT_RATE_PER_SECOND;
        private int burst = DEFAULT_BURST;
        private double requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private int retryAttempts = DEFAULT_RETRY_ATTEMPTS;
        private int breakerThreshold = DEFAULT_BREAKER_THRESHOLD;
        private double breakerCooldown = DEFAULT_BREAKER_COOLDOWN;
        private BigDecimal materialDifference = DEFAULT_MATERIAL_DIFFERENCE;
        private BigDecimal watchThreshold = DEFAULT_WATCH_THRESHOLD;
        private BigDecimal creditDiscount = DEFAULT_CREDIT_DISCOUNT;
        private String userAgent = DEFAULT_USER_AGENT;
        private boolean honourRobots = DEFAULT_HONOUR_ROBOTS;
        private boolean dryRun = DEFAULT_DRY_RUN;

        private Builder() {}

        public Builder databaseUrl(String value) {
            databaseUrl = value;
            return this;
        }

        public Builder archiveRoot(Path value) {
            archiveRoot = value;
            return this;
        }

        public Builder sellingCurrency(String value) {
            sellingCurrency = value;
            return this;
        }

        public Builder countryOfSale(String value) {
            countryOfSale = value;
            return this;
        }

        public Builder dailyQuota(int value) {
            dailyQuota = value;
            return this;
        }

        public Builder ratePerSecond(double value) {
            ratePerSecond = value;
            return this;
        }

        public Builder burst(int value) {
            burst = value;
            return this;
        }

        public Builder requestTimeout(double value) {
            requestTimeout = value;
            return this;
        }

        public Builder retryAttempts(int value) {
            retryAttempts = value;
            return this;
        }

        public Builder breakerThreshold(int value) {
            breakerThreshold = value;
            return this;
        }

        public Builder breakerCooldown(double value) {
            breakerCooldown = value;
            return this;
        }

        public Builder materialDifference(BigDecimal value) {
            materialDifference = value;
            return this;
        }

        public Builder watchThreshold(BigDecimal value) {
            watchThreshold = value;
            return this;
        }

        public Builder creditDiscount(BigDecimal value) {
            creditDiscount = value;
            return this;
        }

        public Builder userAgent(String value) {
            userAgent = value;
            return this;
        }

        public Builder honourRobots(boolean value) {
            honourRobots = value;
            return this;
        }

        public Builder dryRun(boolean value) {
            dryRun = value;
            return this;
        }

        /**
         * The settings named so far, the rest at their defaults.
         *
         * @throws ConfigError when they cannot describe a working desk
         */
        public Settings build() {
            return new Settings(
                    databaseUrl,
                    archiveRoot,
                    sellingCurrency,
                    countryOfSale,
                    dailyQuota,
                    ratePerSecond,
                    burst,
                    requestTimeout,
                    retryAttempts,
                    breakerThreshold,
                    breakerCooldown,
                    materialDifference,
                    watchThreshold,
                    creditDiscount,
                    userAgent,
                    honourRobots,
                    dryRun);
        }
    }
}
