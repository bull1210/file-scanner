package com.filescanner.controller;

import com.filescanner.entity.AuditEvent;
import com.filescanner.repository.AuditEventRepository;
import com.filescanner.service.WatchMonitorService;
import com.filescanner.service.WinEventLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Controller
@RequestMapping("/audit")
@RequiredArgsConstructor
@Slf4j
public class AuditController {

    private final WatchMonitorService  watchService;
    private final WinEventLogService   winLogService;
    private final AuditEventRepository auditRepo;

    // -------------------------------------------------------------------------
    // Main audit page — handles both "pick a folder" and "show events" states
    // -------------------------------------------------------------------------

    @GetMapping
    public String audit(@RequestParam(required = false) String path,
                        @RequestParam(defaultValue = "ALL") String filter,
                        @RequestParam(defaultValue = "0")   int page,
                        Model model) {

        model.addAttribute("isAdmin",     winLogService.isAdmin());
        model.addAttribute("auditStatus", winLogService.checkAuditPolicy().name());
        model.addAttribute("monitoredPaths", watchService.getMonitoredPaths());

        if (path != null && !path.isBlank()) {
            Page<AuditEvent> events = auditRepo.findByFolderAndType(
                path, filter, PageRequest.of(page, 50));
            List<Object[]> stats = auditRepo.countByEventType(path);
            long total = auditRepo.countByFolderPath(path);

            model.addAttribute("folderPath",  path);
            model.addAttribute("events",      events);
            model.addAttribute("eventStats",  stats);
            model.addAttribute("totalEvents", total);
            model.addAttribute("filter",      filter);
            model.addAttribute("monitoring",  watchService.isMonitoring(path));
        }
        return "audit";
    }

    // -------------------------------------------------------------------------
    // WatchService controls
    // -------------------------------------------------------------------------

    @PostMapping("/start")
    public String startMonitor(@RequestParam String folderPath, RedirectAttributes ra) {
        try {
            watchService.startMonitoring(folderPath);
            ra.addFlashAttribute("successMessage", "Live monitoring started.");
        } catch (IOException e) {
            ra.addFlashAttribute("errorMessage", "Cannot start monitor: " + e.getMessage());
        }
        return "redirect:/audit?path=" + encode(folderPath);
    }

    @PostMapping("/stop")
    public String stopMonitor(@RequestParam String folderPath, RedirectAttributes ra) {
        watchService.stopMonitoring(folderPath);
        ra.addFlashAttribute("successMessage", "Monitoring stopped.");
        return "redirect:/audit?path=" + encode(folderPath);
    }

    // -------------------------------------------------------------------------
    // SSE stream for live events
    // -------------------------------------------------------------------------

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String path) {
        SseEmitter emitter = watchService.subscribe(path);
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException ignored) {}
        return emitter;
    }

    // -------------------------------------------------------------------------
    // Windows Security Event Log import
    // -------------------------------------------------------------------------

    @PostMapping("/winlog")
    public String fetchWinLog(@RequestParam String folderPath,
                              @RequestParam(defaultValue = "24") int hours,
                              RedirectAttributes ra) {
        List<AuditEvent> events = winLogService.fetchAndSave(folderPath, hours);
        if (events.isEmpty()) {
            ra.addFlashAttribute("warnMessage",
                "No Windows audit events found for this path in the last " + hours + " hours. " +
                "Ensure the app runs as Administrator and Object Access auditing is enabled.");
        } else {
            ra.addFlashAttribute("successMessage",
                events.size() + " event(s) imported from Windows Security Event Log.");
        }
        return "redirect:/audit?path=" + encode(folderPath);
    }

    // -------------------------------------------------------------------------
    // Clear stored events for a folder
    // -------------------------------------------------------------------------

    @PostMapping("/clear")
    public String clearEvents(@RequestParam String folderPath, RedirectAttributes ra) {
        auditRepo.deleteByFolderPath(folderPath);
        ra.addFlashAttribute("successMessage", "All audit events cleared for this folder.");
        return "redirect:/audit?path=" + encode(folderPath);
    }

    private String encode(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8);
    }
}
