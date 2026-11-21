package io.github.rkaushikdev.gussetline.cli;

import static io.github.rkaushikdev.gussetline.cli.Desk.OK;
import static io.github.rkaushikdev.gussetline.cli.Desk.OPERATOR_CAN_FIX;
import static io.github.rkaushikdev.gussetline.cli.Desk.SOMEBODY_SHOULD_LOOK;
import static io.github.rkaushikdev.gussetline.cli.Desk.exitCodeFor;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.ConfigError;
import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.SupplierBlocked;
import io.github.rkaushikdev.gussetline.SupplierUnavailable;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.api.routers.HealthRoutes;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliMainTest {

    @TempDir
    Path tmp;

    private final Shell runner = new Shell();
    private String onDisk;

    @BeforeEach
    void aDatabaseOnDisk() {
        onDisk = Shell.fileUrl(tmp);
    }

    @Test
    void theThreeCodesAreDistinct() {
        assertThat(Set.of(OK, OPERATOR_CAN_FIX, SOMEBODY_SHOULD_LOOK)).hasSize(3);
    }

    @Test
    void somethingTheOperatorCanFixIsAOne() {
        assertThat(exitCodeFor(new NotFoundError("no such ticket"))).isEqualTo(OPERATOR_CAN_FIX);
        assertThat(exitCodeFor(new ValidationError("not a market"))).isEqualTo(OPERATOR_CAN_FIX);
        assertThat(exitCodeFor(new NoFareFound("nothing filed"))).isEqualTo(OPERATOR_CAN_FIX);
        assertThat(exitCodeFor(new ConfigError("not a number"))).isEqualTo(OPERATOR_CAN_FIX);
    }

    @Test
    void somethingTheyCannotIsATwo() {
        assertThat(exitCodeFor(new SupplierUnavailable("timeout"))).isEqualTo(SOMEBODY_SHOULD_LOOK);
        assertThat(exitCodeFor(new SupplierBlocked("403"))).isEqualTo(SOMEBODY_SHOULD_LOOK);
        assertThat(exitCodeFor(new ConstructionError("no through fare"))).isEqualTo(SOMEBODY_SHOULD_LOOK);
        assertThat(exitCodeFor(new IOException("connection reset"))).isEqualTo(SOMEBODY_SHOULD_LOOK);
    }

    @Test
    void theHelpListsTheCommands() {
        Shell.Result reply = runner.run("--help");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("settings", "create-schema", "check");
    }

    @Test
    void theShortHelpFlagWorksToo() {
        assertThat(runner.run("-h").code()).isZero();
    }

    @Test
    void settingsPrintsWhatTheRunWouldUse() {
        Shell.Result reply = runner.run("--database", onDisk, "settings");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("selling in EUR", "quota      1000", "dry run    no");
    }

    @Test
    void theDatabaseOverrideReachesTheSettings() {
        Shell.Result reply = runner.run("--database", onDisk, "settings");
        assertThat(reply.output()).contains("gussetline against desk, selling in");
    }

    @Test
    void theEnvironmentIsReadWhenNothingOverridesIt() {
        Shell.Result reply = runner.exporting("GUSSETLINE_SELLING_CURRENCY", "GBP")
                .exporting("GUSSETLINE_DATABASE_URL", Database.IN_MEMORY)
                .run("settings");
        assertThat(reply.output()).contains("selling in GBP");
    }

    @Test
    void aSettingThatIsNotANumberStopsTheRun() {
        Shell.Result reply = runner.exporting("GUSSETLINE_DAILY_QUOTA", "lots").run("settings");
        assertThat(reply.failure()).containsInstanceOf(ConfigError.class);
        assertThat(exitCodeFor(reply.failure().orElseThrow())).isEqualTo(OPERATOR_CAN_FIX);
        assertThat(reply.code()).isEqualTo(OPERATOR_CAN_FIX);
    }

    @Test
    void createSchemaThenCheckReadsAnEmptyDesk() {
        assertThat(runner.run("--database", onDisk, "create-schema").code()).isZero();
        Shell.Result reply = runner.run("--database", onDisk, "check");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("airports      0", "observations  0");
    }

    @Test
    void countingAnEmptyDeskIsNotItselfAFailure() {
        // The counts are a reading. An empty desk is what create-schema leaves, and a command that
        // fails there fails on the one morning it is expected.
        runner.run("--database", onDisk, "create-schema");
        assertThat(runner.run("--database", onDisk, "check").code()).isEqualTo(OK);
    }

    @Test
    void askingAnEmptyDeskWhetherItIsFitIsAFailure() {
        runner.run("--database", onDisk, "create-schema");
        Shell.Result reply = runner.run("--database", onDisk, "check", "--fit");
        assertThat(reply.code()).isEqualTo(OPERATOR_CAN_FIX);
        assertThat(reply.output()).contains("not fit");
    }

    @Test
    void theFitReportNamesWhatIsMissingAndHowToFixIt() {
        runner.run("--database", onDisk, "create-schema");
        Shell.Result reply = runner.run("--database", onDisk, "check", "--fit");
        assertThat(reply.output()).contains("load fares", "load rules", "load combinability", "collect run");
    }

    @Test
    void theFitReportMarksEachQuestionAndNotOnlyTheTotal() {
        runner.run("--database", onDisk, "create-schema");
        Shell.Result reply = runner.run("--database", onDisk, "check", "--fit");
        for (String question : List.of("tariff", "rules", "combinability", "collecting", "observations")) {
            assertThat(reply.output()).contains("no  " + question);
        }
    }

    @Test
    void theCountsComeOutWhetherOrNotTheDeskIsFit() {
        runner.run("--database", onDisk, "create-schema");
        Shell.Result reply = runner.run("--database", onDisk, "check", "--fit");
        assertThat(reply.output()).contains("fares         0");
    }

    @Test
    void aDeskWithSomethingInItAnswersThoseQuestionsYes() {
        // Seeded as of today, because the two date questions ask whether the desk is collecting now
        // and not whether it ever did.
        runner.run("--database", onDisk, "create-schema");
        runner.run("--database", onDisk, "seed", "--on", LocalDate.now().toString());
        Shell.Result reply = runner.run("--database", onDisk, "check", "--fit");
        for (String question : List.of("tariff", "rules", "combinability", "observations")) {
            assertThat(reply.output()).contains("ok  " + question);
        }
    }

    @Test
    void aDeskThatHasNeverCollectedIsNotFitHoweverFullItIs() {
        // A seeded desk holds fares, rules and a week of readings and has never run a collection,
        // which is exactly the state a count cannot see.
        runner.run("--database", onDisk, "create-schema");
        runner.run("--database", onDisk, "seed", "--on", LocalDate.now().toString());
        Shell.Result reply = runner.run("--database", onDisk, "check", "--fit");
        assertThat(reply.code()).isEqualTo(OPERATOR_CAN_FIX);
        assertThat(reply.output()).contains("not fit: collecting");
    }

    @Test
    void theQuestionsAreTheOnesTheServiceAnswers() {
        // Two lists of what a desk needs would answer differently within a month, and the one nobody
        // looks at would be the wrong one.
        runner.run("--database", onDisk, "create-schema");
        Shell.Result reply = runner.run("--database", onDisk, "check", "--fit");
        List<String> asked;
        try (Database database = Shell.database(onDisk);
                UnitOfWork work = database.unit()) {
            asked = new ArrayList<>(HealthRoutes.checks(work).keySet());
        }
        assertThat(asked).isNotEmpty();
        for (String question : asked) {
            assertThat(reply.output()).contains(question);
        }
    }

    @Test
    void createSchemaSaysSoUnlessItIsToldToBeQuiet() {
        Shell.Result loud = runner.run("--database", onDisk, "create-schema");
        assertThat(loud.output()).contains("schema built");
        Shell.Result quiet = runner.run("--database", onDisk, "--quiet", "create-schema");
        assertThat(quiet.output().strip()).isEmpty();
    }

    @Test
    void checkAgainstADatabaseWithNoSchemaIsAFailure() {
        Shell.Result reply = runner.run("--database", onDisk, "check");
        assertThat(reply.code()).isNotZero();
    }

    @Test
    void creatingTheSchemaTwiceIsNotAnError() {
        runner.run("--database", onDisk, "create-schema");
        assertThat(runner.run("--database", onDisk, "create-schema").code()).isZero();
    }

    @Test
    void aCommandThatDoesNotExistIsAUsageError() {
        Shell.Result reply = runner.run("nonsense");
        assertThat(reply.code()).isEqualTo(2);
        assertThat(reply.output()).contains("No such command");
    }

    @Test
    void theVersionIsReported() {
        Shell.Result reply = runner.run("--version");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("0.1.0");
    }

    @Test
    void theLogSaysWhichCommandStarted() {
        Shell.Result reply = runner.run("--database", onDisk, "create-schema");
        assertThat(reply.output()).contains("event=command.started", "command=create-schema");
    }

    @Test
    void everyLineOfOneCommandSharesARunIdentifier() {
        Shell.Result reply = runner.run("--database", onDisk, "create-schema");
        Set<String> runs = reply.output()
                .lines()
                .flatMap(line -> Arrays.stream(line.split("\\s+")))
                .filter(part -> part.startsWith("run="))
                .collect(Collectors.toSet());
        assertThat(runs).hasSize(1);
    }

    @Test
    void twoCommandsDoNotShareOne() {
        String first = runner.run("--database", onDisk, "create-schema").output();
        String second = runner.run("--database", onDisk, "check").output();
        assertThat(first.split("run=")[1].substring(0, 12)).isNotEqualTo(second.split("run=")[1].substring(0, 12));
    }

    @Test
    void quietMeansQuietOnTheLogAsWell() {
        // A cron run told to say nothing on success that still writes a line per event fills the
        // night shift's mail.
        Shell.Result reply = runner.run("--database", onDisk, "--quiet", "create-schema");
        assertThat(reply.output()).doesNotContain("event=");
    }

    @Test
    void theLevelCanStillBeNamedWhenSomebodyWantsBoth() {
        Shell.Result reply = runner.run("--database", onDisk, "--quiet", "--log-level", "INFO", "create-schema");
        assertThat(reply.output()).contains("event=command.started");
        assertThat(reply.output()).doesNotContain("schema built");
    }
}
