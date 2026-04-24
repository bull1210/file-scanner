package com.filescanner.controller;

import com.filescanner.repository.FileEntryRepository;
import com.filescanner.service.ScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class BrowseController {

    private final ScanService         scanService;
    private final FileEntryRepository entryRepo;

    private static final int PAGE_SIZE = 100;

    @GetMapping("/browse/{scanId}")
    public String browse(@PathVariable Long scanId,
                         @RequestParam(required = false) String path,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(required = false) String search,
                         Model model) {

        var scan = scanService.getScan(scanId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Scan not found: " + scanId));

        var pageable = PageRequest.of(page, PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "directory").and(Sort.by("name")));

        String currentPath = (path != null && !path.isBlank()) ? path : scan.getRootPath();

        var entries = (search != null && !search.isBlank())
                ? entryRepo.findByScanIdAndFullPathContainingIgnoreCase(scanId, search, pageable)
                : entryRepo.findByScanIdAndParentPathOrderByDirectoryDescNameAsc(
                        scanId, currentPath, pageable);

        model.addAttribute("scan",        scan);
        model.addAttribute("entries",     entries);
        model.addAttribute("currentPath", currentPath);
        model.addAttribute("search",      search);
        model.addAttribute("breadcrumbs", buildBreadcrumbs(scan.getRootPath(), currentPath));
        return "browse";
    }

    private List<String[]> buildBreadcrumbs(String root, String current) {
        List<String[]> crumbs = new ArrayList<>();
        crumbs.add(new String[]{"Root", root});
        if (current != null && !current.equals(root)) {
            try {
                Path rel = Path.of(root).relativize(Path.of(current));
                Path accumulated = Path.of(root);
                for (Path segment : rel) {
                    accumulated = accumulated.resolve(segment);
                    crumbs.add(new String[]{segment.toString(), accumulated.toString()});
                }
            } catch (IllegalArgumentException ignored) {
                // paths on different drives; just show current
                crumbs.add(new String[]{current, current});
            }
        }
        return crumbs;
    }
}
