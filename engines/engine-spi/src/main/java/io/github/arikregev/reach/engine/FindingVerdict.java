package io.github.arikregev.reach.engine;

import java.util.List;

public record FindingVerdict(TargetFinding target, Verdict verdict, FindingReason reason, Level level,
                             List<CallPath> paths) {

    public FindingVerdict {
        paths = List.copyOf(paths);
    }

    public static FindingVerdict unknown(TargetFinding target, FindingReason reason) {
        return new FindingVerdict(target, Verdict.UNKNOWN, reason, Level.NONE, List.of());
    }
}
