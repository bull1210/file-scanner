package com.filescanner.controller;

import com.filescanner.dto.CloudDuplicateGroup;
import com.filescanner.entity.CloudAccount;
import com.filescanner.entity.CloudFileEntry;
import com.filescanner.repository.CloudAccountRepository;
import com.filescanner.repository.CloudFileEntryRepository;
import com.filescanner.service.CloudSyncService;
import com.filescanner.service.DuplicateService;
import com.filescanner.service.GoogleDriveService;
import com.filescanner.service.OneDriveService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Controller
@RequestMapping("/cloud")
@RequiredArgsConstructor
@Slf4j
public class CloudController {

    private final CloudAccountRepository   accountRepo;
    private final CloudFileEntryRepository fileRepo;
    private final CloudSyncService         syncService;
    private final DuplicateService         duplicateService;
    private final OneDriveService          oneDrive;
    private final GoogleDriveService       googleDrive;

    @Value("${cloud.oauth.redirect-base:http://localhost:8080}")
    private String redirectBase;

    // =========================================================================
    // Accounts overview
    // =========================================================================

    @GetMapping
    public String accounts(Model model) {
        model.addAttribute("accounts", accountRepo.findAllByOrderByCreatedAtDesc());
        model.addAttribute("syncService", syncService);
        return "cloud-accounts";
    }

    // =========================================================================
    // Add account
    // =========================================================================

    @GetMapping("/add")
    public String addForm(Model model) {
        model.addAttribute("oneDriveRedirectUri",  redirectBase + "/cloud/oauth/callback/onedrive");
        model.addAttribute("googleRedirectUri",    redirectBase + "/cloud/oauth/callback/googledrive");
        return "cloud-add";
    }

    @PostMapping("/account")
    public String saveAccount(@RequestParam String provider,
                               @RequestParam String displayName,
                               @RequestParam String clientId,
                               @RequestParam String clientSecret,
                               RedirectAttributes ra) {
        CloudAccount account = new CloudAccount();
        account.setProvider(provider.toUpperCase());
        account.setDisplayName(displayName.trim());
        account.setClientId(clientId.trim());
        account.setClientSecret(clientSecret.trim());
        account.setStatus("PENDING");
        accountRepo.save(account);
        ra.addFlashAttribute("successMessage", "Account saved. Click Connect to authorise with " + provider + ".");
        return "redirect:/cloud";
    }

    // =========================================================================
    // OAuth — start
    // =========================================================================

    @GetMapping("/connect/{id}")
    public String startOAuth(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        CloudAccount account = accountRepo.findById(id).orElse(null);
        if (account == null) { ra.addFlashAttribute("errorMessage", "Account not found."); return "redirect:/cloud"; }

        String state = id + ":" + UUID.randomUUID();
        session.setAttribute("oauthState", state);

        String redirectUri;
        String authUrl;
        if ("ONEDRIVE".equals(account.getProvider())) {
            redirectUri = redirectBase + "/cloud/oauth/callback/onedrive";
            authUrl = oneDrive.buildAuthUrl(account.getClientId(), redirectUri, state);
        } else {
            redirectUri = redirectBase + "/cloud/oauth/callback/googledrive";
            authUrl = googleDrive.buildAuthUrl(account.getClientId(), redirectUri, state);
        }
        return "redirect:" + authUrl;
    }

    // =========================================================================
    // OAuth — callbacks
    // =========================================================================

    @GetMapping("/oauth/callback/onedrive")
    public String oneDriveCallback(@RequestParam(required = false) String code,
                                    @RequestParam(required = false) String state,
                                    @RequestParam(required = false) String error,
                                    HttpSession session, RedirectAttributes ra) {
        if (error != null) { ra.addFlashAttribute("errorMessage", "OneDrive auth denied: " + error); return "redirect:/cloud"; }

        Long accountId = extractAccountId(state, session, ra);
        if (accountId == null) return "redirect:/cloud";

        CloudAccount account = accountRepo.findById(accountId).orElse(null);
        if (account == null) { ra.addFlashAttribute("errorMessage", "Account not found."); return "redirect:/cloud"; }

        try {
            String redirectUri = redirectBase + "/cloud/oauth/callback/onedrive";
            OneDriveService.TokenResponse tokens =
                    oneDrive.exchangeCode(code, account.getClientId(), account.getClientSecret(), redirectUri);
            OneDriveService.UserInfo user = oneDrive.getUserInfo(tokens.accessToken());

            applyTokens(account, tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn());
            account.setUserEmail(user.email());
            account.setProviderUserId(user.id());
            account.setStatus("CONNECTED");
            accountRepo.save(account);
            ra.addFlashAttribute("successMessage", "OneDrive connected as " + user.email() + ". Click Sync to fetch files.");
        } catch (Exception e) {
            log.error("OneDrive callback error: {}", e.getMessage());
            ra.addFlashAttribute("errorMessage", "OAuth failed: " + e.getMessage());
        }
        return "redirect:/cloud";
    }

