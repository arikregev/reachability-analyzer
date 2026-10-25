package io.github.arikregev.reach.api.rest;

/** Ends a request with the given problem body. */
public class ProblemException extends RuntimeException {

    private final Problem problem;

    public ProblemException(Problem problem) {
        super(problem.code() + ": " + problem.detail(), null, false, false);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
