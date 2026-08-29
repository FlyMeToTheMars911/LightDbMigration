package com.hundsun.lightdbmigration.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public class TaskLog {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final long sequence;
    private final String time;
    private final String level;
    private final String message;

    public TaskLog(long sequence, String level, String message) {
        this.sequence = sequence;
        this.time = LocalDateTime.now().format(FORMATTER);
        this.level = level;
        this.message = message;
    }

    public long getSequence() {
        return sequence;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sequence", sequence);
        result.put("time", time);
        result.put("level", level);
        result.put("message", message);
        return result;
    }
}
