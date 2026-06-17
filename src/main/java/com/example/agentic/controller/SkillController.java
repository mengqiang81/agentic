package com.example.agentic.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.skill.util.SkillUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

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
 * <p>
 * 支持两种上传方式：
 * - 方案 A：JSON body（skillMd + resources map）
 * - 方案 B：ZIP 文件上传（multipart/form-data）
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final FileSystemSkillRepository repository;

    public SkillController(@Value("${agent.workspace:workspace}") String workspace) {
        Path skillsDir = Paths.get(workspace, "skills");
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create skills directory", e);
        }
        // writeable=true, source 标识来源
        this.repository = new FileSystemSkillRepository(skillsDir, true, "workspace");
    }

    // ==================== 查询 ====================

    /**
     * 列出当前工作区所有 Skill 名称
     */
    @GetMapping
    public Mono<List<String>> listSkills() {
        return Mono.fromCallable(repository::getAllSkillNames);
    }

    /**
     * 获取单个 Skill 详情（含 metadata、content、resource 路径列表）
     */
    @GetMapping("/{name}")
    public Mono<Map<String, Object>> getSkill(@PathVariable String name) {
        return Mono.fromCallable(() -> {
            AgentSkill skill = repository.getSkill(name);
            if (skill == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill '" + name + "' not found");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", skill.getName());
            result.put("description", skill.getDescription());
            result.put("metadata", skill.getMetadata());
            result.put("resourcePaths", skill.getResourcePaths());
            result.put("content", skill.getSkillContent());
            return result;
        });
    }

    // ==================== 方案 A：JSON 上传 ====================

    /**
     * 通过 JSON 创建/更新 Skill。
     * <pre>
     * POST /api/skills
     * {
     *   "skillMd": "---\nname: search\ndescription: ...\n---\n# Search\n...",
     *   "resources": {
     *     "references/api-doc.md": "# API Reference\n...",
     *     "scripts/search.py": "def search(query): ..."
     *   }
     * }
     * </pre>
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, String>> createSkillFromJson(@RequestBody JsonNode body) {
        return Mono.fromCallable(() -> {
            String skillMd = requireField(body, "skillMd");
            Map<String, String> resources = parseResources(body.get("resources"));

            AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
            repository.save(List.of(skill), true);

            return Map.of("message", "Skill '" + skill.getName() + "' created",
                    "name", skill.getName());
        });
    }

    // ==================== 方案 B：ZIP 上传 ====================

    /**
     * 通过 ZIP 文件上传 Skill（multipart/form-data，字段名 "file"）。
     * <p>
     * ZIP 内部结构须为标准 Skill 目录：
     * <pre>
     * skill-name/
     * ├── SKILL.md
     * ├── references/
     * ├── scripts/
     * └── ...
     * </pre>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, String>> createSkillFromZip(@RequestPart("file") FilePart filePart) {
        return filePart.content()
                .reduce(new byte[0], (acc, dataBuffer) -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    // 简单拼接（Skill ZIP 通常很小）
                    byte[] merged = new byte[acc.length + bytes.length];
                    System.arraycopy(acc, 0, merged, 0, acc.length);
                    System.arraycopy(bytes, 0, merged, acc.length, bytes.length);
                    return merged;
                })
                .flatMap(zipBytes -> Mono.fromCallable(() -> {
                    AgentSkill skill = SkillUtil.createFromZip(zipBytes);
                    repository.save(List.of(skill), true);
                    return Map.of("message", "Skill '" + skill.getName() + "' created from ZIP",
                            "name", skill.getName());
                }));
    }

    // ==================== 更新 ====================

    /**
     * 更新已有 Skill（JSON 方式，覆盖写入）
     */
    @PutMapping(value = "/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, String>> updateSkill(@PathVariable String name, @RequestBody JsonNode body) {
        return Mono.fromCallable(() -> {
            if (!repository.skillExists(name)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill '" + name + "' not found");
            }
            String skillMd = requireField(body, "skillMd");
            Map<String, String> resources = parseResources(body.get("resources"));

            AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
            repository.save(List.of(skill), true);

            return Map.of("message", "Skill '" + name + "' updated",
                    "name", skill.getName());
        });
    }

    // ==================== 删除 ====================

    /**
     * 删除 Skill（整个文件夹）
     */
    @DeleteMapping("/{name}")
    public Mono<Map<String, String>> deleteSkill(@PathVariable String name) {
        return Mono.fromCallable(() -> {
            if (!repository.skillExists(name)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill '" + name + "' not found");
            }
            repository.delete(name);
            return Map.of("message", "Skill '" + name + "' deleted");
        });
    }

    // ==================== 工具方法 ====================

    private String requireField(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Missing required field: " + field);
        }
        return node.asText();
    }

    private Map<String, String> parseResources(JsonNode resourcesNode) {
        if (resourcesNode == null || resourcesNode.isNull() || resourcesNode.isEmpty()) {
            return null;
        }
        Map<String, String> resources = new LinkedHashMap<>();
        resourcesNode.fields().forEachRemaining(entry ->
                resources.put(entry.getKey(), entry.getValue().asText()));
        return resources;
    }
}
