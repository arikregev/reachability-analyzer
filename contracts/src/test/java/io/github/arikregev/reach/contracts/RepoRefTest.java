package io.github.arikregev.reach.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class RepoRefTest {

    @Test
    void lowerCasesOrgAndRepo() {
        assertThat(RepoRef.parse("https://github.com/ACME/Payments")).map(RepoRef::fullName).contains("acme/payments");
    }

    @Test
    void parsesOrgAndRepo() {
        assertThat(RepoRef.parse("https://GitHub.com/acme/payments.git"))
                .contains(new RepoRef("github.com", "acme", "payments"));
        assertThat(RepoRef.parse("https://github.com/acme/payments/")).map(RepoRef::fullName).contains("acme/payments");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://github.com/acme/payments",
            "https://token@github.com/acme/payments",
            "https://github.com:8443/acme/payments",
            "https://github.com/acme",
            "https://github.com/acme/payments/tree/main",
            "https://github.com/acme/payments?x=1",
            "https://github.com/acme/payments#readme",
            "https://github.com/acme/..",
            "not a url"})
    void rejectsOtherShapes(String url) {
        assertThat(RepoRef.parse(url)).isEmpty();
    }
}
