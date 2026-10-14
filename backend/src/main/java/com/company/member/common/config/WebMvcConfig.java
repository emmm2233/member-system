package com.company.member.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地文件访问配置
 *
 * <p>把 {@code member.storage.local-path} 目录映射到 {@code /files/**}，
 * 用于导入错误明细、导出文件、图片等静态资源访问。
 *
 * <p>生产环境建议改用对象存储（OSS/COS），此时把 {@code member.storage.type} 切为 oss 并移除本映射。
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${member.storage.local-path:./data/files}")
    private String localPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(localPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("[存储] 创建本地文件目录失败：{}", e.getMessage());
        }
        String location = dir.toUri().toString();
        registry.addResourceHandler("/files/**")
                .addResourceLocations(location)
                .setCachePeriod(600);
        log.info("[存储] 本地文件目录 {} 已映射到 /files/**", dir);
    }
}
