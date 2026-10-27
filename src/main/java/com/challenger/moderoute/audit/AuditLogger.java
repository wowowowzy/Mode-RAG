package com.challenger.moderoute.audit;

import com.challenger.moderoute.config.ModeRouteProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Map;

/**
 * 每请求一行 JSONL。没有「理由」字段——决策模型不生成文本，给不出理由，
 * 可审计的证据就是概率分布本身。
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final Path dir;
    private final boolean enabled;
    private final ObjectMapper mapper;

    public AuditLogger(ModeRouteProperties properties, ObjectMapper mapper) {
        this.dir = Path.of(properties.getAudit().getDir()).toAbsolutePath().normalize();
        this.enabled = properties.getAudit().isEnabled();
        this.mapper = mapper;
    }

    public void record(Map<String, Object> event) {
        if (!enabled) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("audit-" + LocalDate.now() + ".jsonl");
            String line = mapper.writeValueAsString(event) + "\n";
            synchronized (this) {
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.warn("审计日志写入失败（不影响响应）: {}", e.getMessage());
        }
    }
}
