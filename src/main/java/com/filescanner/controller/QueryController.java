package com.filescanner.controller;

import com.filescanner.service.AiQueryService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/query")
@RequiredArgsConstructor
@Slf4j
public class QueryController {

    private static final int HISTORY_MAX = 10;

    private final AiQueryService aiQueryService;

    @GetMapping
    public String queryForm(HttpSession session, Model model) {
        model.addAttribute("queryHistory", getHistory(session));
        return "query";
    }

    @PostMapping
    public String executeQuery(@RequestParam String prompt, HttpSession session, Model model) {
        model.addAttribute("prompt", prompt);
        try {
            model.addAttribute("result", aiQueryService.query(prompt));
            addToHistory(session, prompt);
        } catch (SecurityException e) {
            model.addAttribute("errorMessage",
                    "Rejected: query would modify data. Only SELECT queries are allowed.");
        } catch (Exception e) {
            log.error("AI query failed for prompt: {}", prompt, e);
            model.addAttribute("errorMessage", "Query failed: " + e.getMessage());
        }
        model.addAttribute("queryHistory", getHistory(session));
        return "query";
    }

    @SuppressWarnings("unchecked")
    private List<String> getHistory(HttpSession session) {
        List<String> h = (List<String>) session.getAttribute("queryHistory");
        return h != null ? h : new ArrayList<>();
    }

    private void addToHistory(HttpSession session, String prompt) {
        List<String> history = new ArrayList<>(getHistory(session));
        history.remove(prompt);
        history.add(0, prompt);
        if (history.size() > HISTORY_MAX) history = history.subList(0, HISTORY_MAX);
        session.setAttribute("queryHistory", history);
    }
}
