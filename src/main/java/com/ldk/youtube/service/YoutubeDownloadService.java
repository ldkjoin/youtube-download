package com.ldk.youtube.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

@Service
public class YoutubeDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(YoutubeDownloadService.class);
    
    // 存储下载任务状态的并发Map
    private final ConcurrentHashMap<String, DownloadStatus> downloadTasks = new ConcurrentHashMap<>();
    
    // 临时文件目录
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");
    
    // 下载目录
    private static final String DOWNLOAD_DIR = "/Users/lidakai/Downloads";
    
    // 下载超时时间（分钟）
    private static final int DOWNLOAD_TIMEOUT_MINUTES = 10;
    
    /**
     * 异步下载YouTube视频
     * @param youtubeUrl YouTube视频URL
     * @param quality 视频质量（如：best, 720p, 1080p等）
     * @return 下载任务ID
     */
    @Async
    public ListenableFuture<String> downloadVideo(String youtubeUrl, String quality) {
        String taskId = UUID.randomUUID().toString();
        DownloadStatus status = new DownloadStatus(taskId, youtubeUrl, quality);
        downloadTasks.put(taskId, status);
        
        logger.info("开始下载任务 [{}]: URL={}, 质量={}", taskId, youtubeUrl, quality);
        
        try {
            // 检查系统环境信息
            logSystemEnvironment();
            
            // 检查yt-dlp命令是否可用
            if (!isYtDlpAvailable()) {
                status.setStatus("failed");
                String errorMsg = "yt-dlp命令不可用，请确保系统中已安装yt-dlp。安装方法：brew install yt-dlp 或 pip install yt-dlp";
                status.addError(errorMsg);
                logger.error("下载任务 [{}] 失败: {}", taskId, errorMsg);
                return new AsyncResult<>("[ERROR] " + errorMsg);
            }
            
            // 创建下载目录
            Path downloadOutputDir = Paths.get(DOWNLOAD_DIR, "youtube-downloads");
            Files.createDirectories(downloadOutputDir);
            logger.debug("创建下载目录: {}", downloadOutputDir);
            
            // 根据quality参数构建格式选择器
            String formatSelector = getFormatSelector(quality);
            logger.debug("使用格式选择器: {}", formatSelector);
            
            // 构建yt-dlp命令
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "yt-dlp",
                    "-f", formatSelector,
                    "--merge-output-format", "mp4",
                    "--socket-timeout", "30",
                    "--retries", "10",
                    "--user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "--cookies-from-browser", "chrome",
                    "--no-check-certificate",
                    "--geo-bypass",
                    "--verbose",
                    "-o", downloadOutputDir.resolve("%(title)s.%(ext)s").toString(),
                    youtubeUrl
            );
            
            logger.debug("执行命令: {}", String.join(" ", processBuilder.command()));
            
            // 启动进程
            Process process = processBuilder.start();
            status.setStatus("downloading");
            logger.info("下载进程已启动 [{}]", taskId);
            
            // 读取输出流
            new Thread(() -> {
                try {
                    readStreamToStatus(process.getInputStream(), status, true);
                } catch (IOException e) {
                    status.addError("读取输出流错误: " + e.getMessage());
                }
            }).start();
            
            // 读取错误流
            new Thread(() -> {
                try {
                    readStreamToStatus(process.getErrorStream(), status, false);
                } catch (IOException e) {
                    status.addError("读取错误流错误: " + e.getMessage());
                }
            }).start();
            
            // 等待进程完成，设置超时
            boolean completed = process.waitFor(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            
            if (!completed) {
                process.destroyForcibly();
                status.setStatus("failed");
                status.addError("下载超时");
                logger.error("下载任务 [{}] 超时", taskId);
                return new AsyncResult<>("[ERROR] 下载超时");
            }
            
            if (process.exitValue() != 0) {
                status.setStatus("failed");
                
                // 检查是否有特定错误信息
                String errorDetails = status.getError();
                String userFriendlyMessage = "下载失败，退出码: " + process.exitValue();
                
                // 根据错误信息提供更友好的提示
                if (errorDetails.contains("HTTP Error 403") || errorDetails.contains("Forbidden")) {
                    userFriendlyMessage = "下载失败：YouTube拒绝访问(HTTP 403)，可能是由于地区限制或内容保护。请尝试：\n" +
                                          "1. 使用VPN或代理服务器\n" +
                                          "2. 确认视频在您的地区可以访问\n" +
                                          "3. 尝试其他视频或稍后再试";
                } else if (errorDetails.contains("unavailable") || errorDetails.contains("不可用")) {
                    userFriendlyMessage = "下载失败：视频不可用，可能已被删除或设为私有";
                } else if (errorDetails.contains("copyright") || errorDetails.contains("版权")) {
                    userFriendlyMessage = "下载失败：视频可能受版权保护，无法下载";
                }
                
                status.addError(userFriendlyMessage);
                logger.error("下载任务 [{}] 失败，退出码: {}，详细信息: {}", taskId, process.exitValue(), userFriendlyMessage);
                return new AsyncResult<>("[ERROR] " + userFriendlyMessage);
            }
            
            // 查找下载的文件
            File[] files = downloadOutputDir.toFile().listFiles((dir, name) -> name.endsWith(".mp4"));
            if (files == null || files.length == 0) {
                status.setStatus("failed");
                status.addError("找不到下载的视频文件");
                logger.error("下载任务 [{}] 失败：找不到下载的视频文件", taskId);
                return new AsyncResult<>("[ERROR] 找不到下载的视频文件");
            }
            
            // 检查文件大小
            long fileSize = files[0].length();
            if (fileSize < 1024) { // 小于1KB的文件可能是无效的
                status.setStatus("failed");
                status.addError("下载的视频文件过小，可能是无效文件: " + fileSize + " 字节");
                logger.error("下载任务 [{}] 失败：文件过小 ({} 字节)", taskId, fileSize);
                return new AsyncResult<>("[ERROR] 下载的视频文件过小，可能是无效文件");
            }
            
            // 设置下载完成状态
            status.setStatus("completed");
            // 确保进度为100%
            status.setProgress(100.0f);
            status.setOutputFile(files[0].getAbsolutePath());
            
            // 打印更详细的文件保存信息
            String fileName = files[0].getName();
            String absolutePath = files[0].getAbsolutePath();
            String canonicalPath = files[0].getCanonicalPath();
            
            logger.info("下载任务 [{}] 完成:", taskId);
            logger.info("  - 文件名称: {}", fileName);
            logger.info("  - 文件大小: {:.2f} MB", fileSize / (1024.0 * 1024.0));
            logger.info("  - 绝对路径: {}", absolutePath);
            logger.info("  - 规范路径: {}", canonicalPath);
            logger.info("  - 临时目录: {}", TEMP_DIR);
            
            // 检查文件是否可读
            if (files[0].canRead()) {
                logger.info("  - 文件可读: 是");
            } else {
                logger.warn("  - 文件可读: 否，可能无法正常访问");
            }
            
            return new AsyncResult<>(taskId);
            
        } catch (Exception e) {
            status.setStatus("failed");
            String errorMsg = String.format("下载异常: %s (类型: %s)", e.getMessage(), e.getClass().getName());
            status.addError(errorMsg);
            status.addError("堆栈信息: " + getStackTraceAsString(e));
            logger.error("下载任务 [{}] 异常: {} (类型: {})", taskId, e.getMessage(), e.getClass().getName(), e);
            return new AsyncResult<>("[ERROR] " + errorMsg);
        }
    }
    
    /**
     * 获取下载任务状态
     * @param taskId 任务ID
     * @return 下载状态对象，如果不存在返回null
     */
    public DownloadStatus getDownloadStatus(String taskId) {
        logger.debug("获取下载任务 [{}] 状态", taskId);
        return downloadTasks.get(taskId);
    }
    
    /**
     * 获取已下载的视频文件
     * @param taskId 任务ID
     * @return 视频文件的字节数组，如果任务不存在或未完成则返回null
     */
    public byte[] getDownloadedVideo(String taskId) {
        DownloadStatus status = downloadTasks.get(taskId);
        if (status == null || !"completed".equals(status.getStatus()) || status.getOutputFile() == null) {
            logger.warn("获取下载视频失败 [{}]: 任务不存在或未完成", taskId);
            return null;
        }
        
        try {
            File videoFile = new File(status.getOutputFile());
            long fileSize = videoFile.length();
            
            logger.info("读取下载视频文件 [{}]:", taskId);
            logger.info("  - 文件路径: {}", status.getOutputFile());
            logger.info("  - 文件大小: {:.2f} MB", fileSize / (1024.0 * 1024.0));
            logger.info("  - 文件存在: {}", videoFile.exists() ? "是" : "否");
            logger.info("  - 文件可读: {}", videoFile.canRead() ? "是" : "否");
            
            byte[] data = Files.readAllBytes(Paths.get(status.getOutputFile()));
            logger.info("  - 读取完成: 成功读取 {:.2f} MB 数据", data.length / (1024.0 * 1024.0));
            
            return data;
        } catch (IOException e) {
            status.addError("读取文件错误: " + e.getMessage());
            logger.error("读取下载视频文件失败 [{}]: {}", taskId, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 清理下载任务
     * @param taskId 任务ID
     */
    public void cleanupTask(String taskId) {
        logger.info("清理下载任务 [{}]", taskId);
        DownloadStatus status = downloadTasks.remove(taskId);
        
        // 不删除下载的文件，只记录日志
        if (status != null && status.getOutputFile() != null) {
            logger.info("下载文件保留在: {}", status.getOutputFile());
        }
    }
    
    /**
     * 检查yt-dlp命令是否可用
     * @return 如果命令可用返回true，否则返回false
     */
    private boolean isYtDlpAvailable() {
        try {
            Process process = new ProcessBuilder("which", "yt-dlp").start();
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                logger.error("yt-dlp命令不可用，请确保已正确安装。可通过以下命令安装：brew install yt-dlp 或 pip install yt-dlp");
                // 检查Python环境
                checkPythonEnvironment();
                return false;
            }
            
            // 检查yt-dlp版本
            Process versionProcess = new ProcessBuilder("yt-dlp", "--version").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(versionProcess.getInputStream()))) {
                String version = reader.readLine();
                logger.info("检测到yt-dlp版本: {}", version);
            }
            
            return true;
        } catch (Exception e) {
            logger.error("检查yt-dlp命令失败: {}, 异常类型: {}, 堆栈信息: {}", 
                    e.getMessage(), e.getClass().getName(), getStackTraceAsString(e));
            return false;
        }
    }
    
    /**
     * 检查Python环境
     */
    private void checkPythonEnvironment() {
        try {
            // 检查Python版本
            Process pythonProcess = new ProcessBuilder("python", "--version").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    pythonProcess.getInputStream()))) {
                String pythonVersion = reader.readLine();
                logger.info("Python版本: {}", pythonVersion);
            }
            
            // 检查pip版本
            Process pipProcess = new ProcessBuilder("pip", "--version").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    pipProcess.getInputStream()))) {
                String pipVersion = reader.readLine();
                logger.info("pip版本: {}", pipVersion);
            }
            
            // 检查已安装的相关包
            Process pipListProcess = new ProcessBuilder("pip", "list").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    pipListProcess.getInputStream()))) {
                String line;
                logger.info("已安装的相关Python包:");
                while ((line = reader.readLine()) != null) {
                    if (line.contains("yt-dlp") || line.contains("youtube")) {
                        logger.info("  {}", line);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("检查Python环境失败: {}", e.getMessage());
        }
    }
    
    /**
     * 获取异常堆栈信息字符串
     */
    private String getStackTraceAsString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }
    
    /**
     * 记录系统环境信息
     */
    private void logSystemEnvironment() {
        try {
            logger.info("系统环境信息:");
            logger.info("  操作系统: {} {}", System.getProperty("os.name"), System.getProperty("os.version"));
            logger.info("  Java版本: {}", System.getProperty("java.version"));
            logger.info("  临时目录: {}", TEMP_DIR);
            
            // 检查常用命令
            checkCommand("yt-dlp");
            checkCommand("python");
            checkCommand("pip");
            checkCommand("ffmpeg");
            
            // 检查磁盘空间
            File tempDir = new File(TEMP_DIR);
            long freeSpace = tempDir.getFreeSpace() / (1024 * 1024);
            logger.info("  临时目录可用空间: {} MB", freeSpace);
            
        } catch (Exception e) {
            logger.error("记录系统环境信息失败: {}", e.getMessage());
        }
    }
    
    /**
     * 检查命令是否可用并记录版本信息
     */
    private void checkCommand(String command) {
        try {
            Process process = new ProcessBuilder("which", command).start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                // 尝试获取版本信息
                try {
                    Process versionProcess = new ProcessBuilder(command, "--version").start();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(versionProcess.getInputStream()))) {
                        String version = reader.readLine();
                        logger.info("  {} 版本: {}", command, version);
                    }
                } catch (Exception e) {
                    logger.info("  {} 已安装，但无法获取版本信息: {}", command, e.getMessage());
                }
            } else {
                logger.warn("  {} 命令不可用", command);
            }
        } catch (Exception e) {
            logger.warn("  检查 {} 命令失败: {}", command, e.getMessage());
        }
    }
    
    /**
     * 根据质量参数获取yt-dlp格式选择器
     */
    private String getFormatSelector(String quality) {
        switch (quality) {
            case "1080p":
                return "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best";
            case "720p":
                return "bestvideo[height<=720]+bestaudio/best[height<=720]/best";
            case "480p":
                return "bestvideo[height<=480]+bestaudio/best[height<=480]/best";
            case "360p":
                return "bestvideo[height<=360]+bestaudio/best[height<=360]/best";
            case "240p":
                return "bestvideo[height<=240]+bestaudio/best[height<=240]/best";
            case "best":
            default:
                return "best";
        }
    }
    
    /**
     * 读取流并更新状态
     */
    private void readStreamToStatus(InputStream inputStream, DownloadStatus status, boolean isOutput) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isOutput) {
                    // 标准输出流处理逻辑保持不变
                    status.addOutput(line);
                    // 记录输出日志
                    logger.debug("任务 [{}] 输出: {}", status.getTaskId(), line);
                    
                    // 解析下载进度
                    if (line.contains("% of")) {
                        try {
                            // 提取进度百分比
                            String progressStr = line.substring(line.indexOf("[download]") + 11, line.indexOf("% of")).trim();
                            float progress = Float.parseFloat(progressStr);
                            
                            // 更新进度
                            status.setProgress(progress);
                            logger.debug("任务 [{}] 进度: {}%", status.getTaskId(), progress);
                            
                            // 如果进度接近100%但还没完成，设置为99.9%，留出完成时设为100%的空间
                            if (progress > 99.9 && !"completed".equals(status.getStatus())) {
                                status.setProgress(99.9f);
                            }
                        } catch (Exception e) {
                            // 记录解析错误详情
                            logger.warn("解析进度失败: {} (错误: {})", line, e.getMessage());
                        }
                    }
                    
                    // 检测下载完成的标志
                    if (line.contains("Merging formats into") || 
                        line.contains("has already been downloaded") || 
                        line.contains("Destination:")) {
                        // 这些消息通常表示下载已经完成或接近完成
                        if (status.getProgress() < 99) {
                            status.setProgress(99.0f);
                            logger.debug("任务 [{}] 检测到下载接近完成，设置进度为99%", status.getTaskId());
                        }
                    }
                    
                    // 检测可能的错误信息
                    if (line.contains("ERROR") || line.contains("Error") || line.contains("错误") || 
                            line.contains("失败") || line.contains("Failed") || line.contains("failed")) {
                        logger.error("任务 [{}] 检测到可能的错误: {}", status.getTaskId(), line);
                        status.addError("检测到错误: " + line);
                    }
                    
                    // 检测依赖包信息
                    if (line.contains("Requirement") || line.contains("package") || 
                            line.contains("module") || line.contains("dependency")) {
                        logger.info("任务 [{}] 依赖包信息: {}", status.getTaskId(), line);
                    }
                } else {
                    status.addError(line);
                    logger.warn("任务 [{}] 错误流输出: {}", status.getTaskId(), line);
                    
                    // 分析错误类型
                    if (line.contains("No such file") || line.contains("not found") || line.contains("找不到")) {
                        logger.error("任务 [{}] 文件或命令未找到错误: {}", status.getTaskId(), line);
                    } else if (line.contains("Permission") || line.contains("权限")) {
                        logger.error("任务 [{}] 权限错误: {}", status.getTaskId(), line);
                    } else if (line.contains("network") || line.contains("网络") || 
                              line.contains("connection") || line.contains("连接")) {
                        logger.error("任务 [{}] 网络连接错误: {}", status.getTaskId(), line);
                    } else if (line.contains("module") || line.contains("package") || 
                              line.contains("依赖") || line.contains("模块")) {
                        logger.error("任务 [{}] 依赖包错误: {}", status.getTaskId(), line);
                    } else if (line.contains("HTTP Error 403") || line.contains("Forbidden")) {
                        String errorMsg = "访问被拒绝(HTTP 403)，可能是YouTube限制了访问或需要验证。尝试使用不同的网络环境或更新yt-dlp";
                        status.addError(errorMsg);
                        logger.error("任务 [{}] 访问被拒绝: {}", status.getTaskId(), errorMsg);
                    }
                    
                    // 根据内容区分日志级别
                    if (line.startsWith("[debug]")) {
                        // 调试信息使用debug级别
                        logger.debug("任务 [{}] 调试信息: {}", status.getTaskId(), line);
                    } else if (line.contains("WARNING") || line.contains("Warning") || line.contains("警告")) {
                        // 警告信息使用warn级别
                        logger.warn("任务 [{}] 警告: {}", status.getTaskId(), line);
                    } else if (line.contains("ERROR") || line.contains("Error") || line.contains("错误") || 
                              line.contains("Failed") || line.contains("failed") || line.contains("失败")) {
                        // 错误信息使用error级别
                        logger.error("任务 [{}] 错误: {}", status.getTaskId(), line);
                    } else {
                        // 其他错误流输出使用debug级别
                        logger.debug("任务 [{}] 错误流: {}", status.getTaskId(), line);
                    }
                    
                    // 检测可能的错误信息
                    if (line.contains("ERROR") || line.contains("Error") || line.contains("错误") || 
                            line.contains("失败") || line.contains("Failed") || line.contains("failed")) {
                        logger.error("任务 [{}] 检测到可能的错误: {}", status.getTaskId(), line);
                        status.addError("检测到错误: " + line);
                    }
                    
                    // 检测依赖包信息
                    if (line.contains("Requirement") || line.contains("package") || 
                            line.contains("module") || line.contains("dependency")) {
                        logger.info("任务 [{}] 依赖包信息: {}", status.getTaskId(), line);
                    }
                }
            }
        }
    }
    
    /**
     * 下载状态类
     */
    public static class DownloadStatus {
        private final String taskId;
        private final String youtubeUrl;
        private final String quality;
        private String status; // pending, downloading, completed, failed
        private float progress;
        private final StringBuilder output;
        private final StringBuilder error;
        private String outputFile;
        private final long startTime;
        
        public DownloadStatus(String taskId, String youtubeUrl, String quality) {
            this.taskId = taskId;
            this.youtubeUrl = youtubeUrl;
            this.quality = quality;
            this.status = "pending";
            this.progress = 0;
            this.output = new StringBuilder();
            this.error = new StringBuilder();
            this.startTime = System.currentTimeMillis();
        }
        
        public String getTaskId() {
            return taskId;
        }
        
        public String getYoutubeUrl() {
            return youtubeUrl;
        }
        
        public String getQuality() {
            return quality;
        }
        
        public String getStatus() {
            return status;
        }
        
        public void setStatus(String status) {
            this.status = status;
        }
        
        public float getProgress() {
            return progress;
        }
        
        public void setProgress(float progress) {
            this.progress = progress;
        }
        
        public void addOutput(String line) {
            output.append(line).append("\n");
        }
        
        public void addError(String line) {
            error.append(line).append("\n");
        }
        
        public String getOutput() {
            return output.toString();
        }
        
        public String getError() {
            return error.toString();
        }
        
        public String getOutputFile() {
            return outputFile;
        }
        
        public void setOutputFile(String outputFile) {
            this.outputFile = outputFile;
        }
        
        public long getElapsedTimeMs() {
            return System.currentTimeMillis() - startTime;
        }
    }

    /**
     * 格式化视频时长
     * @param seconds 视频时长（秒）
     * @return 格式化后的时长字符串
     */
    private String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        } else {
            return String.format("%02d:%02d", minutes, secs);
        }
    }

    /**
     * 格式化发布日期
     * @param dateStr 日期字符串（格式：YYYYMMDD）
     * @return 格式化后的日期字符串
     */
    private String formatDate(String dateStr) {
        if (dateStr == null || dateStr.length() != 8) {
            return "未知";
        }
        try {
            LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
            return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        } catch (Exception e) {
            logger.warn("日期格式化失败: {}", dateStr);
            return "未知";
        }
    }

    /**
     * 获取视频信息
     * @param url YouTube视频URL
     * @return 包含视频信息的Map
     */
    public Map<String, Object> getVideoInfo(String url) throws Exception {
        // 检查yt-dlp命令是否可用
        if (!isYtDlpAvailable()) {
            throw new RuntimeException("yt-dlp命令不可用，请确保系统中已安装yt-dlp");
        }

        // 构建命令
        ProcessBuilder processBuilder = new ProcessBuilder(
            "yt-dlp",
            "-j",  // 输出JSON格式
            "--no-playlist",  // 不处理播放列表
            url
        );
        
        logger.info("开始获取视频信息: {}", url);
        Process process = processBuilder.start();
        
        // 读取输出
        String jsonOutput = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
            .lines()
            .collect(Collectors.joining("\n"));
        
        // 等待进程完成
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String errorOutput = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));
            logger.error("获取视频信息失败: {}", errorOutput);
            throw new RuntimeException("获取视频信息失败: " + errorOutput);
        }
        
        // 解析JSON响应
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonOutput);
        
        // 构建视频信息
        Map<String, Object> videoInfo = new HashMap<>();
        videoInfo.put("id", rootNode.get("id").asText());
        videoInfo.put("title", rootNode.get("title").asText());
        videoInfo.put("author", rootNode.get("uploader").asText());
        videoInfo.put("duration", formatDuration(rootNode.get("duration").asLong()));
        videoInfo.put("publishDate", formatDate(rootNode.get("upload_date").asText()));
        videoInfo.put("thumbnail", rootNode.get("thumbnail").asText());
        
        // 处理视频格式
        List<Map<String, Object>> formats = new ArrayList<>();
        Map<String, Map<String, Object>> qualityMap = new HashMap<>(); // 用于存储每个清晰度对应的最佳格式
        
        JsonNode formatsNode = rootNode.get("formats");
        if (formatsNode != null && formatsNode.isArray()) {
            for (JsonNode format : formatsNode) {
                // 只添加包含视频的格式
                if (format.has("vcodec") && !"none".equals(format.get("vcodec").asText())) {
                    String quality = format.has("height") ? format.get("height").asText() + "p" : "unknown";
                    
                    // 创建格式信息Map
                    Map<String, Object> formatInfo = new HashMap<>();
                    formatInfo.put("itag", format.get("format_id").asText());
                    formatInfo.put("qualityLabel", quality);
                    
                    // 处理文件大小信息
                    Long fileSize = null;
                    if (format.has("filesize") && !format.get("filesize").isNull()) {
                        fileSize = format.get("filesize").asLong();
                        formatInfo.put("contentLength", fileSize);
                    } else {
                        // 如果没有文件大小，设置为null，但不跳过
                        formatInfo.put("contentLength", null);
                    }
                    
                    formatInfo.put("container", format.get("ext").asText());
                    formatInfo.put("fps", format.has("fps") ? format.get("fps").asText() : null);
                    formatInfo.put("audioQuality", format.has("asr") ? format.get("asr").asText() + "Hz" : null);
                    formatInfo.put("videoCodec", format.get("vcodec").asText());
                    formatInfo.put("audioCodec", format.has("acodec") ? format.get("acodec").asText() : null);
                    
                    // 检查是否已存在相同清晰度的格式
                    if (qualityMap.containsKey(quality)) {
                        Map<String, Object> existingFormat = qualityMap.get(quality);
                        Object existingSizeObj = existingFormat.get("contentLength");
                        
                        // 如果现有格式没有文件大小但新格式有，则替换
                        if (existingSizeObj == null && fileSize != null) {
                            qualityMap.put(quality, formatInfo);
                            logger.debug("替换清晰度 {} 的格式：新格式有文件大小 {} MB，原格式无文件大小", 
                                quality, fileSize / (1024.0 * 1024.0));
                        } 
                        // 如果两者都有文件大小，比较大小
                        else if (existingSizeObj != null && fileSize != null) {
                            long existingSize = (Long) existingSizeObj;
                            if (fileSize > existingSize) {
                                qualityMap.put(quality, formatInfo);
                                logger.debug("替换清晰度 {} 的格式，新文件大小: {} MB，原文件大小: {} MB", 
                                    quality, fileSize / (1024.0 * 1024.0), existingSize / (1024.0 * 1024.0));
                            }
                        }
                        // 如果新格式没有文件大小但现有格式有，保留现有格式
                    } else {
                        // 如果是新的清晰度，直接添加
                        qualityMap.put(quality, formatInfo);
                        if (fileSize != null) {
                            logger.debug("添加新清晰度 {} 的格式，文件大小: {} MB", 
                                quality, fileSize / (1024.0 * 1024.0));
                        } else {
                            logger.debug("添加新清晰度 {} 的格式，文件大小未知", quality);
                        }
                    }
                }
            }
        }
        
        // 将筛选后的格式添加到最终列表
        formats.addAll(qualityMap.values());
        
        // 按清晰度降序排序
        formats.sort((a, b) -> {
            String qualityA = (String) a.get("qualityLabel");
            String qualityB = (String) b.get("qualityLabel");
            if (qualityA.equals("unknown") || qualityB.equals("unknown")) {
                return 0;
            }
            int heightA = Integer.parseInt(qualityA.replace("p", ""));
            int heightB = Integer.parseInt(qualityB.replace("p", ""));
            return Integer.compare(heightB, heightA);
        });
        
        videoInfo.put("formats", formats);
        logger.info("成功获取视频信息: {} ({})", videoInfo.get("title"), videoInfo.get("id"));
        
        return videoInfo;
    }
}