package io.github.arikregev.reach.api;

import static io.github.arikregev.reach.api.TestRequests.bytes;
import static io.github.arikregev.reach.api.TestRequests.example;
import static io.github.arikregev.reach.api.TestRequests.reach;
import static io.github.arikregev.reach.api.TestRequests.uid;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ScansResourceTest {

    @Inject
    Db db;

    @AfterEach
    void clearPolicies() {
        db.update("DELETE FROM scan.writeback_policy");
    }

    private static io.restassured.response.ValidatableResponse submit(ObjectNode request) {
        return given().contentType(ContentType.JSON).body(bytes(request)).when().post("/api/v1/scans").then();
    }

    @Test
    @TestSecurity(user = "ci", roles = {"scan-submitter", "results-reader"})
    void acceptsTheExampleAndQueuesIt() throws Exception {
        ObjectNode request = example();

        String scanId = submit(request)
                .statusCode(202)
                .header("Location", org.hamcrest.Matchers.containsString("/api/v1/scans/"))
                .body("status", equalTo("QUEUED"))
                .body("duplicate", equalTo(false))
                .body("request_uid", equalTo(uid(request)))
                .body("priority_class", equalTo("default"))
                .body("dt_write_back", equalTo("OFF"))
                .body("created_at", notNullValue())
                .body("warnings", empty())
                .extract().path("scanId");

        UUID id = UUID.fromString(scanId);
        assertThat(db.query("SELECT attempt, priority_class FROM scan.scan_queue WHERE scan_id = ?", id))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("attempt", 1)
                        .containsEntry("priority_class", "default"));
        List<Map<String, Object>> outbox = db.query(
                "SELECT topic, key, payload::text AS payload FROM scan.outbox WHERE key = ?", scanId);
        assertThat(outbox).singleElement().satisfies(r -> assertThat(r).containsEntry("topic", "appsec.reachability.status.v1"));
        JsonNode event = TestRequests.MAPPER.readTree((String) outbox.getFirst().get("payload"));
        assertThat(event.path("activity_id").asInt()).isEqualTo(1);
        assertThat(event.path("type_uid").asInt()).isEqualTo(600701);
        assertThat(event.at("/metadata/uid").asText()).isEqualTo(scanId);
        assertThat(event.at("/metadata/correlation_uid").asText()).isEqualTo(uid(request));
        assertThat(event.at("/unmapped/reachability/scan_status").asText()).isEqualTo("QUEUED");
        assertThat(event.at("/unmapped/reachability/attempt").asInt()).isEqualTo(1);

        given().when().get("/api/v1/scans/" + scanId).then()
                .statusCode(200)
                .body("scanId", equalTo(scanId))
                .body("status", equalTo("QUEUED"))
                .body("attempt", equalTo(1))
                .body("repo.url", equalTo("https://github.com/acme/payments"))
                .body("repo.commit", equalTo("4f1c9e2b7d3a8e6f0b5c1d2e3f4a5b6c7d8e9f0a"))
                .body("dt_project.name", equalTo("acme/payments"))
                .body("dt_project.version", equalTo("main"))
                .body("dt_project.uuid", nullValue())
                .body("formats", containsInAnyOrder("ocsf", "cyclonedx-vex", "openvex"))
                .body("counts", nullValue())
                .body("reason", nullValue())
                .body("claimed_at", nullValue());
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void resubmissionReturnsTheSameScan() {
        ObjectNode request = example();
        String first = submit(request).statusCode(202).extract().path("scanId");

        submit(request).statusCode(202)
                .body("scanId", equalTo(first))
                .body("duplicate", equalTo(true));
        assertThat(db.query("SELECT 1 FROM scan.scan WHERE request_uid = ?", uid(request))).hasSize(1);
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void requestsWithoutUidAreNeverDeduplicated() {
        ObjectNode request = example();
        ((ObjectNode) request.get("metadata")).remove("uid");

        String a = submit(request).statusCode(202).extract().path("scanId");
        String b = submit(request).statusCode(202).extract().path("scanId");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void schemaViolationsAreProblemJson() {
        ObjectNode request = example();
        ((ObjectNode) request.at("/unmapped/reachability/repo")).put("commit", "main");

        submit(request).statusCode(400)
                .contentType("application/problem+json")
                .body("code", equalTo("SCHEMA_INVALID"))
                .body("status", equalTo(400))
                .body("type", equalTo("https://reach.example.internal/problems/schema-invalid"))
                .body("errors.pointer", containsInAnyOrder("/unmapped/reachability/repo/commit"));
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void malformedJsonIsSchemaInvalid() {
        given().contentType(ContentType.JSON).body("{").when().post("/api/v1/scans").then()
                .statusCode(400).body("code", equalTo("SCHEMA_INVALID"));
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void repoHostMustBeAllowed() {
        ObjectNode request = example();
        ((ObjectNode) request.at("/unmapped/reachability/repo")).put("url", "https://gitlab.com/acme/payments");

        submit(request).statusCode(400)
                .body("errors[0].pointer", equalTo("/unmapped/reachability/repo/url"));
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void repoUrlMustNotCarryCredentials() {
        ObjectNode request = example();
        ((ObjectNode) request.at("/unmapped/reachability/repo")).put("url", "https://x:token@github.com/acme/payments");

        submit(request).statusCode(400).body("errors[0].pointer", equalTo("/unmapped/reachability/repo/url"));
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void priorityAboveDefaultWithoutRoleIsDowngraded() {
        ObjectNode request = example();
        reach(request).put("priority", "pr");

        submit(request).statusCode(202)
                .body("priority_class", equalTo("default"))
                .body("warnings.code", containsInAnyOrder("PRIORITY_DOWNGRADED"));
    }

    @Test
    @TestSecurity(user = "ci", roles = {"scan-submitter", "reach-priority-pr"})
    void priorityRoleGrantsTheClass() {
        ObjectNode request = example();
        reach(request).put("priority", "pr");

        submit(request).statusCode(202).body("priority_class", equalTo("pr")).body("warnings", empty());
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void classesAtOrBelowDefaultNeedNoRole() {
        ObjectNode request = example();
        reach(request).put("priority", "bulk");

        submit(request).statusCode(202).body("priority_class", equalTo("bulk")).body("warnings", empty());
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void unknownPriorityFallsBackToDefault() {
        ObjectNode request = example();
        reach(request).put("priority", "urgent");

        submit(request).statusCode(202)
                .body("priority_class", equalTo("default"))
                .body("warnings.code", containsInAnyOrder("PRIORITY_DOWNGRADED"));
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void nulAndDurationOverflowAre400() {
        ObjectNode nul = example();
        ((ObjectNode) nul.get("metadata")).put("uid", "rest-nul-\u0000");
        submit(nul).statusCode(400).body("code", equalTo("SCHEMA_INVALID"))
                .body("errors[0].pointer", equalTo("/metadata/uid"));

        ObjectNode overflow = example();
        reach(overflow).put("link_ttl", "P999999999999999D");
        submit(overflow).statusCode(400).body("errors[0].pointer", equalTo("/unmapped/reachability/link_ttl"));
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void withDefaultDisabledRequestsUseTheLowestClassAndHigherOnesNeedARole() {
        db.update("UPDATE scan.priority_class SET enabled = false WHERE name = 'default'");
        try {
            ObjectNode plain = example();
            submit(plain).statusCode(202).body("priority_class", equalTo("bulk"))
                    .body("warnings.code", containsInAnyOrder("PRIORITY_DOWNGRADED"));

            ObjectNode critical = example();
            reach(critical).put("priority", "critical");
            submit(critical).statusCode(202).body("priority_class", equalTo("bulk"))
                    .body("warnings.code", containsInAnyOrder("PRIORITY_DOWNGRADED"));
        } finally {
            db.update("UPDATE scan.priority_class SET enabled = true WHERE name = 'default'");
        }
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void noEnabledPriorityClassIsAConfigurationError() {
        db.update("UPDATE scan.priority_class SET enabled = false");
        try {
            submit(example()).statusCode(503).body("code", equalTo("MISCONFIGURED"));
        } finally {
            db.update("UPDATE scan.priority_class SET enabled = true");
        }
    }

    @Test
    void policyKeysMustBeLowerCase() {
        // The claim query and the write-back lookup compare keys directly with the lower-cased names intake stores.
        assertThatThrownBy(() -> db.update(
                "INSERT INTO scan.concurrency_limit (scope, key, max_running) VALUES ('REPO', 'ACME/Payments', 1)"))
                .hasMessageContaining("concurrency_limit_key_lower");
        assertThatThrownBy(() -> db.update(
                "INSERT INTO scan.writeback_policy (scope, key, mode) VALUES ('ORG', 'Acme', 'VEX')"))
                .hasMessageContaining("writeback_policy_key_lower");
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void mixedCaseRepoUrlMatchesLowerCasePolicies() {
        db.update("INSERT INTO scan.writeback_policy (scope, key, mode) VALUES ('GLOBAL', '*', 'ANALYSIS')");
        db.update("INSERT INTO scan.writeback_policy (scope, key, mode) VALUES ('REPO', 'acme/payments', 'OFF')");
        ObjectNode request = example();
        ((ObjectNode) request.at("/unmapped/reachability/repo")).put("url", "https://github.com/ACME/Payments");
        reach(request).put("dt_write_back", "ANALYSIS");

        String id = submit(request).statusCode(202).body("dt_write_back", equalTo("OFF")).extract().path("scanId");

        assertThat(db.query("SELECT org, repo, repo_url FROM scan.scan WHERE id = ?::uuid", id)).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("org", "acme").containsEntry("repo", "payments")
                        .containsEntry("repo_url", "https://github.com/ACME/Payments"));
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void concurrentSubmissionsWithTheSameUidMakeOneScan() throws Exception {
        ObjectNode request = example();
        ((ObjectNode) request.get("metadata")).put("uid", "concurrent-" + UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> ids = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                ids.add(pool.submit(() -> given().contentType(ContentType.JSON).body(bytes(request))
                        .when().post("/api/v1/scans").then().statusCode(anyOf(
                                equalTo(200), equalTo(202))).extract().<String>path("scanId")));
            }
            Set<String> distinct = new HashSet<>();
            for (var f : ids) {
                distinct.add(f.get());
            }
            assertThat(distinct).hasSize(1);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void writeBackIsNarrowedToTheMostSpecificPolicy() {
        db.update("INSERT INTO scan.writeback_policy (scope, key, mode) VALUES ('GLOBAL', '*', 'ANALYSIS')");
        db.update("INSERT INTO scan.writeback_policy (scope, key, mode) VALUES ('ORG', 'acme', 'VEX')");
        db.update("INSERT INTO scan.writeback_policy (scope, key, mode) VALUES ('REPO', 'acme/payments', 'COMMENT')");
        ObjectNode request = example();
        reach(request).put("dt_write_back", "ANALYSIS");

        submit(request).statusCode(202)
                .body("dt_write_back", equalTo("COMMENT"))
                .body("warnings.code", containsInAnyOrder("WRITE_BACK_NARROWED"));
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void writeBackDefaultsToThePolicyMode() {
        db.update("INSERT INTO scan.writeback_policy (scope, key, mode) VALUES ('ORG', 'acme', 'VEX')");
        ObjectNode request = example();
        reach(request).remove("dt_write_back");

        submit(request).statusCode(202).body("dt_write_back", equalTo("VEX")).body("warnings", empty());
    }

    @Test
    @TestSecurity(user = "ci", roles = "scan-submitter")
    void writeBackAboveConfiguredDefaultIsNarrowedToOff() {
        ObjectNode request = example();
        reach(request).put("dt_write_back", "VEX");

        submit(request).statusCode(202)
                .body("dt_write_back", equalTo("OFF"))
                .body("warnings.code", containsInAnyOrder("WRITE_BACK_NARROWED"));
    }

    @Test
    @TestSecurity(user = "ci", roles = {"scan-submitter", "results-reader"})
    void linkTtlIsCapped() {
        ObjectNode request = example();
        reach(request).put("link_ttl", "P30D");

        String scanId = submit(request).statusCode(202)
                .body("warnings.code", containsInAnyOrder("LINK_TTL_CAPPED"))
                .extract().path("scanId");
        assertThat(db.query("SELECT options->>'link_ttl' AS ttl FROM scan.scan WHERE id = ?", UUID.fromString(scanId)))
                .singleElement().satisfies(r -> assertThat(r).containsEntry("ttl", "PT168H"));
        given().get("/api/v1/scans/" + scanId).then().body("warnings.code", containsInAnyOrder("LINK_TTL_CAPPED"));
    }

    @Test
    void unauthenticatedIs401ProblemJson() {
        submit(example()).statusCode(401)
                .contentType("application/problem+json")
                .body("code", equalTo("UNAUTHENTICATED"));
    }

    @Test
    @TestSecurity(user = "reader", roles = "results-reader")
    void wrongRoleIs403ProblemJson() {
        submit(example()).statusCode(403)
                .contentType("application/problem+json")
                .body("code", equalTo("FORBIDDEN"));
    }

    @Test
    @TestSecurity(user = "reader", roles = "results-reader")
    void unknownScanIs404() {
        given().get("/api/v1/scans/" + UUID.randomUUID()).then().statusCode(404).body("code", equalTo("SCAN_NOT_FOUND"));
        given().get("/api/v1/scans/not-a-uuid").then().statusCode(404).body("code", equalTo("SCAN_NOT_FOUND"));
    }

    @Test
    @TestSecurity(user = "ci", roles = {"scan-submitter", "results-reader"})
    void listsWithFiltersAndCursor() {
        String commit = UUID.randomUUID().toString().replace("-", "") + "00000000";
        String[] ids = new String[3];
        for (int i = 0; i < 3; i++) {
            ObjectNode request = example();
            ((ObjectNode) request.at("/unmapped/reachability/repo")).put("commit", commit);
            ids[i] = submit(request).statusCode(202).extract().path("scanId");
        }

        var page1 = given().queryParam("repo", "acme/payments").queryParam("commit", commit)
                .queryParam("status", "QUEUED,FETCHING").queryParam("page_size", 2)
                .get("/api/v1/scans").then().statusCode(200)
                .body("items", hasSize(2))
                .body("items[0].scanId", equalTo(ids[2]))
                .body("items[1].scanId", equalTo(ids[1]))
                .body("items[0].dt_project.name", equalTo("acme/payments"))
                .body("next_cursor", notNullValue())
                .extract();
        String cursor = page1.path("next_cursor");

        given().queryParam("repo", "acme/payments").queryParam("commit", commit)
                .queryParam("status", "QUEUED,FETCHING").queryParam("page_size", 2).queryParam("cursor", cursor)
                .get("/api/v1/scans").then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].scanId", equalTo(ids[0]))
                .body("next_cursor", nullValue());

        given().queryParam("commit", commit).queryParam("cursor", cursor)
                .get("/api/v1/scans").then().statusCode(400).body("code", equalTo("INVALID_PARAMETER"));
        given().queryParam("commit", commit).queryParam("status", "SUCCEEDED")
                .get("/api/v1/scans").then().statusCode(200).body("items", empty());
    }

    @Test
    @TestSecurity(user = "reader", roles = "results-reader")
    void rejectsBadListParameters() {
        given().queryParam("status", "DONE").get("/api/v1/scans").then().statusCode(400)
                .body("code", equalTo("INVALID_PARAMETER")).body("errors[0].pointer", equalTo("status"));
        given().queryParam("page_size", 0).get("/api/v1/scans").then().statusCode(400);
        given().queryParam("page_size", 501).get("/api/v1/scans").then().statusCode(400);
        given().queryParam("repo", "acme").get("/api/v1/scans").then().statusCode(400);
        given().queryParam("commit", "main").get("/api/v1/scans").then().statusCode(400);
    }
}
