package io.github.arikregev.reach.controller.dt;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestResponse;

/** The Dependency-Track 5.x {@code /api/v1} read endpoints used by the controller (architecture.md 8.1). */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "dt")
@RegisterClientHeaders(DtApiKeyHeaders.class)
public interface DtApi {

    @GET
    @Path("/project/lookup")
    RestResponse<JsonNode> lookupProject(@QueryParam("name") String name, @QueryParam("version") String version);

    /** One page of findings; {@code X-Total-Count} carries the total. */
    @GET
    @Path("/finding/project/{uuid}")
    RestResponse<JsonNode> findings(@PathParam("uuid") String projectUuid, @QueryParam("offset") int offset,
                                    @QueryParam("limit") int limit);

    /** The project's CycloneDX SBOM (dependency graph, no vulnerabilities) for the input bundle (8.1 step 3). */
    @GET
    @Path("/bom/cyclonedx/project/{uuid}")
    RestResponse<JsonNode> bom(@PathParam("uuid") String projectUuid, @QueryParam("format") String format,
                               @QueryParam("variant") String variant, @QueryParam("version") String specVersion);
}
