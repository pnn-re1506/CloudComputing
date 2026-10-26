package edu.cloudcomputing26.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

/**
 * Answers exactly one question: "is this the correct secret token?"
 *
 * This replaces AuthConfig.isAuthorized(request)'s token comparison from
 * s3-v3-webapp/s3-v4-webapp. The comparison itself hasn't changed at
 * all (still a single String.equals against one shared secret) — what's
 * changed is where that comparison runs: inside AuthFilter, in the same
 * JVM as the servlets, versus here, as its own separately deployed,
 * separately invoked unit of compute.
 *
 * The correct token is read from this function's own TOKEN environment
 * variable (set in the Lambda console / CLI, not hardcoded in this
 * class) via System.getenv("TOKEN") — Lambda's equivalent of
 * AuthConfig.TOKEN's "EDIT ME" constant, but configured outside the
 * code entirely, so changing the token never requires a redeploy.
 *
 * Input: the submitted token, as a plain String (Lambda's runtime
 * deserializes the invocation payload, a JSON string like "claude26",
 * directly into the handler's input type).
 * Output: true or false, as a plain Boolean (Lambda serializes it back
 * to the caller as JSON: "true" or "false").
 */
public class CheckTokenIsValid implements RequestHandler<String, Boolean> {

    @Override
    public Boolean handleRequest(String submittedToken, Context context) {
        String correctToken = System.getenv("TOKEN");
        return correctToken != null && correctToken.equals(submittedToken);
    }
}
