package io.github.rkaushikdev.gussetline.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ConfigTest {

    private static final Pattern DOCUMENTED = Pattern.compile("GUSSETLINE_[A-Z_]+");

    /**
     * The README documents these by name, so a rename has to break here.
     *
     * <p>Both lists are written out by hand and this walks them together. A knob renamed in one place
     * and not the other otherwise stops working with nothing saying so, because an unread
     * environment variable is a default.
     */
    @Test
    void everyNameTheReadmeListsIsOneTheSettingsRead() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        Set<String> documented = new HashSet<>();
        Matcher found = DOCUMENTED.matcher(readme);
        while (found.find()) {
            documented.add(found.group());
        }
        Set<String> known = new HashSet<>(Settings.environmentNames());
        known.add("GUSSETLINE_API_KEYS");

        // A README that names nothing would pass the subset check without reading anything.
        assertThat(documented).contains("GUSSETLINE_DATABASE_URL");
        Set<String> unknown = new HashSet<>(documented);
        unknown.removeAll(known);
        assertThat(unknown).as("named in the README but never read").isEmpty();
    }

    @Test
    void everyNameTheSettingsReadIsOneFromEnvActuallyReads() {
        Set<String> amounts = Set.of("MATERIAL_DIFFERENCE", "WATCH_THRESHOLD", "CREDIT_DISCOUNT");
        for (String name : Settings.ENVIRONMENT_NAMES) {
            Settings made = Settings.fromEnv(Map.of());
            Set<String> components = Arrays.stream(made.getClass().getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toSet());
            assertThat(components.contains(componentFor(name)) || amounts.contains(name))
                    .as(name)
                    .isTrue();
        }
    }

    @Test
    void theNamesAreAllPrefixedAndNoneOfThemRepeats() {
        List<String> names = Settings.environmentNames();
        assertThat(names).allMatch(name -> name.startsWith("GUSSETLINE_"));
        assertThat(new HashSet<>(names)).hasSameSizeAs(names);
    }

    @Test
    void nothingSetReadsBackAsNothingSet() {
        assertThat(Settings.whatIsSet(Map.of())).isEmpty();
    }

    @Test
    void whatIsSetReportsOnlyWhatWasChosen() {
        Map<String, String> found = Settings.whatIsSet(Map.of("GUSSETLINE_DAILY_QUOTA", "40", "SOMETHING_ELSE", "x"));
        assertThat(found).containsExactly(Map.entry("GUSSETLINE_DAILY_QUOTA", "40"));
    }

    /** {@code DAILY_QUOTA} is read into {@code dailyQuota}. */
    private static String componentFor(String name) {
        String[] words = name.toLowerCase(Locale.ROOT).split("_", -1);
        StringBuilder component = new StringBuilder(words[0]);
        for (int index = 1; index < words.length; index++) {
            component.append(Character.toUpperCase(words[index].charAt(0))).append(words[index].substring(1));
        }
        return component.toString();
    }
}
