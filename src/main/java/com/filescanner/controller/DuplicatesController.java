package com.filescanner.controller;

import com.filescanner.dto.DuplicateGroup;
import com.filescanner.service.DuplicateService;
import com.filescanner.service.ScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.List;

@Controller
@RequiredArgsConstructor
@Slf4j
public class DuplicatesController {

    private final ScanService      scanService;
    private final DuplicateService duplicateService;

    @GetMapping("/duplicates/{scanId}")
    public String showDuplicates(@PathVariable Long scanId, Model model) {
        var scan = scanService.getScan(scanId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Scan not found: " + scanId));

        List<DuplicateGroup> groups = duplicateService.findDuplicates(scanId);

        long totalFiles = groups.stream().mapToLong(DuplicateGroup::getCount).sum();
        long totalSize  = groups.stream().mapToLong(DuplicateGroup::getTotalSizeBytes).sum();

        model.addAttribute("scan",        scan);
        model.addAttribute("groups",      groups);
        model.addAttribute("totalGroups", groups.size());
        model.addAttribute("totalFiles",  totalFiles);
        model.addAttribute("totalSize",   totalSize);
        return "duplicates";
    }

    @PostMapping("/duplicates/{scanId}/delete/{entryId}")
    public String deleteFile(@PathVariable Long scanId,
                             @PathVariable Long entryId,
                             RedirectAttributes redirectAttrs) {
        try {
            String path = duplicateService.deleteFile(entryId);
            redirectAttrs.addFlashAttribute("successMessage",
                    "Deleted: " + path);
        } catch (IOException e) {
            redirectAttrs.addFlashAttribute("errorMessage",
                    "Could not delete file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            redirectAttrs.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/duplicates/" + scanId;
    }

    @PostMapping("/duplicates/{scanId}/delete-group")
    public String deleteGroup(@PathVariable Long scanId,
                              @RequestParam String groupName,
                              @RequestParam(defaultValue = "true") boolean keepFirst,
                              RedirectAttributes redirectAttrs) {
        try {
            int deleted = duplicateService.deleteGroup(scanId, groupName, keepFirst);
            redirectAttrs.addFlashAttribute("successMessage",
                    "Deleted " + deleted + " duplicate(s) of \"" + groupName + "\".");
        } catch (IOException e) {
            redirectAttrs.addFlashAttribute("errorMessage",
                    "Could not delete duplicates: " + e.getMessage());
        }
        return "redirect:/duplicates/" + scanId;
    }

    @GetMapping("/duplicates/{scanId}/export.csv")
    public ResponseEntity<byte[]> exportCsv(@PathVariable Long scanId) {
        scanService.getScan(scanId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Scan not found: " + scanId));

        List<DuplicateGroup> groups = duplicateService.findDuplicates(scanId);
        byte[] csv = duplicateService.exportCsv(groups);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"duplicates-scan-" + scanId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }
}
