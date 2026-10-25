package io.github.arikregev.reach.api.config;

import io.github.arikregev.reach.contracts.WriteBackMode;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The {@code reach.*} keys read by reachability-api (architecture.md section 12). */
@ConfigMapping(prefix = "reach")
public interface ReachConfig {

    Queue queue();

    Dt dt();

    S3 s3();

    Results results();

    Auth auth();

    Github github();

    Kafka kafka();

    Partitions partitions();

    interface Queue {
        /** Priority classes seeded into {@code scan.priority_class} at start-up; rows that exist are kept (D-007). */
        Map<String, PriorityClass> classes();
    }

    interface PriorityClass {
        int weight();

        @WithDefault("1")
        double agingPerMin();

        Optional<Integer> maxRunning();
    }

    interface Dt {
        Writeback writeback();

        interface Writeback {
            /** Used when no GLOBAL, ORG or REPO row exists in {@code scan.writeback_policy} (D-004). */
            @WithDefault("OFF")
            WriteBackMode mode();
        }
    }

    interface S3 {
        @WithDefault("PT24H")
        Duration linkTtl();

        @WithDefault("P7D")
        Duration linkTtlMax();
    }

    interface Results {
        @WithDefault("500")
        int pageSize();
    }

    interface Auth {
        /** Classes above {@code default} that Kafka requests may use (5.4a j). Empty by default. */
        Optional<Set<String>> kafkaPriorityClasses();
    }

    interface Github {
        /** Hosts a request's {@code repo.url} may name. */
        @WithDefault("github.com")
        List<String> allowedHosts();
    }

    interface Kafka {
        Topics topics();

        interface Topics {
            @WithDefault("appsec.reachability.status.v1")
            String status();
        }
    }

    interface Partitions {
        /** Monthly partitions kept ready ahead of the current month (D-025). */
        @WithDefault("3")
        int monthsAhead();
    }
}
