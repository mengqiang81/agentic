package com.example.agentic.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skill CRUD REST API。
 * <p>
 * Skill 四层合成优先级（从低到高）：
 * 1. 项目全局 projectGlobalSkillsDir
 * 2. Marketplace skillRepository（MySQL/Git/Nacos）
 * 3. 工作区 workspace/skills/
 * 4. 用户隔离 userId/skills/
 * <p>
 * 此 Controller 管理工作区级别的 Skill（第 3 层）。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final Path skillsDir;

    public SkillController(@org.springframework.beans.factory.annotation.Value("${agent.workspace:workspace}") String workspace) {
        this.skillsDir = Paths.get(workspace, "skills");
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create skills directory", e);
        }
    }

    /**
     * 列出当前工作区所有 Skill
     */
    @GetMapping
    public Mono<List<String>> listSkills() {
        return Mono.fromCallable(() -> {
            try (Stream<Path> paths = Files.list(skillsDir)) {
                return paths
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.toList());
            }
        });
    }

    /**
     * 上传新 Skill
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<String> createSkill(@RequestBody JsonNode body) {
        return Mono.fromCallable(() -> {
            String name = body.get("name").asText();
            String content = body.get("content").asText();
            Path skillFile = skillsDir.resolve(name + ".md");
            Files.writeString(skillFile, content);
            return "Skill '" + name + "' created";
        });
    }

    /**
     * 更新 Skill
     */
    @PutMapping("/{name}")
    public Mono<String> updateSkill(@PathVariable String name, @RequestBody JsonNode body) {
        return Mono.fromCallable(() -> {
            String content = body.get("content").asText();
            Path skillFile = skillsDir.resolve(name + ".md");
            if (!Files.exists(skillFile)) {
                throw new IllegalArgumentException("Skill '" + name + "' not found");
            }
            Files.writeString(skillFile, content);
            return "Skill '" + name + "' updated";
        });
    }

    /**
     * 删除 Skill
     */
    @DeleteMapping("/{name}")
    public Mono<String> deleteSkill(@PathVariable String name) {
        return Mono.fromCallable(() -> {
            Path skillFile = skillsDir.resolve(name + ".md");
            if (!Files.exists(skillFile)) {
                throw new IllegalArgumentException("Skill '" + name + "' not found");
            }
            Files.delete(skillFile);
            return "Skill '" + name + "' deleted";
        });
    }
}
