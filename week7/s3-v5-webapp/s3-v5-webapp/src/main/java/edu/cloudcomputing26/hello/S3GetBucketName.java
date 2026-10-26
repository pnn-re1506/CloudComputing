package edu.cloudcomputing26.hello;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Returns the name of the S3 bucket the S3 client is configured to use.
 *
 * Requires a valid Authorization header — enforced by AuthFilter, which
 * runs before this servlet for every /s3/* request.
 */
@WebServlet(name = "S3GetBucketName", urlPatterns = {"/s3/bucket"})
public class S3GetBucketName extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("text/plain;charset=UTF-8");
        try (PrintWriter out = response.getWriter()) {
            out.print(S3Config.BUCKET_NAME);
        }
    }
}
