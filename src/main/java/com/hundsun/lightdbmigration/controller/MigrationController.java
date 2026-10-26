package com.hundsun.lightdbmigration.controller;

import com.hundsun.lightdbmigration.model.ConnectionTestRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.LastDatabaseConfig;
import com.hundsun.lightdbmigration.model.StructureSyncRequest;
import com.hundsun.lightdbmigration.service.TaskManager;
import com.hundsun.lightdbmigration.support.DatabaseSupport;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MigrationController {

    private final TaskManager taskManager;

    public MigrationController(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @GetMapping("/system")
    public Map<String, Object> systemInfo() {
        return success(taskManager.systemInfo());
    }

    @PostMapping("/connections/test")
    public Map<String, Object> testConnection(@RequestBody ConnectionTestRequest request) throws Exception {
        if (request == null || request.getDatabase() == null) {
            throw new IllegalArgumentException("数据库配置不能为空");
        }
        return success(DatabaseSupport.test(request.getDatabase()));
    }

    @GetMapping("/config/last")
    public ResponseEntity<Map<String, Object>> getLastDatabaseConfig() {
        LastDatabaseConfig config = taskManager.getLastDatabaseConfig();
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .header("Pragma", "no-cache")
            .body(success(config));
    }

    @PostMapping("/config/last")
    public Map<String, Object> saveLastDatabaseConfig(@RequestBody LastDatabaseConfig config) {
        taskManager.saveLastDatabaseConfig(config);
        return success("数据库配置已加密保存");
    }

    @DeleteMapping("/config/last")
    public Map<String, Object> clearLastDatabaseConfig() {
        taskManager.clearLastDatabaseConfig();
        return success("已清除最近一次数据库配置");
    }

    @PostMapping("/tasks")
    public Map<String, Object> createTask(@RequestBody CreateTaskRequest request) {
        return success(taskManager.create(request));
    }

    @GetMapping("/tasks")
    public Map<String, Object> listTasks() {
        List<Map<String, Object>> tasks = taskManager.list();
        return success(tasks);
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> getTask(@PathVariable("id") String id,
        @RequestParam(name = "afterLog", defaultValue = "0") long afterLog) {
        return success(taskManager.get(id, afterLog));
    }

    @PostMapping("/tasks/{id}/start")
    public Map<String, Object> startTask(@PathVariable("id") String id) {
        return success(taskManager.start(id));
    }

    /** Params: id 原任务标识；request 用户确认的建议摘要和临时连接。 Returns: 任务详情及执行进度。 */
    @PostMapping("/tasks/{id}/structure-sync/execute")
    public Map<String, Object> executeStructureSync(@PathVariable("id") String id,
        @RequestBody StructureSyncRequest request) {
        return success(taskManager.executeStructureSync(id, request));
    }

    @PostMapping("/tasks/{id}/pause")
    public Map<String, Object> pauseTask(@PathVariable("id") String id) {
        return success(taskManager.pause(id));
    }

    @PostMapping("/tasks/{id}/resume")
    public Map<String, Object> resumeTask(@PathVariable("id") String id) {
        return success(taskManager.resume(id));
    }

    @PostMapping("/tasks/{id}/terminate")
    public Map<String, Object> terminateTask(@PathVariable("id") String id) {
        return success(taskManager.stop(id));
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable("id") String id) {
        taskManager.delete(id);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", Boolean.TRUE);
        result.put("data", data);
        return result;
    }
}
