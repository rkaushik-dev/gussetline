package io.github.rkaushikdev.gussetline.geography;

/**
 * One airport, and the city it prices as.
 *
 * @param code the IATA airport code
 * @param name the airport's name
 * @param city the metro code it prices as, which is its own code for a standalone airport
 * @param country the country's two letter code
 * @param latitude degrees north
 * @param longitude degrees east
 * @param utcOffsetMinutes the fixed offset from UTC, summer time ignored
 */
public record Airport(
        String code,
        String name,
        String city,
        String country,
        double latitude,
        double longitude,
        int utcOffsetMinutes) {

    /** Whether this airport carries its own city's code, as a standalone airport does. */
    public boolean isMetro() {
        return code.equals(city);
    }
}
