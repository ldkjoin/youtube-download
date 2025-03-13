package com.ldk.youtube.controller;

import com.ldk.youtube.service.YoutubeDownloadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Controller
public class YoutubeDownloadController {

    private static final Logger logger = LoggerFactory.getLogger(YoutubeDownloadController.class);

    @Autowired
    private YoutubeDownloadService youtubeDownloadService;
    
    @GetMapping("/youtube-downloader")
    public String youtubeDownloader() {
        return "youtube-downloader";
    }
    
    /**
     * 启动视频下载任务
     * @param videoUrl 视频URL
     * @param quality 视频质量
     * @return 包含任务ID的响应
     */
    @GetMapping("/api/download-video")
    @ResponseBody
    public ResponseEntity<?> downloadVideo(@RequestParam("url") String videoUrl, 
                                         @RequestParam("quality") String quality) {
        try {
            // 验证URL不为空
            if (videoUrl == null || videoUrl.trim().isEmpty()) {
                Map<String, String> response = new HashMap<>();
                response.put("error", "视频URL不能为空");
                return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
            }
            
            // 验证quality参数
            if (quality == null || quality.trim().isEmpty()) {
                quality = "best"; // 默认使用最佳质量
            }
            
            // 解码URL
            String decodedUrl = URLDecoder.decode(videoUrl, StandardCharsets.UTF_8.name());
            
            // 启动异步下载任务
            String taskId = youtubeDownloadService.downloadVideo(decodedUrl, quality).get();
            
            // 检查是否返回了错误
            if (taskId.startsWith("[ERROR]")) {
                Map<String, String> response = new HashMap<>();
                response.put("error", taskId.substring(8)); // 移除[ERROR]前缀
                logger.error("下载视频失败: {}", taskId);
                return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
            }
            
            // 返回任务ID
            Map<String, String> response = new HashMap<>();
            response.put("taskId", taskId);
            response.put("status", "downloading");
            logger.info("成功启动下载任务: {}", taskId);
            return new ResponseEntity<>(response, HttpStatus.OK);
            
        } catch (InterruptedException | ExecutionException e) {
            logger.error("下载视频时发生异常: {}", e.getMessage(), e);
            Map<String, String> response = new HashMap<>();
            response.put("error", "下载失败: 处理下载任务时出错");
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            logger.error("下载视频时发生未预期异常: {}", e.getMessage(), e);
            Map<String, String> response = new HashMap<>();
            response.put("error", "下载失败: " + e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    
    /**
     * 获取下载任务状态
     * @param taskId 任务ID
     * @return 任务状态
     */
    @GetMapping("/api/download-status/{taskId}")
    @ResponseBody
    public ResponseEntity<?> getDownloadStatus(@PathVariable("taskId") String taskId) {
        YoutubeDownloadService.DownloadStatus status = youtubeDownloadService.getDownloadStatus(taskId);
        
        if (status == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("taskId", status.getTaskId());
        response.put("status", status.getStatus());
        response.put("progress", status.getProgress());
        response.put("elapsedTimeMs", status.getElapsedTimeMs());
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
    
    /**
     * 获取已下载的视频文件
     * @param taskId 任务ID
     * @return 视频文件
     */
    @GetMapping("/api/download-file/{taskId}")
    @ResponseBody
    public ResponseEntity<byte[]> getDownloadedVideo(@PathVariable("taskId") String taskId) {
        YoutubeDownloadService.DownloadStatus status = youtubeDownloadService.getDownloadStatus(taskId);
        
        if (status == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        
        if (!"completed".equals(status.getStatus())) {
            return new ResponseEntity<>(HttpStatus.ACCEPTED);
        }
        
        byte[] videoData = youtubeDownloadService.getDownloadedVideo(taskId);
        if (videoData == null) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("video/mp4"));
        headers.setContentDispositionFormData("attachment", "youtube-video-" + status.getQuality() + ".mp4");
        headers.setContentLength(videoData.length);
        
        // 下载完成后清理任务资源
        youtubeDownloadService.cleanupTask(taskId);
        
        return new ResponseEntity<>(videoData, headers, HttpStatus.OK);
    }
    
    @GetMapping("/api/video-info")
    @ResponseBody
    public ResponseEntity<?> getVideoInfo(@RequestParam("url") String videoUrl) {
        try {
            if (videoUrl == null || videoUrl.trim().isEmpty()) {
                Map<String, String> response = new HashMap<>();
                response.put("error", "视频URL不能为空");
                return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
            }

            String decodedUrl = URLDecoder.decode(videoUrl, StandardCharsets.UTF_8.name());
            Map<String, Object> videoInfo = youtubeDownloadService.getVideoInfo(decodedUrl);
            
            return new ResponseEntity<>(videoInfo, HttpStatus.OK);
        } catch (Exception e) {
            logger.error("获取视频信息时发生异常: {}", e.getMessage(), e);
            Map<String, String> response = new HashMap<>();
            response.put("error", "获取视频信息失败: " + e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
    

    
    private String extractVideoId(String url) {
        String videoId = "dQw4w9WgXcQ"; // 默认视频ID
        
        if (url.contains("v=")) {
            videoId = url.split("v=")[1];
            if (videoId.contains("&")) {
                videoId = videoId.split("&")[0];
            }
        } else if (url.contains("youtu.be/")) {
            videoId = url.split("youtu.be/")[1];
            if (videoId.contains("?")) {
                videoId = videoId.split("\\?")[0];
            }
        }
        
        return videoId;
    }
}