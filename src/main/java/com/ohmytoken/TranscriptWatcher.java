package com.ohmytoken;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 增量读取 ~/.claude/projects 下的会话 jsonl，把 usage 事件喂给 {@link TokenStats}。
 * 记录每个文件的读取偏移，每轮只解析新增内容。
 */
final class TranscriptWatcher {

    /** 同一请求按内容块拆成多行、每行重复携带同一份 usage，按 messageId|requestId 去重 */
    private static final int SEEN_IDS_CAPACITY = 20_000;

    private final Path dir;
    private final TokenStats stats;
    private final Map<Path, Long> offsets = new HashMap<>();
    /** LRU：重复行在文件中相邻出现，小规模缓存即可覆盖；access-order 自动淘汰最久未用 */
    private final LinkedHashMap<String, Boolean> seenIds =
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_IDS_CAPACITY;
                }
            };

    /** 文件清单刷新间隔：walk 整个目录树较贵，30s 一次足够发现新会话 */
    private static final long LIST_REFRESH_MILLIS = 30_000;

    /** 各文件上次读到时的 size，用于跳过未变化的文件 */
    private final Map<Path, Long> sizes = new HashMap<>();
    private volatile List<Path> files = List.of();
    private volatile long lastListMillis;

    TranscriptWatcher(Path dir, TokenStats stats) {
        this.dir = dir;
        this.stats = stats;
    }

    void scan() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastListMillis > LIST_REFRESH_MILLIS) {
            refreshFileList();
            lastListMillis = now;
        }
        for (Path path : files) {
            try {
                long size = Files.size(path);
                if (size == sizes.getOrDefault(path, -1L)) {
                    continue; // 没变化，不打开
                }
                if (size < offsets.getOrDefault(path, 0L)) {
                    offsets.put(path, 0L); // 文件被截断/轮转，从头读
                }
                readNew(path, size);
                sizes.put(path, size);
            } catch (IOException ignored) {
                // 文件可能被占用/删除，下轮再试
            }
        }
    }

    private void refreshFileList() {
        try (Stream<Path> paths = Files.walk(dir)) {
            files = paths.filter(p -> p.toString().endsWith(".jsonl")).toList();
        } catch (IOException ignored) {
            // 保留旧清单，下轮再试
        }
    }

    private void readNew(Path path, long length) {
        // ~/.claude/projects/<project-slug>/<session>.jsonl → 目录名即项目标识
        Path parent = path.getParent();
        String project = parent != null && parent.getFileName() != null
                ? parent.getFileName().toString() : null;
        long offset = offsets.getOrDefault(path, 0L);
        if (length == offset) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(offset);
            byte[] buf = new byte[(int) (length - offset)];
            raf.readFully(buf);

            // 只处理完整行（以 \n 结尾）；\n 不会出现在 UTF-8 多字节字符内部，按字节切行是安全的
            int lineStart = 0;
            for (int i = 0; i < buf.length; i++) {
                if (buf[i] == '\n') {
                    handleLine(new String(buf, lineStart, i - lineStart, StandardCharsets.UTF_8), project);
                    lineStart = i + 1;
                }
            }
            offsets.put(path, offset + lineStart);
        } catch (IOException ignored) {
            // 文件可能被占用/删除，下轮再试
        }
    }

    private void handleLine(String line, String project) {
        // 粗过滤，避免对无关行做 JSON 解析
        if (!line.contains("\"usage\"")) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(line).getAsJsonObject();
            JsonObject message = root.has("message") && root.get("message").isJsonObject()
                    ? root.getAsJsonObject("message") : null;
            JsonObject usage = message != null && message.has("usage") && message.get("usage").isJsonObject()
                    ? message.getAsJsonObject("usage") : null;
            if (usage == null) {
                return;
            }
            if (isDuplicate(root, message)) {
                return;
            }
            long input = getLong(usage, "input_tokens");
            long output = getLong(usage, "output_tokens");
            // 写入与读取分开记，趋势图按四类堆叠
            long cacheWrite = getLong(usage, "cache_creation_input_tokens");
            long cacheRead = getLong(usage, "cache_read_input_tokens");
            String model = message.has("model") && message.get("model").isJsonPrimitive()
                    ? message.get("model").getAsString() : null;

            Instant at = Instant.now();
            boolean timestamped = false;
            if (root.has("timestamp") && root.get("timestamp").isJsonPrimitive()) {
                try {
                    at = Instant.parse(root.get("timestamp").getAsString());
                    timestamped = true;
                } catch (Exception ignored) {
                    // 无合法时间戳，只计入总量
                }
            }
            stats.add(input, output, cacheWrite, cacheRead, model, project, at, timestamped);
        } catch (Exception ignored) {
            // 坏行跳过
        }
    }

    /** 提取 message.id / requestId 作为去重键；两者都缺失时不做去重 */
    private boolean isDuplicate(JsonObject root, JsonObject message) {
        String msgId = message.has("id") && message.get("id").isJsonPrimitive()
                ? message.get("id").getAsString() : null;
        String reqId = root.has("requestId") && root.get("requestId").isJsonPrimitive()
                ? root.get("requestId").getAsString() : null;
        if (msgId == null && reqId == null) {
            return false;
        }
        String key = (msgId == null ? "" : msgId) + '|' + (reqId == null ? "" : reqId);
        return seenIds.put(key, Boolean.TRUE) != null;
    }

    private static long getLong(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsLong() : 0L;
    }
}
