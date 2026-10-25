package io.github.arikregev.reach.engine;

import java.util.List;

/** A path from an entry point (first frame) to the vulnerable code (last frame). */
public record CallPath(List<Frame> frames) {

    public CallPath {
        frames = List.copyOf(frames);
    }
}
