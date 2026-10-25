package io.github.arikregev.reach.controller.github;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestResponse;

/** The GitHub REST endpoints the controller calls to mint a source-fetch token (architecture.md 3.3, 9). */
@RegisterRestClient(configKey = "github")
@Produces("application/vnd.github+json")
@ClientHeaderParam(name = "X-GitHub-Api-Version", value = "2022-11-28")
public interface GitHubApi {

    /** The App's installation on the repo's owner; needs the App JWT. */
    @GET
    @Path("/repos/{owner}/{repo}/installation")
    RestResponse<JsonNode> installation(@HeaderParam("Authorization") String appJwt, @PathParam("owner") String owner,
                                        @PathParam("repo") String repo);

    @POST
    @Path("/app/installations/{id}/access_tokens")
    @Consumes(MediaType.APPLICATION_JSON)
    RestResponse<JsonNode> accessToken(@HeaderParam("Authorization") String appJwt, @PathParam("id") long installationId,
                                       JsonNode body);

    /** The commit, read with the installation token, so the scan fails before a Job is launched for a bad sha. */
    @GET
    @Path("/repos/{owner}/{repo}/commits/{sha}")
    RestResponse<JsonNode> commit(@HeaderParam("Authorization") String token, @PathParam("owner") String owner,
                                  @PathParam("repo") String repo, @PathParam("sha") String sha);
}
