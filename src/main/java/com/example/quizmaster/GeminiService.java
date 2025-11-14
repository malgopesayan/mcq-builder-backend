package com.example.quizmaster;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class GeminiService {

    private final RestTemplate restTemplate;
    private final List<String> apiKeys;
    private final String geminiApiUrl;
    private final String openRouterApiKey;
    private final String openRouterApiUrl;
    private final AtomicInteger keyIndex = new AtomicInteger(0);

    public GeminiService(RestTemplate restTemplate,
                         @Value("${gemini.api.key.1}") String key1,
                         @Value("${gemini.api.key.2}") String key2,
                         @Value("${gemini.api.flash.url}") String geminiFlashUrl,
                         @Value("${openrouter.api.key}") String openRouterApiKey,
                         @Value("${openrouter.api.url}") String openRouterApiUrl) {
        this.restTemplate = restTemplate;
        this.apiKeys = List.of(key1, key2);
        this.geminiApiUrl = geminiFlashUrl;
        this.openRouterApiKey = openRouterApiKey;
        this.openRouterApiUrl = openRouterApiUrl;
    }

    private String getApiKey() {
        return apiKeys.get(keyIndex.getAndIncrement() % apiKeys.size());
    }

    public String generateTopics(Path filePath) throws IOException {
        String base64Pdf = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
        String prompt = "Analyze the content of the provided PDF document. Identify up to 10 main topics. For each topic, provide a concise 'title' and a one-sentence 'description'. Return the output as a valid JSON array of objects only. Do not include any text, backticks, or markdown formatting outside the JSON array.";
        String requestBody = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray()
                        .put(new JSONObject().put("text", prompt))
                        .put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "application/pdf").put("data", base64Pdf)))
                ))).toString();
        return callGemini(geminiApiUrl, requestBody);
    }

    public String generateQuiz(Path filePath, String topic, int questionCount) throws IOException {
        String base64Pdf = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
        String prompt = String.format(
                "You are an expert Quiz Generator. Use the provided document to create a quiz about the specific topic: \"%s\". Generate exactly %d multiple-choice questions. For each question, provide: a unique 'id' (string), the 'question' text (string), an array of exactly 4 'options' (strings), the index (0-3) of the 'correctAnswer' (number), and a concise 'explanation' (string). Return the output as a single, valid JSON object with one key: 'questions'. When you take question from document inside image then should remember don't miss any data from image. Do not include markdown.",
                topic, questionCount
        );
        String requestBody = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray()
                        .put(new JSONObject().put("text", prompt))
                        .put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "application/pdf").put("data", base64Pdf)))
                ))).toString();
        return callGemini(geminiApiUrl, requestBody);
    }

    private String callGemini(String url, String requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
        String fullUrl = url + "?key=" + getApiKey();
        String response = restTemplate.postForObject(fullUrl, entity, String.class);
        JSONObject responseObject = new JSONObject(response);
        return responseObject.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                .replaceAll("```json", "").replaceAll("```", "").trim();
    }

    // --- NEW METHOD FOR CLAUDE ANALYSIS ---
    public String analyzeWeakAreasWithClaude(List<Map<String, Object>> wrongAnswers) {
        String userData = new JSONArray(wrongAnswers).toString(2); // Pretty-print JSON for the AI
        String prompt = "You are a helpful academic tutor. A student has provided a list of quiz questions they answered incorrectly. Analyze this data to identify 1-3 key themes or weak areas. Provide a concise, encouraging, and actionable summary to help the student know what to study next. Address the user directly.\n\nHere is the data:\n" + userData;

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", "anthropic/claude-3.5-sonnet"); // The powerful model you requested
        requestBody.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + openRouterApiKey);
        headers.set("HTTP-Referer", "http://localhost:3000"); // Optional but good practice
        headers.set("X-Title", "QuizMaster"); // Optional

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
        String response = restTemplate.postForObject(openRouterApiUrl, entity, String.class);

        // Parse the OpenRouter response
        JSONObject responseObject = new JSONObject(response);
        return responseObject.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");
    }
}