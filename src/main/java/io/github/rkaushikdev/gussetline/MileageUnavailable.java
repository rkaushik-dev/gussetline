package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** No maximum permitted mileage is published for the pair. */
public class MileageUnavailable extends TariffError {

    public MileageUnavailable(String message) {
        super(message);
    }

    public MileageUnavailable(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "tariff.no_mileage";
    }
}
