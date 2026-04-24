package com.filescanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filescanner.entity.CloudAccount;
import com.filescanner.entity.CloudFileEntry;
import com.filescanner.repository.CloudAccountRepository;
import com.filescanner.repository.CloudFileEntryRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@RequiredArgsConstructor
public class CloudSyncService {

    private final CloudAccountRepository    accountRepo;
    private final CloudFileEntryRepository  fileRepo;
    private final OneDriveService           oneDrive;
    private final GoogleDriveService        googleDrive;
    private final ObjectMapper              objectMapper;

    @Value("${cloud.sync.batch-size:500}")
    private int batchSize;

    private final ConcurrentHashMap<Long, Future<?>>        tasks      = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<SseEmitter>> listeners  = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isSyncing(Long accountId) {
        Future<?> f = tasks.get(accountId);
        return f != null && !f.isDone();
    }

    public SseEmitter subscribeProgress(Long accountId) {
        SseEmitter emitter = new SseEmitter(0L);
        listeners.computeIfAbsent(accountId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(accountId, emitter));
        emitter.onTimeout(()    -> removeEmitter(accountId, emitter));
        emitter.onError(e       -> removeEmitter(accountId, emitter));
        return emitter;
    }

    public void startSync(Long accountId) {
        if (isSyncing(accountId)) return;
        tasks.put(accountId, executor.submit(() -> runSync(accountId)));
    }

    public void cancelSync(Long accountId) {
        Future<?> f = tasks.remove(accountId);
        if (f != null) f.cancel(true);
    }

    // -------------------------------------------------------------------------
    // Sync worker
    // -------------------------------------------------------------------------

    private void runSync(Long accountId) {
        CloudAccount account = accountRepo.findById(accountId).orElse(null);
        if (account == null) return;

        try {
            account.setStatus("SYNCING");
            account.setErrorMessage(null);
            accountRepo.save(account);
            push(accountId, "START", 0, "Clearing previous data…", false, false);

            fileRepo.deleteByAccountId(accountId);

            List<CloudFileEntry> buffer = new ArrayList<>(batchSize);
            AtomicLong files    = new AtomicLong();
            AtomicLong folders  = new AtomicLong();
            AtomicLong bytes    = new AtomicLong();

            var onEntry = buildConsumer(accountId, account.getId(), buffer, files, folders, bytes);

            if ("ONEDRIVE".equals(account.getProvider())) {
                push(accountId, "FILES", 0, "Connecting to OneDrive…", false, false);
                oneDrive.syncFiles(account, onEntry);
            } else {
                push(accountId, "FOLDERS", 0, "Building Google Drive folder map…", false, false);
                googleDrive.syncFiles(account, onEntry);
            }

            // Flush remaining buffer
            if (!buffer.isEmpty()) flushBatch(buffer, account.getId());

            account.setStatus("SYNCED");
            account.setTotalFiles(files.get());
            account.setTotalFolders(folders.get());
            account.setTotalSizeBytes(bytes.get());
            account.setLastSyncAt(LocalDateTime.now());
            accountRepo.save(account);

            long total = files.get() + folders.get();
            push(accountId, "DONE", total,
                    String.format("Done — %,d files, %,d folders", files.get(), folders.get()),
                    true, false);

        } catch (Exception ex) {
            log.error("Sync failed [{}]: {}", accountId, ex.getMessage(), ex);
            account.setStatus("ERROR");
            account.setErrorMessage(ex.getMessage());
            accountRepo.save(account);
            push(accountId, "ERROR", 0, "Sync failed: " + ex.getMessage(), true, true);
        } finally {
            tasks.remove(accountId);
        }
    }

    private java.util.function.BiConsumer<CloudFileEntry, String> buildConsumer(
            Long accountId, Long realAccountId,
            List<CloudFileEntry> buffer,
            AtomicLong files, AtomicLong folders, AtomicLong bytes) {

        return (entry, stage) -> {
            entry.setAccountId(realAccountId);
            buffer.add(entry);

            if (entry.isDirectory()) folders.incrementAndGet();
            else {
                files.incrementAndGet();
                if (entry.getSizeBytes() != null) bytes.addAndGet(entry.getSizeBytes());
            }

            if (buffer.size() >= batchSize) flushBatch(new ArrayList<>(buffer), realAccountId);
            if (buffer.size() >= batchSize) buffer.clear();

            long total = files.get() + folders.get();
            if (total % 200 == 0)
                push(accountId, stage, total,
                        String.format("Fetched %,d items…", total), false, false);
        };
    }

    private void flushBatch(List<CloudFileEntry> batch, Long accountId) {
        try {
            fileRepo.saveAll(batch);
            batch.clear();
        } catch (Exception e) {
            log.warn("Batch save error: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // SSE helpers
    // -------------------------------------------------------------------------

    private void push(Long accountId, String stage, long count,
                       String message, boolean done, boolean error) {
        List<SseEmitter> list = listeners.getOrDefault(accountId, List.of());
        if (list.isEmpty()) return;
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "stage",   stage,
                    "count",   count,
                    "message", message,
                    "done",    done,
                    "error",   error));
            List<SseEmitter> dead = new ArrayList<>();
            for (SseEmitter e : list) {
                try { e.send(SseEmitter.event().name("progress").data(payload)); }
                catch (IOException ex) { dead.add(e); }
            }
            list.removeAll(dead);
        } catch (Exception ignored) {}
    }

    private void removeEmitter(Long accountId, SseEmitter emitter) {
        List<SseEmitter> list = listeners.get(accountId);
        if (list != null) list.remove(emitter);
    }

    @PreDestroy
    public void shutdown() { executor.shutdown(); }
}
