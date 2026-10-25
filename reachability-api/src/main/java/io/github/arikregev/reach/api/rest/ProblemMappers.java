package io.github.arikregev.reach.api.rest;

import io.github.arikregev.reach.api.intake.NoPriorityClassException;
import io.github.arikregev.reach.api.intake.RequestRejectedException;
import io.github.arikregev.reach.api.store.StoreUnavailableException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Maps every failure to {@code application/problem+json} (architecture.md 5.4a a). */
public class ProblemMappers {

    private static final Logger LOG = Logger.getLogger(ProblemMappers.class);

    @ServerExceptionMapper
    public Response problem(ProblemException e) {
        return e.problem().toResponse();
    }

    @ServerExceptionMapper
    public Response rejected(RequestRejectedException e) {
        return Problem.schemaInvalid(e.violations()).toResponse();
    }

    @ServerExceptionMapper({UnauthorizedException.class, AuthenticationFailedException.class})
    public Response unauthenticated(RuntimeException e) {
        return Response.fromResponse(Problem.of(401, "UNAUTHENTICATED", "Authentication required",
                "missing, expired or invalid bearer token").toResponse())
                .header("WWW-Authenticate", "Bearer").build();
    }

    @ServerExceptionMapper
    public Response forbidden(ForbiddenException e) {
        return Problem.of(403, "FORBIDDEN", "Forbidden", "the token lacks the role this endpoint requires").toResponse();
    }

    @ServerExceptionMapper
    public Response unavailable(StoreUnavailableException e) {
        LOG.warn("Postgres unavailable", e);
        return Response.fromResponse(Problem.of(503, "UNAVAILABLE", "Service unavailable",
                "the database is unavailable; retry with backoff").toResponse()).header("Retry-After", "5").build();
    }

    @ServerExceptionMapper
    public Response misconfigured(NoPriorityClassException e) {
        LOG.error(e.getMessage());
        return Response.fromResponse(Problem.of(503, "MISCONFIGURED", "Service unavailable",
                "no priority class is enabled; contact the service operators").toResponse())
                .header("Retry-After", "60").build();
    }

    @ServerExceptionMapper
    public Response web(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        if (status >= 500) {
            LOG.error("Request failed", e);
        }
        String code = switch (status) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "BAD_REQUEST";
        };
        Response.StatusType type = e.getResponse().getStatusInfo();
        return Problem.of(status, code, type.getReasonPhrase(), status >= 500 ? "internal error" : e.getMessage())
                .toResponse();
    }

    @ServerExceptionMapper
    public Response unexpected(Exception e) {
        LOG.error("Unhandled error", e);
        return Problem.of(500, "INTERNAL_ERROR", "Internal error", "internal error").toResponse();
    }
}
