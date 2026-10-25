package io.github.arikregev.reach.engine.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MavenPurlTest {

    @Test
    void parsesNamespaceNameVersionAndIgnoresQualifiers() {
        assertThat(MavenPurl.parse("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1?type=jar#sub"))
                .contains(new MavenPurl("org.apache.logging.log4j", "log4j-core", "2.14.1"));
        assertThat(MavenPurl.parse("pkg:maven/com.ex/a%2Bb@1.0%2B2")).contains(new MavenPurl("com.ex", "a+b", "1.0+2"));
        assertThat(MavenPurl.parse("pkg:npm/left-pad@1.0.0")).isEmpty();
        assertThat(MavenPurl.parse("pkg:maven/onlyname@1")).isEmpty();
    }

    @Test
    void matchesMavenAndGradleCacheLayouts() {
        MavenPurl p = new MavenPurl("org.yaml", "snakeyaml", "1.33");
        assertThat(p.matches(Path.of("/w/.m2/repository/org/yaml/snakeyaml/1.33/snakeyaml-1.33.jar"))).isTrue();
        assertThat(p.matches(Path.of("/w/.gradle/caches/modules-2/files-2.1/org.yaml/snakeyaml/1.33/abc/snakeyaml-1.33.jar"))).isTrue();
        assertThat(p.matches(Path.of("/w/.m2/repository/org/yaml/snakeyaml/2.0/snakeyaml-2.0.jar"))).isFalse();
        assertThat(p.matches(Path.of("/w/.m2/repository/org/yaml/snakeyaml-engine/1.33/x.jar"))).isFalse();
    }
}
