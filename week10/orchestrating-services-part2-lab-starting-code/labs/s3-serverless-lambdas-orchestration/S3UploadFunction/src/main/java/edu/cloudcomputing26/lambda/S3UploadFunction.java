package edu.cloudcomputing26.lambda;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import java.net.URLDecoder;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.SfnException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Part 2 of "Orchestrating Services". The S3 upload stays in this
 * Lambda, exactly as in week 9. The DynamoDB note write, its retries,
 * and the compensating delete now run in the FileNotesOrchestration
 * state machine. This handler starts that execution and polls until it
 * leaves RUNNING, because a Standard workflow has no start-and-wait API.
 *
 * Auth, CORS, the raw request body, and the X-Note header are unchanged
 * from the week 9 upload function. Cors.java and AuthCheck.java in this
 * module are reused as-is.
 */
public class S3UploadFunction implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final String BUCKET_NAME = "cloud26-phucnhan-bucket";

    /** Name must be FileNotesOrchestration, region ap-southeast-1, account from week 9. */
    private static final String STATE_MACHINE_ARN =
            "arn:aws:states:ap-southeast-1:353842237220:stateMachine:FileNotesOrchestration";

    /**
     * Failure path waits 1s + 2s + 4s inside the state machine before it
     * deletes the object. 40 polls × 500 ms covers that without polling
     * forever. The Lambda timeout must be longer than this loop (30 s).
     */
    private static final int MAX_POLL_ATTEMPTS = 40;
    private static final long POLL_INTERVAL_MILLIS = 500L;

    private static final S3Client S3 = S3Client.builder()
            .region(Region.AP_SOUTHEAST_1)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    private static final SfnClient SFN = SfnClient.builder()
            .region(Region.AP_SOUTHEAST_1)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (Cors.isPreflight(event)) {
            return Cors.preflightResponse();
        }

        if (!AuthCheck.isAuthorized(event.getHeaders())) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(401)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Missing or incorrect token.")
                    .build();
        }

        Map<String, String> headers = event.getHeaders();
        String key = (headers == null) ? null : headers.get("x-filename");
        if (key == null || key.isBlank()) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(400)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Missing X-Filename header.")
                    .build();
        }

        String body = event.getBody();
        if (body == null) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(400)
                    .withHeaders(Cors.HEADERS)
                    .withBody("No file was submitted.")
                    .build();
        }

        byte[] fileBytes = Boolean.TRUE.equals(event.getIsBase64Encoded())
                ? Base64.getDecoder().decode(body)
                : body.getBytes(StandardCharsets.UTF_8);

        String encodedNote = headers.get("x-note");
        String note = (encodedNote == null) ? "" : URLDecoder.decode(encodedNote, StandardCharsets.UTF_8);

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();

            S3.putObject(putRequest, RequestBody.fromBytes(fileBytes));
        } catch (S3Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(Cors.HEADERS)
                    .withBody("S3 error: " + e.awsErrorDetails().errorMessage()
                            + " (status " + e.statusCode() + ")")
                    .build();
        }

        String executionInput = "{"
                + "\"fileKey\":\"" + escapeJson(key) + "\","
                + "\"note\":\"" + escapeJson(note) + "\","
                + "\"bucketName\":\"" + escapeJson(BUCKET_NAME) + "\""
                + "}";

        try {
            StartExecutionResponse startResponse = SFN.startExecution(StartExecutionRequest.builder()
                    .stateMachineArn(STATE_MACHINE_ARN)
                    .input(executionInput)
                    .build());

            String executionArn = startResponse.executionArn();

            for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
                DescribeExecutionResponse describeResponse = SFN.describeExecution(DescribeExecutionRequest.builder()
                        .executionArn(executionArn)
                        .build());

                if (describeResponse.status() == ExecutionStatus.RUNNING) {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                    continue;
                }

                if (describeResponse.status() == ExecutionStatus.SUCCEEDED) {
                    return APIGatewayV2HTTPResponse.builder()
                            .withStatusCode(200)
                            .withHeaders(Cors.HEADERS)
                            .withBody("Uploaded successfully.\n"
                                    + "Bucket: " + BUCKET_NAME + "\n"
                                    + "Key:    " + key + "\n"
                                    + "Size:   " + fileBytes.length + " bytes\n"
                                    + "Note:   " + note + "\n")
                            .build();
                }

                // FAILED, TIMED_OUT, or ABORTED. On SaveNoteFailed the state
                // machine has already deleted the object. On
                // SaveNoteFailedAndCompensationFailed the delete also failed.
                String error = describeResponse.error() == null
                        ? String.valueOf(describeResponse.status())
                        : describeResponse.error();
                String cause = describeResponse.cause() == null ? "" : describeResponse.cause();
                return APIGatewayV2HTTPResponse.builder()
                        .withStatusCode(500)
                        .withHeaders(Cors.HEADERS)
                        .withBody(error + (cause.isEmpty() ? "" : " — " + cause))
                        .build();
            }

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Orchestration still running after " + MAX_POLL_ATTEMPTS
                            + " polls. Execution: " + executionArn)
                    .build();

        } catch (SfnException e) {
            // The object is already in S3, and this execution never started,
            // so the state machine cannot compensate.
            
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(Cors.HEADERS)
                    .withBody("File uploaded, but starting the note orchestration failed: "
                            + e.awsErrorDetails().errorMessage()
                            + " (status " + e.statusCode() + ")")
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(Cors.HEADERS)
                    .withBody("Interrupted while waiting for the note orchestration.")
                    .build();
        }
    }

    /** Same escaping as week 9's list function, plus newlines that would break this hand-built JSON. */
    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
