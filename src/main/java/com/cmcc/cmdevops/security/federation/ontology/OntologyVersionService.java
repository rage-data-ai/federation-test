package com.cmcc.cmdevops.security.federation.ontology;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Snapshot;

import tools.jackson.databind.ObjectMapper;

/**
 * 本体版本快照：将 {@link Snapshot} 序列化为 JSON 文件，支持列表与回滚。
 * 解决「起草即生效、无版本」的运维风险。
 */
@Service
public class OntologyVersionService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final OntologyRepository repository;
    private final ObjectMapper objectMapper;
    private final Path versionsDir;

    public OntologyVersionService(
            OntologyRepository repository,
            ObjectMapper objectMapper,
            FederationProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.versionsDir = resolveDir(properties);
    }

    OntologyVersionService(OntologyRepository repository, ObjectMapper objectMapper, Path versionsDir) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.versionsDir = versionsDir;
    }

    public VersionInfo create(String label) {
        try {
            Files.createDirectories(versionsDir);
            String id = STAMP.format(Instant.now()) + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt());
            if (label != null && !label.isBlank()) {
                String safe = label.strip().replaceAll("[^\\w\\u4e00-\\u9fff.-]+", "_");
                if (!safe.isBlank()) {
                    id = id + "-" + safe;
                }
            }
            Path file = versionsDir.resolve(id + ".json");
            Snapshot snapshot = repository.snapshot();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot);
            return infoOf(file, snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("无法写入本体版本: " + e.getMessage(), e);
        }
    }

    public List<VersionInfo> list() {
        if (!Files.isDirectory(versionsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(versionsDir)) {
            List<VersionInfo> versions = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                    .forEach(path -> {
                        try {
                            Snapshot snap = objectMapper.readValue(path.toFile(), Snapshot.class);
                            versions.add(infoOf(path, snap));
                        } catch (Exception ignored) {
                            versions.add(new VersionInfo(stripExt(path.getFileName().toString()),
                                    path.getFileName().toString(), 0, 0, 0, 0, 0, "unreadable"));
                        }
                    });
            return versions;
        } catch (IOException e) {
            throw new IllegalStateException("无法列出本体版本: " + e.getMessage(), e);
        }
    }

    public VersionInfo restore(String id) {
        Path file = resolveId(id);
        try {
            Snapshot snapshot = objectMapper.readValue(file.toFile(), Snapshot.class);
            repository.replaceSnapshot(snapshot);
            return infoOf(file, snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取本体版本: " + e.getMessage(), e);
        }
    }

    public Snapshot read(String id) {
        Path file = resolveId(id);
        try {
            return objectMapper.readValue(file.toFile(), Snapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取本体版本: " + e.getMessage(), e);
        }
    }

    private Path resolveId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("版本 id 不能为空");
        }
        String name = id.strip();
        if (!name.endsWith(".json")) {
            name = name + ".json";
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("非法版本 id");
        }
        Path file = versionsDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("版本不存在: " + id);
        }
        return file;
    }

    private static VersionInfo infoOf(Path file, Snapshot snapshot) {
        return new VersionInfo(
                stripExt(file.getFileName().toString()),
                file.getFileName().toString(),
                size(snapshot.concepts()),
                size(snapshot.attributes()),
                size(snapshot.relations()),
                size(snapshot.codes()),
                size(snapshot.definedConcepts()),
                null);
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static String stripExt(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".json")
                ? name.substring(0, name.length() - 5)
                : name;
    }

    private static Path resolveDir(FederationProperties properties) {
        String configured = properties.getOntologyVersions();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        Path store = Path.of(properties.getOntologyStore());
        Path parent = store.getParent();
        return parent == null ? Path.of("data/ontology-versions") : parent.resolve("ontology-versions");
    }

    public record VersionInfo(
            String id,
            String fileName,
            int concepts,
            int attributes,
            int relations,
            int codes,
            int definedConcepts,
            String note) {
    }
}
