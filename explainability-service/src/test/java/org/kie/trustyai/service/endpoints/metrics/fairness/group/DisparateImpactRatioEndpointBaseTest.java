package org.kie.trustyai.service.endpoints.metrics.fairness.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.restassured.http.ContentType;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kie.trustyai.explainability.model.Dataframe;
import org.kie.trustyai.service.endpoints.metrics.RequestPayloadGenerator;
import org.kie.trustyai.service.mocks.MockDatasource;
import org.kie.trustyai.service.mocks.MockPrometheusScheduler;
import org.kie.trustyai.service.payloads.BaseScheduledResponse;
import org.kie.trustyai.service.payloads.metrics.BaseMetricResponse;
import org.kie.trustyai.service.payloads.metrics.fairness.group.GroupMetricRequest;
import org.kie.trustyai.service.payloads.scheduler.ScheduleId;
import org.kie.trustyai.service.payloads.scheduler.ScheduleList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class DisparateImpactRatioEndpointBaseTest {

    protected static final String MODEL_ID = "example1";
    protected static final int N_SAMPLES = 100;
    @Inject
    Instance<MockDatasource> datasource;

    @Inject
    Instance<MockPrometheusScheduler> scheduler;

    @AfterEach
    void clearRequests() {
        scheduler.get().getAllRequests().clear();
    }


    private void populate() {
        final Dataframe dataframe = datasource.get().generateRandomDataframe(N_SAMPLES);
        datasource.get().saveDataframe(dataframe, MODEL_ID);
        datasource.get().saveMetadata(datasource.get().createMetadata(dataframe), MODEL_ID);
    }

    @Test
    void get() {
        populate();
        when().get()
                .then()
                .statusCode(Response.Status.METHOD_NOT_ALLOWED.getStatusCode())
                .body(is(""));
    }

    @Test
    void postCorrect() throws JsonProcessingException {
        populate();

        final GroupMetricRequest payload = RequestPayloadGenerator.correct();

        final BaseMetricResponse response = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.OK.getStatusCode())
                .extract()
                .body().as(BaseMetricResponse.class);

        assertEquals("metric", response.getType());
        assertEquals("DIR", response.getName());
        assertFalse(Double.isNaN(response.getValue()));
    }

    // disabled until better integrated with ODH UI
    //    @Test
    //    void postMultiValueCorrect() throws JsonProcessingException {
    //        datasource.get().reset();
    //
    //        final GroupMetricRequest payload = RequestPayloadGenerator.multiValueCorrect();
    //
    //        final BaseMetricResponse response = given()
    //                .contentType(ContentType.JSON)
    //                .body(payload)
    //                .when().post()
    //                .then().statusCode(Response.Status.OK.getStatusCode())
    //                .extract()
    //                .body().as(BaseMetricResponse.class);
    //
    //        assertEquals("metric", response.getType());
    //        assertEquals("DIR", response.getName());
    //        assertFalse(Double.isNaN(response.getValue()));
    //    }
    //
    //    @Test
    //    void postMultiValueMismatchingType() throws JsonProcessingException {
    //        datasource.get().reset();
    //
    //        final GroupMetricRequest payload = RequestPayloadGenerator.multiValueMismatchingType();
    //
    //        given()
    //                .contentType(ContentType.JSON)
    //                .body(payload)
    //                .when().post()
    //                .then().statusCode(Response.Status.BAD_REQUEST.getStatusCode())
    //                .body(containsString("Received invalid type for privileged attribute=age: got 'wrong', expected object compatible with 'INT32'"));
    //    }

    @Test
    void postThresh() throws JsonProcessingException {
        populate();

        // with large threshold, the DIR is inside bounds
        GroupMetricRequest payload = RequestPayloadGenerator.correct();
        payload.setThresholdDelta(.5);
        BaseMetricResponse response = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.OK.getStatusCode())
                .extract()
                .body().as(BaseMetricResponse.class);

        assertEquals("metric", response.getType());
        assertEquals("DIR", response.getName());
        assertFalse(response.getThresholds().outsideBounds);

        // with negative threshold, the DIR is guaranteed outside bounds
        payload = RequestPayloadGenerator.correct();
        payload.setThresholdDelta(-.5);
        response = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.OK.getStatusCode())
                .extract()
                .body().as(BaseMetricResponse.class);

        assertEquals("metric", response.getType());
        assertEquals("DIR", response.getName());
        assertTrue(response.getThresholds().outsideBounds);
    }

    @Test
    @DisplayName("DIR request incorrectly typed")
    void postIncorrectType() throws JsonProcessingException {
        populate();

        final GroupMetricRequest payload = RequestPayloadGenerator.incorrectType();

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(RestResponse.StatusCode.BAD_REQUEST)
                .body(containsString("Invalid type for output=income: got 'male', expected object compatible with 'INT32'"));
    }

    @Test
    @DisplayName("DIR request with incorrect input")
    void postIncorrectInput() throws JsonProcessingException {
        populate();

        final GroupMetricRequest payload = RequestPayloadGenerator.incorrectInput();

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
                .body(containsString("No protected attribute found with name=city"));

    }

    @Test
    void postUnknownType() throws JsonProcessingException {
        populate();

        final Map<String, Object> payload = RequestPayloadGenerator.unknownType();

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
                .body(any(String.class));

    }

    @Test
    void postManyWrongNames() throws JsonProcessingException {
        populate();

        final GroupMetricRequest payload = RequestPayloadGenerator.incorrectManyWrongNames();

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
                .body(allOf(
                        containsString("No output found with name=icnome"),
                        containsString("No protected attribute found with name=city")));
    }

    @Test
    void postManyWrongTypes() throws JsonProcessingException {
        populate();

        final GroupMetricRequest payload = RequestPayloadGenerator.incorrectManyWrongTypes();

        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
                .body(allOf(
                        containsString("Invalid type for output=income: got 'approved-doesnt-exist', expected object compatible with 'INT32'"),
                        containsString("Received invalid type for privileged attribute=gender: got 'lemons', expected object compatible with 'INT32'"),
                        containsString("Received invalid type for unprivileged attribute=gender: got '1.5', expected object compatible with 'INT32'")));
    }

    @Test
    void listSchedules() throws JsonProcessingException {
        populate();

        // No schedule request made yet
        final ScheduleList emptyList = given()
                .when()
                .get("/requests")
                .then().statusCode(Response.Status.OK.getStatusCode()).extract().body().as(ScheduleList.class);

        assertEquals(0, emptyList.requests.size());

        // Perform multiple schedule requests
        final GroupMetricRequest payload = RequestPayloadGenerator.correct();
        final BaseScheduledResponse firstRequest = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/request")
                .then().statusCode(Response.Status.OK.getStatusCode()).extract().body().as(BaseScheduledResponse.class);

        assertNotNull(firstRequest.getRequestId());

        final BaseScheduledResponse secondRequest = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/request")
                .then().statusCode(Response.Status.OK.getStatusCode()).extract().body().as(BaseScheduledResponse.class);

        assertNotNull(secondRequest.getRequestId());

        ScheduleList scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(Response.Status.OK.getStatusCode()).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(2, scheduleList.requests.size());

        // Remove one request
        final ScheduleId firstRequestId = new ScheduleId();
        firstRequestId.requestId = firstRequest.getRequestId();
        given().contentType(ContentType.JSON).when().body(firstRequestId).delete("/request")
                .then().statusCode(Response.Status.OK.getStatusCode()).body(is("Removed"));

        scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(Response.Status.OK.getStatusCode()).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(1, scheduleList.requests.size());

        // Remove second request
        final ScheduleId secondRequestId = new ScheduleId();
        secondRequestId.requestId = secondRequest.getRequestId();
        given().contentType(ContentType.JSON).when().body(secondRequestId).delete("/request")
                .then().statusCode(Response.Status.OK.getStatusCode()).body(is("Removed"));

        scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(Response.Status.OK.getStatusCode()).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(0, scheduleList.requests.size());

        // Remove non-existing request
        final ScheduleId nonExistingRequestId = new ScheduleId();
        nonExistingRequestId.requestId = secondRequest.getRequestId();
        given().contentType(ContentType.JSON).when().body(nonExistingRequestId).delete("/request")
                .then().statusCode(RestResponse.StatusCode.NOT_FOUND).body(is(""));

        scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(Response.Status.OK.getStatusCode()).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(0, scheduleList.requests.size());
    }

    @Test
    void requestWrongType() {
        populate();

        // No schedule request made yet
        final ScheduleList emptyList = given()
                .when()
                .get("/requests")
                .then().statusCode(200).extract().body().as(ScheduleList.class);

        assertEquals(0, emptyList.requests.size());

        // Perform multiple schedule requests
        final GroupMetricRequest payload = RequestPayloadGenerator.correct();
        final BaseScheduledResponse firstRequest = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/request")
                .then()
                .statusCode(200).extract().body().as(BaseScheduledResponse.class);

        assertNotNull(firstRequest.getRequestId());

        final GroupMetricRequest wrongPayload = RequestPayloadGenerator.incorrectType();
        given()
                .contentType(ContentType.JSON)
                .body(wrongPayload)
                .when()
                .post("/request")
                .then().statusCode(RestResponse.StatusCode.BAD_REQUEST)
                .body(containsString("got 'male', expected object compatible with 'INT32'"));

        ScheduleList scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(200).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(1, scheduleList.requests.size());

        // Remove one request
        final ScheduleId firstRequestId = new ScheduleId();
        firstRequestId.requestId = firstRequest.getRequestId();
        given().contentType(ContentType.JSON).when().body(firstRequestId).delete("/request")
                .then().statusCode(200).body(is("Removed"));

        scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(200).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(0, scheduleList.requests.size());

    }

    @Test
    void requestUnknowType() {
        populate();

        // No schedule request made yet
        final ScheduleList emptyList = given()
                .when()
                .get("/requests")
                .then().statusCode(200).extract().body().as(ScheduleList.class);

        assertEquals(0, emptyList.requests.size());

        // Perform multiple schedule requests
        final GroupMetricRequest payload = RequestPayloadGenerator.correct();
        final BaseScheduledResponse firstRequest = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when()
                .post("/request")
                .then().statusCode(200).extract().body().as(BaseScheduledResponse.class);

        assertNotNull(firstRequest.getRequestId());

        final Map<String, Object> unknownPayload = RequestPayloadGenerator.unknownType();
        given()
                .contentType(ContentType.JSON)
                .body(unknownPayload)
                .when()
                .post("/request")
                .then().statusCode(RestResponse.StatusCode.BAD_REQUEST);

        ScheduleList scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(200).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(1, scheduleList.requests.size());

        // Remove one request
        final ScheduleId firstRequestId = new ScheduleId();
        firstRequestId.requestId = firstRequest.getRequestId();
        given().contentType(ContentType.JSON).when().body(firstRequestId).delete("/request")
                .then().statusCode(200).body(is("Removed"));

        scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(200).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(0, scheduleList.requests.size());

    }

    @Test
    void listNames() {
        populate();
        // No schedule request made yet
        final ScheduleList emptyList = given()
                .when()
                .get("/requests")
                .then().statusCode(RestResponse.StatusCode.OK).extract().body().as(ScheduleList.class);
        assertEquals(0, emptyList.requests.size());

        List<String> names = List.of("name1", "name2", "name3");
        Map<UUID, String> nameIDs = new HashMap<>();

        // Perform multiple schedule requests
        for (String name : names) {
            final GroupMetricRequest payload = RequestPayloadGenerator.named(name);
            BaseScheduledResponse scheduledResponse = given()
                    .contentType(ContentType.JSON)
                    .body(payload)
                    .when()
                    .post("/request")
                    .then().statusCode(RestResponse.StatusCode.OK).extract().body().as(BaseScheduledResponse.class);
            nameIDs.put(scheduledResponse.getRequestId(), name);
        }

        ScheduleList scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(RestResponse.StatusCode.OK).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(3, scheduleList.requests.size());

        // check that names are as expected
        for (int i = 0; i < scheduleList.requests.size(); i++) {
            UUID returnedID = scheduleList.requests.get(i).id;
            assertEquals(nameIDs.get(returnedID), scheduleList.requests.get(i).request.getRequestName());

            // delete the corresponding request
            final ScheduleId thisRequestId = new ScheduleId();
            thisRequestId.requestId = returnedID;
            given()
                    .contentType(ContentType.JSON)
                    .when()
                    .body(thisRequestId)
                    .delete("/request")
                    .then()
                    .statusCode(200)
                    .body(is("Removed"));
        }

        scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(RestResponse.StatusCode.OK).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(0, scheduleList.requests.size());
    }

    @Test
    void listThresholds() {
        populate();
        // No schedule request made yet
        final ScheduleList emptyList = given()
                .when()
                .get("/requests")
                .then().statusCode(RestResponse.StatusCode.OK).extract().body().as(ScheduleList.class);
        assertEquals(0, emptyList.requests.size());

        List<Double> threshs = List.of(.1, .05, .5);
        Map<UUID, Double> threshIDs = new HashMap<>();

        // Perform multiple schedule requests
        for (Double thresh : threshs) {
            final GroupMetricRequest payload = RequestPayloadGenerator.correct();
            payload.setThresholdDelta(thresh);
            BaseScheduledResponse scheduledResponse = given()
                    .contentType(ContentType.JSON)
                    .body(payload)
                    .when()
                    .post("/request")
                    .then().statusCode(RestResponse.StatusCode.OK).extract().body().as(BaseScheduledResponse.class);
            threshIDs.put(scheduledResponse.getRequestId(), thresh);
        }

        ScheduleList scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(RestResponse.StatusCode.OK).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(3, scheduleList.requests.size());

        // check that names are as expected
        for (int i = 0; i < scheduleList.requests.size(); i++) {
            UUID returnedID = scheduleList.requests.get(i).id;
            assertEquals(threshIDs.get(returnedID), ((GroupMetricRequest) scheduleList.requests.get(i).request).getThresholdDelta());

            // delete the corresponding request
            final ScheduleId thisRequestId = new ScheduleId();
            thisRequestId.requestId = returnedID;
            given()
                    .contentType(ContentType.JSON)
                    .when()
                    .body(thisRequestId)
                    .delete("/request")
                    .then()
                    .statusCode(200)
                    .body(is("Removed"));
        }

        scheduleList = given()
                .when()
                .get("/requests")
                .then().statusCode(RestResponse.StatusCode.OK).extract().body().as(ScheduleList.class);

        // Correct number of active requests
        assertEquals(0, scheduleList.requests.size());
    }

    @Test
    @DisplayName("DIR request should produce correct result")
    void postCorrectFilteringSynthetic() throws JsonProcessingException {
        populate();

        final GroupMetricRequest payload = RequestPayloadGenerator.correct();
        final BaseMetricResponse response = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.OK.getStatusCode())
                .extract()
                .body().as(BaseMetricResponse.class);

        final Double value = response.getValue();
        assertEquals("metric", response.getType());
        assertEquals("DIR", response.getName());
        assertFalse(Double.isNaN(value));

        final Dataframe syntheticDataframe = datasource.get().generateRandomSyntheticDataframe(N_SAMPLES);
        datasource.get().saveDataframe(syntheticDataframe, MODEL_ID);

        final BaseMetricResponse responseSecond = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.OK.getStatusCode())
                .extract()
                .body().as(BaseMetricResponse.class);

        assertEquals("metric", responseSecond.getType());
        assertEquals("DIR", responseSecond.getName());
        assertEquals(value, responseSecond.getValue());
    }


    @Test
    @DisplayName("DIR request with no organic data")
    void postCorrectFilteringOnlySynthetic() throws JsonProcessingException {

        final Dataframe syntheticDataframe = datasource.get().generateRandomSyntheticDataframe(N_SAMPLES);
        datasource.get().saveDataframe(syntheticDataframe, MODEL_ID);

        final GroupMetricRequest payload = RequestPayloadGenerator.correct();
        given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());

        final Dataframe organicDataframe = datasource.get().generateRandomDataframe(N_SAMPLES);
        datasource.get().saveDataframe(organicDataframe, MODEL_ID);

        final BaseMetricResponse responseSecond = given()
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post()
                .then()
                .statusCode(Response.Status.OK.getStatusCode())
                .extract()
                .body().as(BaseMetricResponse.class);

        assertEquals("metric", responseSecond.getType());
        assertEquals("DIR", responseSecond.getName());
        assertFalse(Double.isNaN(responseSecond.getValue()));
    }

}
