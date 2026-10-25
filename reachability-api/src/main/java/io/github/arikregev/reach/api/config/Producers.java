package io.github.arikregev.reach.api.config;

import io.github.arikregev.reach.contracts.ScanRequestParser;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

public class Producers {

    @Produces
    @Singleton
    ScanRequestParser scanRequestParser() {
        return new ScanRequestParser();
    }
}
