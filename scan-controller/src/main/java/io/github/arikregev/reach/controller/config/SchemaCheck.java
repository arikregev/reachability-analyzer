package io.github.arikregev.reach.controller.config;

import io.quarkus.liquibase.LiquibaseFactory;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;

/**
 * reachability-api owns the migrations (D-031). The controller refuses to start while the database lacks
 * changesets of the changelog it was built with, so a rollout never runs new code on an old schema.
 */
@ApplicationScoped
public class SchemaCheck {

    private final LiquibaseFactory factory;
    private final ControllerConfig config;

    public SchemaCheck(LiquibaseFactory factory, ControllerConfig config) {
        this.factory = factory;
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) throws Exception {
        if (!config.schema().checkAtStart()) {
            return;
        }
        try (Liquibase liquibase = factory.createLiquibase()) {
            List<ChangeSet> pending = liquibase.listUnrunChangeSets(factory.createContexts(), factory.createLabels());
            if (!pending.isEmpty()) {
                throw new IllegalStateException(pending.size() + " Liquibase changeset(s) not applied yet (first: "
                        + pending.getFirst().getId() + "); start reachability-api first");
            }
        }
    }
}
