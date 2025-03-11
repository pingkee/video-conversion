package coco.gov.dsta.video_conversion.controller;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.HttpEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/video")
public class VideoProcessingController {

    private static final String TARGET_URL = "http://localhost:5001/api/coco/incident";

    @PostMapping(value = "/process", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> processVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam("workspaceId") String workspaceId,
            @RequestParam("userId") String userId,
            @RequestParam("createdAt") String createdAt) {

        File videoFile = null;
        try {
            System.out.println("processomg file ");
            videoFile = saveFile(file);
            System.out.println("extracting audio: ");
            byte[] audioData = extractAudio(videoFile);
            System.out.println("extracting to frames: ");
            List<byte[]> frames = extractFrames(videoFile);
            System.out.println("sending to endpoint: ");
            sendToEndpoint(audioData, frames, workspaceId, userId, createdAt);
            return ResponseEntity.ok("Processing completed successfully.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing video: " + e.getMessage());
        } finally {
            if (videoFile != null) {
                videoFile.delete(); // Delete the temporary video file
            }
        }
    }

    private File saveFile(MultipartFile file) throws IOException {
        File tempFile = File.createTempFile("uploaded_video", ".mp4");
        file.transferTo(tempFile);
        return tempFile;
    }

    private byte[] extractAudio(File videoFile) throws IOException, InterruptedException {
        // Create a temporary file to store the extracted audio
        File tempAudio = File.createTempFile("extracted_audio", ".mp3");
        tempAudio.deleteOnExit(); // Ensure the temp file is deleted when the JVM exits

        // Set up the FFmpeg process to extract the audio with the -y flag (force
        // overwrite)
        ProcessBuilder builder = new ProcessBuilder(
                "ffmpeg",
                "-y", // Automatically overwrite existing files
                "-i", videoFile.getAbsolutePath(), // Input video file
                "-q:a", "0", // Highest audio quality
                "-map", "a", // Extract audio only
                tempAudio.getAbsolutePath() // Output audio file
        );

        builder.redirectErrorStream(true); // Merge stdout and stderr
        Process process = builder.start();

        // Optionally: log the process output
        Thread outputThread = new Thread(() -> {
            try {
                process.getInputStream().transferTo(System.out);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        outputThread.start();

        // Wait for the process to finish
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            throw new IOException("FFmpeg process timed out");
        }

        // Check for errors in the process (non-zero exit value means failure)
        if (process.exitValue() != 0) {
            throw new IOException("FFmpeg process failed with exit code " + process.exitValue());
        }

        // Read the audio file into a byte array
        byte[] audioBytes = Files.readAllBytes(tempAudio.toPath());

        // The temp file is automatically deleted on exit, but we can explicitly delete
        // it now if needed
        tempAudio.delete();

        return audioBytes;
    }

    private List<byte[]> extractFrames(File videoFile) throws IOException, InterruptedException {
        List<byte[]> frameDataList = new ArrayList<>();
        File tempDir = Files.createTempDirectory("frames").toFile();
        ProcessBuilder builder = new ProcessBuilder(
                "ffmpeg", "-i", videoFile.getAbsolutePath(), "-vf", "fps=1",
                tempDir.getAbsolutePath() + "/frame%d.png");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        process.waitFor();

        File[] extractedFrames = tempDir.listFiles((dir, name) -> name.endsWith(".png"));
        if (extractedFrames != null) {
            for (File frame : extractedFrames) {
                frameDataList.add(Files.readAllBytes(frame.toPath()));
                frame.delete(); // Delete each frame after reading
            }
        }
        tempDir.delete(); // Delete temporary directory
        return frameDataList;
    }

    private void saveByteArrayToFile(byte[] data, String filename) throws IOException {
        System.out.println("saving file: ");
        Files.createDirectories(Paths.get("processed_files"));
        Path filePath = Paths.get("processed_files", filename);
        System.out.println("File saved: " + filePath.toAbsolutePath()); // Print path
        Files.write(filePath, data);
    }

    private void sendToEndpoint(byte[] audioData, List<byte[]> frames, String workspaceId, String userId,
            String createdAt) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("video", new ByteArrayResource(audioData) {
            @Override
            public String getFilename() {
                return "audio.mp3";
            }
        });

        try {
            saveByteArrayToFile(audioData, "audio.mp3");
        } catch (IOException e) {
            e.printStackTrace(); // Handle the error properly in production
        }

        for (int i = 0; i < frames.size(); i++) {
            final byte[] frameData = frames.get(i);
            final int frameIndex = i + 1;

            try {
                saveByteArrayToFile(frameData, "frame" + frameIndex + ".png");
            } catch (IOException e) {
                e.printStackTrace();
            }

            body.add("image" + frameIndex, new ByteArrayResource(frameData) {
                @Override
                public String getFilename() {
                    return "frame" + frameIndex + ".png";
                }
            });
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(TARGET_URL, requestEntity, String.class);
    }
}
