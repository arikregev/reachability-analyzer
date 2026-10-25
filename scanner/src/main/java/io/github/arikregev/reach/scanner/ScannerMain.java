package io.github.arikregev.reach.scanner;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/** The scan Job's entrypoint; the exit code tells the controller whether the Job succeeded (architecture.md 3.4). */
@QuarkusMain
public class ScannerMain implements QuarkusApplication {

    @Inject
    ScanRunner runner;

    @Override
    public int run(String... args) {
        return runner.run();
    }
}
