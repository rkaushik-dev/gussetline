package io.github.rkaushikdev.gussetline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ErrorsTest {

    @Test
    void messageAloneRendersPlainly() {
        GussetlineError err = new GussetlineError("nothing to price");
        assertThat(err.toString()).isEqualTo("nothing to price");
    }

    @Test
    void detailsRenderSortedAndQuoted() {
        GussetlineError err = new GussetlineError("no fare", Details.of("origin", "FRA", "carrier", "LH"));
        assertThat(err.toString()).isEqualTo("no fare (carrier='LH' origin='FRA')");
    }

    @Test
    void asMapCarriesTheCode() {
        NoFareFound err = new NoFareFound("nothing published", Details.of("market", "FRA-JFK"));
        assertThat(err.asMap())
                .isEqualTo(Map.of(
                        "code",
                        "tariff.no_fare",
                        "message",
                        "nothing published",
                        "details",
                        Map.of("market", "FRA-JFK")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void asMapDetailsAreACopy() {
        NoFareFound err = new NoFareFound("nothing published", Details.of("market", "FRA-JFK"));
        ((Map<String, Object>) err.asMap().get("details")).put("market", "LHR-JFK");
        assertThat(err.details()).isEqualTo(Map.of("market", "FRA-JFK"));
    }

    static Stream<Arguments> codes() {
        return Stream.of(
                Arguments.of((Function<String, GussetlineError>) ConfigError::new, "config.invalid"),
                Arguments.of((Function<String, GussetlineError>) TariffError::new, "tariff.error"),
                Arguments.of((Function<String, GussetlineError>) NoFareFound::new, "tariff.no_fare"),
                Arguments.of((Function<String, GussetlineError>) NoRuleFound::new, "tariff.no_rule"),
                Arguments.of((Function<String, GussetlineError>) ConstructionError::new, "pricing.construction"),
                Arguments.of((Function<String, GussetlineError>) CombinabilityError::new, "pricing.combinability"),
                Arguments.of((Function<String, GussetlineError>) SupplierError::new, "supplier.error"),
                Arguments.of((Function<String, GussetlineError>) SupplierUnavailable::new, "supplier.unavailable"),
                Arguments.of((Function<String, GussetlineError>) SupplierThrottled::new, "supplier.throttled"),
                Arguments.of((Function<String, GussetlineError>) SupplierBlocked::new, "supplier.blocked"),
                Arguments.of((Function<String, GussetlineError>) MalformedPayload::new, "supplier.malformed"),
                Arguments.of((Function<String, GussetlineError>) BudgetExhausted::new, "collector.budget_exhausted"));
    }

    @ParameterizedTest
    @MethodSource("codes")
    void codesAreStable(Function<String, GussetlineError> kind, String code) {
        assertThat(kind.apply("x").code()).isEqualTo(code);
    }

    @Test
    void subclassesGroupTheWayHandlersExpect() {
        assertThat(TariffError.class).isAssignableFrom(NoFareFound.class);
        assertThat(ConstructionError.class).isAssignableFrom(CombinabilityError.class);
        assertThat(SupplierError.class).isAssignableFrom(SupplierThrottled.class);
        assertThat(GussetlineError.class).isAssignableFrom(SupplierError.class);
    }

    @Test
    void aTimeoutIsRetryableAndARefusalIsNot() {
        assertThat(Errors.isRetryable(new SupplierUnavailable("read timeout"))).isTrue();
        assertThat(Errors.isRetryable(new SupplierThrottled("429"))).isTrue();
        assertThat(Errors.isRetryable(new BudgetExhausted("day quota spent"))).isTrue();
        assertThat(Errors.isRetryable(new SupplierBlocked("403 forever"))).isFalse();
        assertThat(Errors.isRetryable(new MalformedPayload("not a record"))).isFalse();
    }

    @Test
    void aBadMarketIsNeverRetryable() {
        assertThat(Errors.isRetryable(new NoFareFound("nothing published"))).isFalse();
        assertThat(Errors.isRetryable(new ConstructionError("no through fare"))).isFalse();
    }

    @Test
    void unknownExceptionsDefaultToRetryableExceptProgrammerErrors() {
        assertThat(Errors.isRetryable(new IOException("connection reset"))).isTrue();
        assertThat(Errors.isRetryable(new IllegalStateException("event loop closed")))
                .isTrue();
        assertThat(Errors.isRetryable(new ClassCastException("String cannot be cast to Integer")))
                .isFalse();
        assertThat(Errors.isRetryable(new NoSuchElementException("carrier"))).isFalse();
    }
}
