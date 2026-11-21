package io.github.rkaushikdev.gussetline.api.routers;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.api.Deps;
import io.github.rkaushikdev.gussetline.api.Requires;
import io.github.rkaushikdev.gussetline.api.Role;
import io.github.rkaushikdev.gussetline.api.Schemas.SupplierOut;
import io.github.rkaushikdev.gussetline.collect.Capability;
import io.github.rkaushikdev.gussetline.collect.Registry;
import io.github.rkaushikdev.gussetline.collect.Supplier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the suppliers are doing, which is the first screen the desk opens.
 *
 * <p>The breaker state and the quota are the two numbers that explain every quiet morning, and they
 * live in the collector's memory rather than in the database, so this reads them off the running
 * process. A deployment with two processes therefore answers differently on each, which is correct and
 * is why the reply names the process.
 *
 * <p>Operator only. The quota and the cost per call are commercial and the fares are not.
 */
@RestController
@RequestMapping("/sources")
@Requires(Role.OPERATOR)
@Tag(name = "sources")
public final class SourceRoutes {

    private final Deps deps;

    public SourceRoutes(Deps deps) {
        this.deps = deps;
    }

    /** Every supplier, by name. */
    @GetMapping
    @Operation(summary = "List Sources")
    public List<SupplierOut> listSources() {
        Registry registry = Registry.defaultRegistry();
        return registry.names().stream()
                .map(registry::get)
                .map(this::supplierOut)
                .toList();
    }

    /** One supplier, by name in any case. */
    @GetMapping("/{name}")
    @Operation(summary = "Read Source")
    public SupplierOut readSource(@PathVariable String name) {
        Registry registry = Registry.defaultRegistry();
        if (!registry.names().contains(name.toLowerCase(Locale.ROOT))) {
            throw new NotFoundError("no such supplier", Details.of("name", name));
        }
        return supplierOut(registry.get(name));
    }

    /** Which process answered, because the breaker state is per process. */
    @GetMapping("/-/process")
    @Operation(summary = "Process", description = "Which process answered, because the breaker state is per process.")
    public Map<String, String> process() {
        return Map.of("pid", String.valueOf(ProcessHandle.current().pid()));
    }

    private SupplierOut supplierOut(Supplier supplier) {
        return new SupplierOut(
                supplier.name(),
                supplier.host(),
                supplier.capabilities().stream().map(Capability::value).sorted().toList(),
                supplier.costPerCall().toString(),
                deps.settings().dailyQuota());
    }
}
