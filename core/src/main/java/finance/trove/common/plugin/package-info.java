/**
 * The ingestion SPI: the shape a transaction arrives in, and the interface a source implements.
 *
 * <p>It sits in {@code common} because both the ingestion pipeline and every source
 * implementation depend on it and neither may depend on the other. Nothing here depends on any
 * other {@code finance.trove} package — {@code ArchitectureTest} enforces that for the whole of
 * {@code common} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3).
 */
package finance.trove.common.plugin;
