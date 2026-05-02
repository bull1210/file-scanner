package com.filescanner.controller;

import com.filescanner.service.ScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ScanController {

    private final ScanService scanService;

    @GetMapping("/")
    public String index(Model model) {
        var scans = scanService.getAllScans();
        model.addAttribute("scans", scans);
        model.addAttribute("totalFilesIndexed",
                scans.stream().mapToLong(s -> s.getTotalFiles() != null ? s.getTotalFiles() : 0L).sum());
        model.addAttribute("totalSizeIndexed",
                scans.stream().mapToLong(s -> s.getTotalSizeBytes() != null ? s.getTotalSizeBytes() : 0L).sum());
        return "index";
    }

    @PostMapping("/scan")
    public String triggerScan(@RequestParam String path,
                              RedirectAttributes redirectAttrs) {
        if (path == null || path.isBlank()) {
            redirectAttrs.addFlashAttribute("errorMessage", "Please enter a directory path.");
            return "redirect:/";
        }
        try {
            Long scanId = scanService.startScan(path);
            redirectAttrs.addFlashAttribute("successMessage",
                    "Scan completed! Scan ID: " + scanId);
            return "redirect:/dashboard/" + scanId;
        } catch (IllegalArgumentException e) {
            redirectAttrs.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/";
        } catch (Exception e) {
            log.error("Scan failed for path: {}", path, e);
            redirectAttrs.addFlashAttribute("errorMessage",
                    "Scan failed: " + e.getMessage());
            return "redirect:/";
        }
    }
}
