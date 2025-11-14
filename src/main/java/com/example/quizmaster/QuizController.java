package com.example.quizmaster;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Main controller for handling all API requests for the QuizMaster application.
 */
@RestController
@CrossOrigin(origins = "*") // Allows requests from any frontend, good for development
@RequestMapping("/api")
public class QuizController {

    private final GeminiService geminiService;
    // Defines a stable temporary directory for file uploads
    private final Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "quizmaster_uploads");

    /**
     * Constructor to inject the GeminiService and create the temp directory.
     * @param geminiService The service that communicates with AI models.
     */
    public QuizController(GeminiService geminiService) {
        this.geminiService = geminiService;
        // Create the temporary directory on application startup if it doesn't exist
        if (!Files.exists(tempDir)) {
            try {
                Files.createDirectories(tempDir);
                System.out.println("Created temporary upload directory at: " + tempDir.toString());
            } catch (Exception e) {
                System.err.println("Could not create temporary upload directory: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Handles the PDF file upload, saves it temporarily, and calls the service
     * to generate topics from its content.
     *
     * @param file The PDF file uploaded by the user.
     * @return A ResponseEntity containing the generated topics or an error.
     */
    @PostMapping("/upload-and-analyze")
    public ResponseEntity<?> uploadAndAnalyze(@RequestParam("pdf") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "File is empty."));
        }

        Path tempFile = null;
        try {
            // Create a unique filename to avoid conflicts and save the file
            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.pdf";
            tempFile = tempDir.resolve(System.currentTimeMillis() + "-" + originalFilename);
            file.transferTo(tempFile);
            System.out.println("File temporarily saved at: " + tempFile.toString());

            // Call the service to get topics from the saved file
            String topicsJson = geminiService.generateTopics(tempFile);

            // Build a successful response
            Map<String, Object> response = Map.of(
                    "success", true,
                    "topics", new JSONArray(topicsJson).toList(),
                    "uploadedFileName", tempFile.getFileName().toString() // Return the temp name for the next step
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            // Clean up the temporary file if something goes wrong
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ex) { /* ignored */ }
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Failed to process PDF with AI.", "details", e.getMessage()));
        }
    }

    /**
     * Generates a quiz based on a previously uploaded file and a selected topic.
     *
     * @param payload A map containing the topic, questionCount, and the temporary filename.
     * @return A ResponseEntity containing the generated quiz or an error.
     */
    @PostMapping("/generate-quiz")
    public ResponseEntity<?> generateQuiz(@RequestBody Map<String, Object> payload) {
        String topic = (String) payload.get("topic");
        Object countObj = payload.get("questionCount");
        String uploadedFileName = (String) payload.get("uploadedFileName");

        // Handle both integer and string from JSON for questionCount
        Integer questionCount = (countObj instanceof Number) ? ((Number) countObj).intValue() : Integer.parseInt(countObj.toString());

        if (topic == null || questionCount == null || uploadedFileName == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Missing required parameters."));
        }

        Path filePath = tempDir.resolve(uploadedFileName);

        if (!Files.exists(filePath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "error", "File not found. It may have expired. Please upload again."));
        }

        try {
            // Call the service to generate the quiz
            String quizJsonString = geminiService.generateQuiz(filePath, topic, questionCount);

            Map<String, Object> response = Map.of(
                    "success", true,
                    "quiz", new JSONObject(quizJsonString).toMap()
            );
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Failed to generate quiz from AI.", "details", e.getMessage()));
        } finally {
            // ALWAYS clean up the temporary file after the quiz is generated
            try {
                Files.deleteIfExists(filePath);
                System.out.println("Cleaned up file: " + uploadedFileName);
            } catch (Exception e) {
                System.err.println("Failed to clean up file: " + e.getMessage());
            }
        }
    }

    /**
     * Analyzes a list of wrong answers using Claude on OpenRouter to provide feedback.
     *
     * @param payload A map containing the list of wrong answers.
     * @return A ResponseEntity with the AI-generated analysis.
     */
    @PostMapping("/analyze-weak-areas")
    public ResponseEntity<?> analyzeWeakAreas(@RequestBody Map<String, List<Map<String, Object>>> payload) {
        List<Map<String, Object>> wrongAnswers = payload.get("wrongAnswers");
        if (wrongAnswers == null || wrongAnswers.isEmpty()) {
            // If there are no wrong answers, return a positive message
            return ResponseEntity.ok(Map.of("analysis", "No wrong answers to analyze. Keep up the great work!"));
        }
        try {
            // Call the service method that talks to Claude
            String analysis = geminiService.analyzeWeakAreasWithClaude(wrongAnswers);
            return ResponseEntity.ok(Map.of("analysis", analysis));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Failed to get analysis from AI.", "details", e.getMessage()));
        }
    }
}