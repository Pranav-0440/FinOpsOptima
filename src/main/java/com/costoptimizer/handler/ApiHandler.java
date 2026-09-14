package com.costoptimizer.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.costoptimizer.model.Finding;
import com.costoptimizer.service.DatabaseService;
import com.costoptimizer.service.MockDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(ApiHandler.class);
    
    private final DatabaseService databaseService = new DatabaseService();
    private final MockDataService mockDataService = new MockDataService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        logger.info("API Gateway request received: HTTP {} {}", request.getHttpMethod(), request.getPath());

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token");

        // Handle CORS Preflight OPTIONS
        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(headers)
                    .withBody("{\"status\":\"ok\"}");
        }

        try {
            String path = request.getPath() != null ? request.getPath() : "";
            
            if (path.endsWith("/findings") && "GET".equalsIgnoreCase(request.getHttpMethod())) {
                return handleGetFindings(headers);
            } else if (path.endsWith("/remediate") && "POST".equalsIgnoreCase(request.getHttpMethod())) {
                return handlePostRemediate(request.getBody(), headers);
            } else {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(404)
                        .withHeaders(headers)
                        .withBody("{\"error\":\"Endpoint not found. Use GET /findings or POST /remediate\"}");
            }
        } catch (Exception e) {
            logger.error("Error handling API request: {}", e.getMessage(), e);
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withHeaders(headers)
                    .withBody("{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    private APIGatewayProxyResponseEvent handleGetFindings(Map<String, String> headers) throws Exception {
        logger.info("Fetching findings list");
        List<Finding> findings;
        
        // If we are in local offline mode or table scan fails, fallback to mock data
        try {
            findings = databaseService.getAllFindings();
            if (findings.isEmpty() && "true".equalsIgnoreCase(System.getenv("MOCK_AWS"))) {
                logger.info("DynamoDB is empty but MOCK_AWS=true. Returning mock data.");
                findings = mockDataService.getAllMockFindings();
            }
        } catch (Exception e) {
            logger.warn("Database read failed, falling back to mock data: {}", e.getMessage());
            findings = mockDataService.getAllMockFindings();
        }

        String json = objectMapper.writeValueAsString(findings);
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(headers)
                .withBody(json);
    }

    private APIGatewayProxyResponseEvent handlePostRemediate(String body, Map<String, String> headers) throws Exception {
        logger.info("Processing remediation POST payload: {}", body);
        if (body == null || body.trim().isEmpty()) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withHeaders(headers)
                    .withBody("{\"error\":\"Missing request body\"}");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(body, Map.class);
        
        RemediationHandler remediationHandler = new RemediationHandler();
        // Invoke the remediation lambda logic directly
        String result = remediationHandler.handleRequest(payload, null);

        Map<String, String> responseMap = new HashMap<>();
        responseMap.put("message", result);
        responseMap.put("status", "success");

        String json = objectMapper.writeValueAsString(responseMap);
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(headers)
                .withBody(json);
    }
}
