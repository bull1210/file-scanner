package com.filescanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filescanner.entity.AuditEvent;
import com.filescanner.repository.AuditEventRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.file.StandardWatchEventKinds.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class WatchMonitorService {

    private final AuditEventRepository auditRepo;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, WatchService>        activeWatchers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>>           watchTasks     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<SseEmitter>>    sseEmitters    = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isMonitoring(String folderPath) {
        return activeWatchers.containsKey(folderPath);
    }

    public Set<String> getMonitoredPaths() {
        return Collections.unmodifiableSet(activeWatchers.keySet());
    }

    public SseEmitter subscribe(String folderPath) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        sseEmitters.computeIfAbsent(folderPath, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(folderPath, emitter));
        emitter.onTimeout(()    -> removeEmitter(folderPath, emitter));
        emitter.onError(e       -> removeEmitter(folderPath, emitter));
        return emitter;
    }

    public void startMonitoring(String folderPath) throws IOException {
        if (activeWatchers.containsKey(folderPath)) return;

        Path root = Paths.get(folderPath);
        if (!Files.isDirectory(root)) throw new IOException("Not a directory: " + folderPath);

        WatchService watcher = FileSystems.getDefault().newWatchService();
        registerRecursive(root, watcher);
        activeWatchers.put(folderPath, watcher);

        Future<?> task = executor.submit(() -> runWatchLoop(folderPath, root, watcher));
        watchTasks.put(folderPath, task);
        log.info("Started monitoring: {}", folderPath);
    }

    public void stopMonitoring(String folderPath) {
        WatchService ws = activeWatchers.remove(folderPath);
        if (ws != null) {
            try { ws.close(); } catch (IOException ignored) {}
        }
        Future<?> task = watchTasks.remove(folderPath);
        if (task != null) task.cancel(true);

        List<SseEmitter> emitters = sseEmitters.remove(folderPath);
        if (emitters != null) emitters.forEach(SseEmitter::complete);
        log.info("Stopped monitoring: {}", folderPath);
    }

    @PreDestroy
    public void shutdown() {
        new ArrayList<>(activeWatchers.keySet()).forEach(this::stopMonitoring);
        executor.shutdown();
    }

    // -------------------------------------------------------------------------
    // Watch loop
    // -------------------------------------------------------------------------

    private void runWatchLoop(String folderPath, Path root, WatchService watcher) {
        // Buffer: path → delete timestamp (ms) for rename detection
        LinkedHashMap<String, Long> pendingDeletes = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 100; }
        };

        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }

            for (WatchEvent<?> raw : key.pollEvents()) {
                if (raw.kind() == OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                WatchEvent<Path> event = (WatchEvent<Path>) raw;
                Path dir      = (Path) key.watchable();
                Path fullPath = dir.resolve(event.context());
                String filePath = fullPath.toString();
                LocalDateTime now = LocalDateTime.now();

                String eventType;
                String oldPath = null;

                if (event.kind() == ENTRY_CREATE) {
                    String matched = findRenameCandidate(pendingDeletes, filePath);
                    if (matched != null) {
                        eventType = "RENAMED";
                        oldPath = matched;
                        pendingDeletes.remove(matched);
                    } else {
                        eventType = "CREATED";
                    }
                    // Track new sub-directories
                    if (Files.isDirectory(fullPath)) {
                        try { registerRecursive(fullPath, watcher); }
                        catch (IOException e) { log.warn("Cannot register new dir {}", fullPath); }
                    }
                } else if (event.kind() == ENTRY_DELETE) {
                    pendingDeletes.put(filePath, System.currentTimeMillis());
                    eventType = "DELETED";
                } else {
                    eventType = "MODIFIED";
                }

                // Flush stale pending deletes (> 400 ms old) so they get saved properly
                long cutoff = System.currentTimeMillis() - 400;
                pendingDeletes.entrySet().removeIf(e -> e.getValue() < cutoff);

                AuditEvent ae = buildEvent(folderPath, filePath, oldPath, eventType, "WATCHSERVICE", now);
                try {
                    ae = auditRepo.save(ae);
                } catch (Exception ex) {
                    log.warn("Could not persist audit event: {}", ex.getMessage());
                }
                broadcast(folderPath, ae);
            }

            if (!key.reset()) break;
        }

        activeWatchers.remove(folderPath);
        log.info("Watch loop ended for {}", folderPath);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void registerRecursive(Path root, WatchService watcher) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                try {
                    dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                } catch (IOException e) {
                    log.warn("Cannot watch {}: {}", dir, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Looks for a recent DELETE in the same parent directory (rename heuristic). */
    private String findRenameCandidate(Map<String, Long> pending, String newPath) {
        long cutoff = System.currentTimeMillis() - 400;
        String newParent = Paths.get(newPath).getParent().toString();
        return pending.entrySet().stream()
            .filter(e -> e.getValue() >= cutoff)
            .filter(e -> {
                Path p = Paths.get(e.getKey()).getParent();
                return p != null && p.toString().equals(newParent);
            })
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    private AuditEvent buildEvent(String folder, String filePath, String oldPath,
                                   String type, String source, LocalDateTime time) {
        AuditEvent ae = new AuditEvent();
        ae.setFolderPath(folder);
        ae.setFilePath(filePath);
        ae.setOldPath(oldPath);
        ae.setEventType(type);
        ae.setSource(source);
        ae.setOccurredAt(time);
        return ae;
    }

    private void broadcast(String folderPath, AuditEvent ae) {
        List<SseEmitter> list = sseEmitters.getOrDefault(folderPath, List.of());
        if (list.isEmpty()) return;

        List<SseEmitter> dead = new ArrayList<>();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                "id",          ae.getId() != null ? ae.getId() : 0L,
                "filePath",    ae.getFilePath() != null ? ae.getFilePath() : "",
                "oldPath",     ae.getOldPath() != null ? ae.getOldPath() : "",
                "eventType",   ae.getEventType() != null ? ae.getEventType() : "",
                "source",      ae.getSource() != null ? ae.getSource() : "",
                "userName",    ae.getUserName() != null ? ae.getUserName() : "",
                "accessTypes", ae.getAccessTypes() != null ? ae.getAccessTypes() : "",
                "processName", ae.getProcessName() != null ? ae.getProcessName() : "",
                "occurredAt",  ae.getOccurredAt().toString()
            ));
        } catch (Exception e) {
            log.warn("Serialization error: {}", e.getMessage());
            return;
        }

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("audit").data(payload));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        list.removeAll(dead);
    }

    private void removeEmitter(String folderPath, SseEmitter emitter) {
        List<SseEmitter> list = sseEmitters.get(folderPath);
        if (list != null) list.remove(emitter);
    }
}
