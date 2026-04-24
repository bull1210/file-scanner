package com.filescanner.controller;

import com.filescanner.service.ScanService;
import com.filescanner.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final ScanService  scanService;
    private final StatsService statsService;

    @GetMapping("/dashboard/{scanId}")
    public String dashboard(@PathVariable Long scanId, Model model) {
        var scan = scanService.getScan(scanId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Scan not found: " + scanId));

        model.addAttribute("scan",  scan);
        model.addAttribute("stats", statsService.computeStats(scanId));
        return "dashboard";
    }
}
