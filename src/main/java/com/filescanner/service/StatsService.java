package com.filescanner.service;

import com.filescanner.dto.StatsDto;
import com.filescanner.repository.FileEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final FileEntryRepository entryRepo;

    @Transactional(readOnly = true)
    public StatsDto computeStats(Long scanId) {
        StatsDto dto = new StatsDto();
        dto.setScanId(scanId);

        dto.setLargestFiles(
                entryRepo.findTop20ByScanIdAndDirectoryFalseOrderBySizeBytesDesc(scanId));

        List<Object[]> extRows = entryRepo.countByExtension(scanId);
        List<String> extLabels = new ArrayList<>();
        List<Long>   extCounts = new ArrayList<>();
        List<Long>   extSizes  = new ArrayList<>();
        for (Object[] row : extRows) {
            extLabels.add(row[0] != null ? (String) row[0] : "(none)");
            extCounts.add(toLong(row[1]));
            extSizes.add(toLong(row[2]));
        }
        dto.setExtLabels(extLabels);
        dto.setExtCounts(extCounts);
        dto.setExtSizes(extSizes);

        List<Object[]> depthRows = entryRepo.countByDepth(scanId);
        List<Integer> depthLevels = new ArrayList<>();
        List<Long>    depthCounts = new ArrayList<>();
        for (Object[] row : depthRows) {
            depthLevels.add(((Number) row[0]).intValue());
            depthCounts.add(toLong(row[1]));
        }
        dto.setDepthLevels(depthLevels);
        dto.setDepthCounts(depthCounts);

        List<Object[]> ageRows = entryRepo.ageBuckets(scanId);
        Object[] age = (ageRows.isEmpty() || ageRows.get(0) == null) ? new Object[5] : ageRows.get(0);
        dto.setAgeBuckets(new long[]{
            toLong(age[0]), toLong(age[1]), toLong(age[2]),
            toLong(age[3]), toLong(age[4])
        });

        List<Object[]> totalRows = entryRepo.totalCountAndSize(scanId);
        Object[] totals = (totalRows.isEmpty() || totalRows.get(0) == null) ? new Object[2] : totalRows.get(0);
        dto.setTotalFileCount(toLong(totals[0]));
        dto.setTotalSizeBytes(toLong(totals[1]));

        Long dupGroups = entryRepo.countDuplicateGroups(scanId);
        dto.setDuplicateGroupCount(dupGroups != null ? dupGroups : 0L);

        Long wasted = entryRepo.sumWastedBytes(scanId);
        dto.setDuplicateWastedBytes(wasted != null ? wasted : 0L);

        Long dirCount = entryRepo.countDirectories(scanId);
        dto.setDirectoryCount(dirCount != null ? dirCount : 0L);

        return dto;
    }

    private long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
