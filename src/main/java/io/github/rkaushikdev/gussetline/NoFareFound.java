package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** No published fare covers the market, carrier and dates given. */
public class NoFareFound extends TariffError {

    public NoFareFound(String message) {
        super(message);
    }

    public NoFareFound(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "tariff.no_fare";
    }
}