    @GetMapping("/oauth/callback/googledrive")
    public String googleCallback(@RequestParam(required = false) String code,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String error,
                                  HttpSession session, RedirectAttributes ra) {
        if (error != null) { ra.addFlashAttribute("errorMessage", "Google auth denied: " + error); return "redirect:/cloud"; }

        Long accountId = extractAccountId(state, session, ra);
        if (accountId == null) return "redirect:/cloud";

        CloudAccount account = accountRepo.findById(accountId).orElse(null);
        if (account == null) { ra.addFlashAttribute("errorMessage", "Account not found."); return "redirect:/cloud"; }

        try {
            String redirectUri = redirectBase + "/cloud/oauth/callback/googledrive";
            GoogleDriveService.TokenResponse tokens =
                    googleDrive.exchangeCode(code, account.getClientId(), account.getClientSecret(), redirectUri);
            GoogleDriveService.UserInfo user = googleDrive.getUserInfo(tokens.accessToken());

            applyTokens(account, tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn());
            account.setUserEmail(user.email());
            account.setProviderUserId(user.id());
            account.setStatus("CONNECTED");
            accountRepo.save(account);
            ra.addFlashAttribute("successMessage", "Google Drive connected as " + user.email() + ". Click Sync to fetch files.");
        } catch (Exception e) {
            log.error("Google callback error: {}", e.getMessage());
            ra.addFlashAttribute("errorMessage", "OAuth failed: " + e.getMessage());
        }
        return "redirect:/cloud";
    }

    // =========================================================================
    // Account detail
    // =========================================================================

    @GetMapping("/{id}")
    public String accountDetail(@PathVariable Long id, Model model, RedirectAttributes ra) {
        CloudAccount account = accountRepo.findById(id).orElse(null);
        if (account == null) { ra.addFlashAttribute("errorMessage", "Account not found."); return "redirect:/cloud"; }

        List<CloudFileEntry> largest = fileRepo.findTop20ByAccountIdAndDirectoryFalseOrderBySizeBytesDesc(id);
        List<Object[]> extStats = fileRepo.statsByExtension(id);

        model.addAttribute("account",  account);
        model.addAttribute("largest",  largest);
        model.addAttribute("extStats", extStats);
        model.addAttribute("syncing",  syncService.isSyncing(id));
        model.addAttribute("redirectBase", redirectBase);
        return "cloud-detail";
    }

    // =========================================================================
    // Sync controls
    // =========================================================================

    @PostMapping("/{id}/sync")
    public String sync(@PathVariable Long id, RedirectAttributes ra) {
        if (!accountRepo.existsById(id)) { ra.addFlashAttribute("errorMessage", "Account not found."); return "redirect:/cloud"; }
        syncService.startSync(id);
        ra.addFlashAttribute("successMessage", "Sync started in background.");
        return "redirect:/cloud/" + id;
    }

    @GetMapping(value = "/{id}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable Long id) {
        SseEmitter emitter = syncService.subscribeProgress(id);
        try { emitter.send(SseEmitter.event().name("connected").data("ok")); }
        catch (IOException ignored) {}
        return emitter;
    }

    // =========================================================================
    // File browser
    // =========================================================================

