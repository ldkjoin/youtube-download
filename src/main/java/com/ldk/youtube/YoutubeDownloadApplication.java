package com.ldk.youtube;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.Executor;

@SpringBootApplication
@EnableAsync
public class YoutubeDownloadApplication {

    public static void main(String[] args) {
        SpringApplication.run(YoutubeDownloadApplication.class, args);
    }
    
    /**
     * 配置异步任务执行器
     * 用于处理并发的视频下载任务
     */
    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数
        executor.setCorePoolSize(5);
        // 最大线程数
        executor.setMaxPoolSize(10);
        // 队列容量
        executor.setQueueCapacity(25);
        // 线程名前缀
        executor.setThreadNamePrefix("youtube-downloader-");
        executor.initialize();
        return executor;
    }

}