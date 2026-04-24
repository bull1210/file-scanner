package com.filescanner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filescanner.entity.AuditEvent;
import com.filescanner.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class WinEventLogService {

    private final AuditEventRepository auditRepo;
    private final ObjectMapper objectMapper;

    // Windows Security event log %%XXXX resource strings for file access
    private static final Map<String, String> ACCESS_CODES = new LinkedHashMap<>();
    static {
        ACCESS_CODES.put("%%4416", "Read");
        ACCESS_CODES.put("%%4417", "Write");
        ACCESS_CODES.put("%%4418", "Append");
        ACCESS_CODES.put("%%4419", "ReadExtAttr");
        ACCESS_CODES.put("%%4420", "WriteExtAttr");
        ACCESS_CODES.put("%%4421", "Execute");
        ACCESS_CODES.put("%%4422", "DeleteChild");
        ACCESS_CODES.put("%%4423", "ReadAttr");
        ACCESS_CODES.put("%%4424", "WriteAttr");
        ACCESS_CODES.put("%%1537", "Delete");
        ACCESS_CODES.put("%%1538", "ReadControl");
        ACCESS_CODES.put("%%1539", "WriteDac");
        ACCESS_CODES.put("%%1540", "WriteOwner");
        ACCESS_CODES.put("%%1541", "Sync");
    }

    public enum AuditStatus { ENABLED, DISABLED, NO_ADMIN, UNKNOWN }

    // -------------------------------------------------------------------------
    // Admin / policy checks
    // -------------------------------------------------------------------------

    public boolean isAdmin() {
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "([Security.Principal.WindowsPrincipal]" +
                "[Security.Principal.WindowsIdentity]::GetCurrent())" +
                ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return "True".equalsIgnoreCase(out);
        } catch (Exception e) {
            log.warn("Admin check failed: {}", e.getMessage());
            return false;
        }
    }

    public AuditStatus checkAuditPolicy() {
        try {
            ProcessBuilder pb = new ProcessBuilder("auditpol", "/get", "/subcategory:File System");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            if (out.contains("Success and Failure") || out.contains("Success")) return AuditStatus.ENABLED;
            if (out.contains("No auditing"))                                     return AuditStatus.DISABLED;
            return AuditStatus.UNKNOWN;
        } catch (Exception e) {
            log.warn("Audit policy check failed: {}", e.getMessage());
            return AuditStatus.NO_ADMIN;
        }
    }

    // -------------------------------------------------------------------------
    // Fetch events from Windows Security Event Log
    // -------------------------------------------------------------------------

    @Transactional
    public List<AuditEvent> fetchAndSave(String folderPath, int hours) {
        String json = runPowerShell(folderPath, hours);
        if (json == null || json.isBlank()) return List.of();
        List<AuditEvent> events = parseJson(folderPath, json);
        if (!events.isEmpty()) auditRepo.saveAll(events);
        return events;
    }

    private String runPowerShell(String folderPath, int hours) {
        // Escape backslashes and single-quotes for embedding in PS string
        String escaped = folderPath.replace("'", "''")
                                   .replace("\\", "\\\\");

        String cmd =
            "$ea='SilentlyContinue';" +
            "$start=(Get-Date).AddHours(-" + hours + ");" +
            "try {" +
            "  $evts=Get-WinEvent -FilterHashtable @{LogName='Security';Id=@(4663,4660,4670);StartTime=$start} -EA Stop;" +
            "  $res=$evts|Where-Object{$_.Properties[6].Value -like '" + escaped + "*'}|" +
            "  ForEach-Object{" +
            "    $xml=[xml]$_.ToXml();" +
            "    $d=$xml.Event.EventData.Data;" +
            "    $h=@{}; $d|%{$h[$_.Name]=$_.'#text'};" +
            "    [PSCustomObject]@{" +
            "      Time=$_.TimeCreated.ToString('o');" +
            "      User=$h['SubjectUserName'];" +
            "      Domain=$h['SubjectDomainName'];" +
            "      File=$h['ObjectName'];" +
            "      AccessList=$h['AccessList'];" +
            "      ProcessName=$h['ProcessName'];" +
            "      EventId=[string]$_.Id" +
            "    }" +
            "  };" +
            "  if($res){$res|ConvertTo-Json -Depth 2}" +
            "} catch { Write-Output ('ERR:'+$_.Exception.Message) }";

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();

            if (output.startsWith("ERR:")) {
                log.warn("PowerShell error: {}", output);
                return null;
            }
            return output.trim();
        } catch (Exception e) {
            log.error("PowerShell execution failed: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<AuditEvent> parseJson(String folderPath, String json) {
        List<AuditEvent> result = new ArrayList<>();
        try {
            // PowerShell outputs an object (not array) when there's a single result
            String normalized = json.trim();
            if (!normalized.startsWith("[")) normalized = "[" + normalized + "]";

            List<Map<String, Object>> rows = objectMapper.readValue(
                normalized, new TypeReference<List<Map<String, Object>>>() {});

            for (Map<String, Object> row : rows) {
                String file = str(row, "File");
                if (file == null || file.isBlank() || file.equals("null")) continue;

                String accessList = str(row, "AccessList");
                String eventId    = str(row, "EventId");

                AuditEvent ae = new AuditEvent();
                ae.setFolderPath(folderPath);
                ae.setFilePath(file);
                ae.setEventType(resolveEventType(eventId, accessList));
                ae.setUserName(str(row, "User"));
                ae.setDomainName(str(row, "Domain"));
                ae.setProcessName(filename(str(row, "ProcessName")));
                ae.setAccessTypes(decodeAccessList(accessList));
                ae.setSource("WINEVENTLOG");
                ae.setOccurredAt(parseTime(str(row, "Time")));
                result.add(ae);
            }
        } catch (Exception e) {
            log.warn("Failed to parse PowerShell JSON: {}", e.getMessage());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolveEventType(String eventId, String accessList) {
        if ("4660".equals(eventId))  return "DELETED";
        if ("4670".equals(eventId))  return "PERMISSION_CHANGED";
        if (accessList != null) {
            if (accessList.contains("%%1537"))  return "DELETED";
            if (accessList.contains("%%4417") ||
                accessList.contains("%%4418"))  return "WRITE";
            if (accessList.contains("%%4416"))  return "READ";
        }
        return "ACCESS";
    }

    private String decodeAccessList(String raw) {
        if (raw == null) return "";
        List<String> types = new ArrayList<>();
        for (Map.Entry<String, String> entry : ACCESS_CODES.entrySet()) {
            if (raw.contains(entry.getKey())) types.add(entry.getValue());
        }
        return types.isEmpty() ? raw.trim() : String.join(", ", types);
    }

    private LocalDateTime parseTime(String iso) {
        if (iso == null) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(
                iso.substring(0, 19), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private String filename(String path) {
        if (path == null) return null;
        int i = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
