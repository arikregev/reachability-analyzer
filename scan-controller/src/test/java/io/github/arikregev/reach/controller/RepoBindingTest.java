package io.github.arikregev.reach.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.arikregev.reach.controller.dt.RepoBinding;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** D-037: reading the repository a DT project names. */
class RepoBindingTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "https://github.com/acme/payments",
        "https://github.com/Acme/Payments.git",
        "http://github.com/acme/payments/",
        "https://github.com/acme/payments/tree/main/service",
        "git@github.com:acme/payments.git",
        "ssh://git@github.com/acme/payments.git",
        "git://github.com/acme/payments.git",
        "scm:git:https://github.com/acme/payments.git",
        "scm:git:git@github.com:acme/payments.git",
        "git+https://github.com/acme/payments.git"
    })
    void normalizesTheUsualVcsForms(String url) {
        assertThat(RepoBinding.normalize(url)).hasValue("github.com/acme/payments");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a url", "https://github.com/acme", "https://github.com/", "mailto:a@b"})
    void ignoresWhatIsNotARepository(String url) {
        assertThat(RepoBinding.normalize(url)).isEmpty();
    }

    @Test
    void readsVcsReferencesAndTags() throws Exception {
        var project = new ObjectMapper().readTree("""
                {"externalReferences": [{"type": "vcs", "url": "https://github.com/acme/payments.git"},
                                        {"type": "website", "url": "https://github.com/acme/site"}],
                 "tags": [{"name": "team-x"}, {"name": "reach-repo=github.acme.internal/Acme/Billing"}]}""");

        assertThat(RepoBinding.repositories(project))
                .containsExactly("github.com/acme/payments", "github.acme.internal/acme/billing");
    }

    @Test
    void comparesCaseInsensitively() {
        Set<String> bound = Set.of("github.com/acme/payments");
        assertThat(RepoBinding.check(bound, "github.com/Acme/Payments")).isEqualTo(RepoBinding.Result.BOUND);
        assertThat(RepoBinding.check(bound, "github.com/acme/billing")).isEqualTo(RepoBinding.Result.MISMATCH);
        assertThat(RepoBinding.check(Set.of(), "github.com/acme/payments")).isEqualTo(RepoBinding.Result.UNBOUND);
    }
}