    @GetMapping("/{id}/browse")
    public String browse(@PathVariable Long id,
                          @RequestParam(required = false) String path,
                          @RequestParam(required = false) String search,
                          @RequestParam(defaultValue = "0") int page,
                          Model model, RedirectAttributes ra) {

        CloudAccount account = accountRepo.findById(id).orElse(null);
        if (account == null) { ra.addFlashAttribute("errorMessage", "Account not found."); return "redirect:/cloud"; }

        String currentPath = (path != null && !path.isBlank()) ? path : "/";
        Page<CloudFileEntry> entries;

        if (search != null && !search.isBlank()) {
            entries = fileRepo.findByAccountIdAndNameContainingIgnoreCaseOrderByNameAsc(
                    id, search, PageRequest.of(page, 100));
        } else {
            entries = fileRepo.findByAccountIdAndParentPathOrderByDirectoryDescNameAsc(
                    id, currentPath, PageRequest.of(page, 100));
        }

        model.addAttribute("account",     account);
        model.addAttribute("entries",     entries);
        model.addAttribute("currentPath", currentPath);
        model.addAttribute("search",      search);
        model.addAttribute("breadcrumbs", buildBreadcrumbs(currentPath));
        return "cloud-browse";
    }

    // =========================================================================
    // Cloud duplicate detection
    // =========================================================================

    @GetMapping("/{id}/duplicates")
    public String showDuplicates(@PathVariable Long id, Model model, RedirectAttributes ra) {
        CloudAccount account = accountRepo.findById(id).orElse(null);
        if (account == null) { ra.addFlashAttribute("errorMessage", "Account not found."); return "redirect:/cloud"; }

        List<CloudDuplicateGroup> groups = duplicateService.findCloudDuplicates(id);
        long totalFiles = groups.stream().mapToLong(CloudDuplicateGroup::getCount).sum();
        long totalSize  = groups.stream().mapToLong(CloudDuplicateGroup::getTotalSizeBytes).sum();

        model.addAttribute("account",     account);
        model.addAttribute("groups",      groups);
        model.addAttribute("totalGroups", groups.size());
        model.addAttribute("totalFiles",  totalFiles);
        model.addAttribute("totalSize",   totalSize);
        return "cloud-duplicates";
    }

    @PostMapping("/{id}/duplicates/remove/{entryId}")
    public String removeCloudEntry(@PathVariable Long id,
                                   @PathVariable Long entryId,
                                   RedirectAttributes ra) {
        try {
            duplicateService.removeCloudEntry(entryId);
            ra.addFlashAttribute("successMessage", "Entry removed from scan index.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/cloud/" + id + "/duplicates";
    }

    @GetMapping("/{id}/duplicates/export.csv")
    public ResponseEntity<byte[]> exportCloudCsv(@PathVariable Long id, RedirectAttributes ra) {
        if (!accountRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<CloudDuplicateGroup> groups = duplicateService.findCloudDuplicates(id);
        byte[] csv = duplicateService.exportCloudCsv(groups);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"duplicates-cloud-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    // =========================================================================
    // Delete account
    // =========================================================================

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        if (accountRepo.existsById(id)) {
            fileRepo.deleteByAccountId(id);
            accountRepo.deleteById(id);
            ra.addFlashAttribute("successMessage", "Account and all its files removed.");
        }
        return "redirect:/cloud";
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Long extractAccountId(String state, HttpSession session, RedirectAttributes ra) {
        String expected = (String) session.getAttribute("oauthState");
        if (state == null || !state.equals(expected)) {
            ra.addFlashAttribute("errorMessage", "Invalid OAuth state — possible CSRF.");
            return null;
        }
        session.removeAttribute("oauthState");
        try { return Long.parseLong(state.split(":")[0]); }
        catch (Exception e) { ra.addFlashAttribute("errorMessage", "Malformed OAuth state."); return null; }
    }

    private void applyTokens(CloudAccount account, String access, String refresh, long expiresIn) {
        account.setAccessToken(access);
        if (refresh != null && !refresh.isBlank()) account.setRefreshToken(refresh);
        account.setTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
    }

    private List<String[]> buildBreadcrumbs(String path) {
        List<String[]> crumbs = new ArrayList<>();
        crumbs.add(new String[]{"Root", "/"});
        if (path == null || path.equals("/")) return crumbs;
        String[] parts = path.split("/");
        StringBuilder built = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            built.append("/").append(part);
            crumbs.add(new String[]{part, built.toString()});
        }
        return crumbs;
    }
}
