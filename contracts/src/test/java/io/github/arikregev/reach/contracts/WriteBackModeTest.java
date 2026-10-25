package io.github.arikregev.reach.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WriteBackModeTest {

    @Test
    void narrowsToTheLowerRank() {
        assertThat(WriteBackMode.ANALYSIS.narrowTo(WriteBackMode.COMMENT)).isEqualTo(WriteBackMode.COMMENT);
        assertThat(WriteBackMode.OFF.narrowTo(WriteBackMode.VEX)).isEqualTo(WriteBackMode.OFF);
        assertThat(WriteBackMode.VEX.narrowTo(WriteBackMode.VEX)).isEqualTo(WriteBackMode.VEX);
    }
}
