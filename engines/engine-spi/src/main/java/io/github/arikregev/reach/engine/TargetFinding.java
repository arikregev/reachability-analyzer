package io.github.arikregev.reach.engine;

import java.util.List;
import java.util.UUID;

/** One DT finding the engines are asked about (architecture.md 7.2). */
public record TargetFinding(UUID componentUuid, UUID vulnerabilityUuid, String purl, String vulnId,
                            List<String> aliases, List<VulnerableSymbol> symbols) {

    public TargetFinding {
        aliases = List.copyOf(aliases);
        symbols = List.copyOf(symbols);
    }

    public Ecosystem ecosystem() {
        return Ecosystem.ofPurl(purl);
    }
}
