package com.cmcc.cmdevops.security.federation.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件 denylist：每行 {@code fingerprint\\trevokedAtMs}。多实例可挂同一路径（NFS/共享卷）。
 * 写入用临时文件 + move；读取在每次 isRevoked/size 时轻量 reload（适合中小规模 denylist）。
 */
public final class FileTokenRevocationStore implements TokenRevocationStore {

    private static final Logger log = LoggerFactory.getLogger(FileTokenRevocationStore.class);

    private final Path path;
    private final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public FileTokenRevocationStore(Path path) {
        this.path = path;
        reloadQuietly();
    }

    @Override
    public void revoke(String fingerprint, long revokedAtMs) {
        synchronized (lock) {
            reloadQuietly();
            cache.put(fingerprint, revokedAtMs);
            persist();
        }
    }

    @Override
    public boolean isRevoked(String fingerprint) {
        synchronized (lock) {
            reloadQuietly();
            return cache.containsKey(fingerprint);
        }
    }

    @Override
    public int size() {
        synchronized (lock) {
            reloadQuietly();
            return cache.size();
        }
    }

    private void reloadQuietly() {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            ConcurrentHashMap<String, Long> next = new ConcurrentHashMap<>();
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next().trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    next.put(line, 0L);
                    continue;
                }
                String fp = line.substring(0, tab);
                long at = 0L;
                try {
                    at = Long.parseLong(line.substring(tab + 1).trim());
                } catch (NumberFormatException ignored) {
                    // keep 0
                }
                next.put(fp, at);
            }
            cache.clear();
            cache.putAll(next);
        } catch (IOException e) {
            log.warn("Failed to reload revocation store {}: {}", path, e.toString());
        }
    }

    private void persist() {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            StringBuilder sb = new StringBuilder();
            sb.append("# fingerprint\trevokedAtMs\n");
            for (Map.Entry<String, Long> e : cache.entrySet()) {
                sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("Failed to persist revocation store {}: {}", path, e.toString());
            throw new IllegalStateException("无法写入吊销存储: " + path, e);
        }
    }
}
