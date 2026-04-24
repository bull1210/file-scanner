package com.filescanner.controller;

import com.filescanner.service.AiQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/query")
@RequiredArgsConstructor
@Slf4j
public class QueryController {

    private final AiQueryService aiQueryService;

    @GetMapping
    public String queryForm() {
        return "query";
    }

    @PostMapping
    public String executeQuery(@RequestParam String prompt, Model model) {
        model.addAttribute("prompt", prompt);
        try {
            model.addAttribute("result", aiQueryService.query(prompt));
        } catch (SecurityException e) {
            model.addAttribute("errorMessage",
                    "Rejected: query would modify data. Only SELECT queries are allowed.");
        } catch (Exception e) {
            log.error("AI query failed for prompt: {}", prompt, e);
            model.addAttribute("errorMessage", "Query failed: " + e.getMessage());
        }
        return "query";
    }
}
