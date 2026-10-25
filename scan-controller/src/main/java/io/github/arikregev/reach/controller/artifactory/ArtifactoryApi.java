package io.github.arikregev.reach.controller.artifactory;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestResponse;

/** The JFrog Access endpoints the controller calls to mint and revoke a scan Job's token (D-011, D-044). */
@RegisterRestClient(configKey = "artifactory")
@Produces(MediaType.APPLICATION_JSON)
public interface ArtifactoryApi {

    /** Creates an access token; the reply holds {@code token_id} and {@code access_token}. */
    @POST
    @Path("/access/api/v1/tokens")
    @Consumes(MediaType.APPLICATION_JSON)
    RestResponse<JsonNode> create(@HeaderParam("Authorization") String adminToken, JsonNode body);

    /** Revokes a token by id (200), 404 once it is gone. */
    @DELETE
    @Path("/access/api/v1/tokens/{id}")
    RestResponse<JsonNode> revoke(@HeaderParam("Authorization") String adminToken, @PathParam("id") String tokenId);
}
