package com.intelligence.platform.controller;

import com.intelligence.platform.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class LogsController {

    @Value("${logging.file.name:logs/app.log}")
    private String logFilePath;

    @GetMapping("/logs")
    public Result<List<String>> getLogs(@RequestParam(defaultValue = "1000") int limit) {
        File file = new File(logFilePath);
        if (!file.exists()) {
            return Result.ok(Collections.emptyList());
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (lines.size() > limit) {
                    lines.remove(0);
                }
            }
        } catch (IOException e) {
            return Result.error(500, "Failed to read log file: " + e.getMessage());
        }

        return Result.ok(lines);
    }
}
