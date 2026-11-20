package io.github.rkaushikdev.gussetline.carriers;

import static io.github.rkaushikdev.gussetline.carriers.Carriers.CARRIERS;
import static io.github.rkaushikdev.gussetline.carriers.Carriers.allianceOf;
import static io.github.rkaushikdev.gussetline.carriers.Carriers.carrier;
import static io.github.rkaushikdev.gussetline.carriers.Carriers.interlines;
import static io.github.rkaushikdev.gussetline.carriers.Carriers.isKnown;
import static io.github.rkaushikdev.gussetline.carriers.Carriers.lowCostCarriers;
import static io.github.rkaushikdev.gussetline.carriers.Carriers.members;
import static io.github.rkaushikdev.gussetline.carriers.Carriers.normaliseCode;
import static io.github.rkaushikdev.gussetline.carriers.Carriers.sameAlliance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CarriersTest {

    @Test
    void everyCarrierNamesACountryWeHold() {
        assertThat(CARRIERS.values().stream()
                        .map(Carrier::country)
                        .filter(code -> !Geography.COUNTRIES.containsKey(code))
                        .toList())
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lh", " LH ", "LH"})
    void codesNormalise(String code) {
        assertThat(normaliseCode(code)).isEqualTo("LH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"L", "LHX", "L-", "", "l h"})
    void aDesignatorThatIsNotTwoCharactersIsRefused(String code) {
        assertThatThrownBy(() -> normaliseCode(code)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aNumericDesignatorIsStillADesignator() {
        assertThat(normaliseCode("6e")).isEqualTo("6E");
        assertThat(carrier("6E").name()).isEqualTo("IndiGo");
        assertThat(carrier("U2").lowCost()).isTrue();
    }

    @Test
    void isKnownSeparatesABadShapeFromAnUnlistedCarrier() {
        assertThat(isKnown("LH")).isTrue();
        assertThat(isKnown("ZZ")).isFalse();
        assertThat(isKnown("nonsense")).isFalse();
    }

    @Test
    void unknownCarrierNamesWhatWasAskedFor() {
        ValidationError caught = catchThrowableOfType(ValidationError.class, () -> carrier("ZZ"));
        assertThat(caught.details()).containsEntry("code", "ZZ");
    }

    @Test
    void allianceLookup() {
        assertThat(allianceOf("LH")).isSameAs(Alliance.STAR);
        assertThat(allianceOf("BA")).isSameAs(Alliance.ONEWORLD);
        assertThat(allianceOf("AF")).isSameAs(Alliance.SKYTEAM);
        assertThat(allianceOf("EK")).isSameAs(Alliance.NONE);
    }

    @Test
    void isAlignedIsFalseOnlyForTheUnaligned() {
        assertThat(carrier("LH").isAligned()).isTrue();
        assertThat(carrier("EK").isAligned()).isFalse();
    }

    @Test
    void sameAllianceNeedsBothSidesToBeInOne() {
        assertThat(sameAlliance("LH", "UA")).isTrue();
        assertThat(sameAlliance("BA", "QF")).isTrue();
        assertThat(sameAlliance("LH", "BA")).isFalse();
    }

    @Test
    void twoUnalignedCarriersDoNotShareAnAllianceOfNobody() {
        assertThat(sameAlliance("EK", "EY")).isFalse();
        assertThat(sameAlliance("EK", "EK")).isFalse();
    }

    @Test
    void aCarrierAlwaysInterlinesWithItself() {
        assertThat(interlines("LH", "LH")).isTrue();
        assertThat(interlines("EK", "ek")).isTrue();
    }

    @Test
    void allianceMembershipImpliesAnAgreement() {
        assertThat(interlines("LH", "SQ")).isTrue();
        assertThat(interlines("AA", "QR")).isTrue();
    }

    @Test
    void thePaperListCoversPairsNoAllianceDoes() {
        assertThat(interlines("LH", "EK")).isTrue();
        assertThat(interlines("EK", "LH")).isTrue();
        assertThat(interlines("VS", "DL")).isTrue();
    }

    @Test
    void aPairOnNeitherListDoesNotInterline() {
        assertThat(interlines("EK", "EY")).isFalse();
        assertThat(interlines("FR", "LH")).isFalse();
        assertThat(interlines("U2", "BA")).isFalse();
    }

    @Test
    void interlineRefusesAShapeThatIsNotADesignator() {
        assertThatThrownBy(() -> interlines("LHX", "BA")).isInstanceOf(ValidationError.class);
    }

    @Test
    void membersListsAnAllianceSorted() {
        List<String> star = members(Alliance.STAR);
        assertThat(star).isSorted();
        assertThat(star).contains("LH");
        assertThat(star).doesNotContain("BA");
    }

    @Test
    void unalignedCarriersAreReachableAsAGroup() {
        assertThat(members(Alliance.NONE)).contains("EK");
    }

    @Test
    void lowCostCarriersAreMarkedAndNoneOfThemAreAligned() {
        List<String> lccs = lowCostCarriers();
        assertThat(lccs).contains("FR");
        assertThat(lccs).doesNotContain("LH");
        for (String code : lccs) {
            assertThat(carrier(code).isAligned()).isFalse();
        }
    }
}
