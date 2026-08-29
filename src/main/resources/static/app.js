(function () {
    'use strict';

    var state = {
        tasks: [],
        selectedTaskId: null,
        selectedLogs: [],
        selectedTaskName: '',
        selectedStructureSyncScript: '',
        lastLogSequence: 0,
        refreshing: false,
        toastTimer: null
    };

    var statusNames = {
        CREATED: '待启动', QUEUED: '排队中', VALIDATING: '结构校验中', RUNNING: '迁移中',
        PAUSING: '等待暂停', PAUSED: '已暂停', STOPPING: '终止中', TERMINATED: '已终止',
        SUCCEEDED: '已完成', FAILED: '失败', PENDING: '待校验', VALIDATED: '校验通过'
    };

    document.addEventListener('DOMContentLoaded', function () {
        bindEvents();
        addMigrationStep();
        checkServer();
        loadLastDatabaseConfig();
        refreshTasks();
        window.setInterval(refreshTasks, 2000);
    });

    function bindEvents() {
        document.querySelectorAll('.db-type').forEach(function (select) {
            select.addEventListener('change', function () { updateDatabaseType(select.dataset.prefix); });
        });
        document.querySelectorAll('.test-connection').forEach(function (button) {
            button.addEventListener('click', function () { testConnection(button.dataset.prefix, button); });
        });
        document.getElementById('addStepButton').addEventListener('click', addMigrationStep);
        document.getElementById('stepsContainer').addEventListener('click', function (event) {
            if (event.target.classList.contains('remove-step')) {
                removeMigrationStep(event.target.closest('.migration-step'));
            }
        });
        document.getElementById('createTaskButton').addEventListener('click', createTask);
        document.getElementById('saveCurrentConfigButton').addEventListener('click', saveCurrentDatabaseConfig);
        document.getElementById('clearSavedConfigButton').addEventListener('click', clearSavedDatabaseConfig);
        document.getElementById('refreshTasksButton').addEventListener('click', refreshTasks);
        document.getElementById('tasksContainer').addEventListener('click', handleTaskAction);
        document.getElementById('closeLogButton').addEventListener('click', closeLogs);
        document.getElementById('copyStructureScriptButton').addEventListener('click', copyStructureScript);
        document.getElementById('downloadStructureScriptButton').addEventListener('click', downloadStructureScript);
    }

    async function checkServer() {
        var dot = document.getElementById('serverDot');
        var text = document.getElementById('serverText');
        try {
            var result = await api('/api/system');
            dot.className = 'state-dot online';
            text.textContent = '服务正常 · v' + result.data.version;
        } catch (error) {
            dot.className = 'state-dot offline';
            text.textContent = '服务连接失败';
        }
    }

    function updateDatabaseType(prefix) {
        var type = value(prefix + 'Type');
        var oracle = type === 'ORACLE';
        document.getElementById(prefix + 'Port').value = oracle ? '1521' : '3306';
        document.getElementById(prefix + 'DatabaseLabel').textContent = oracle ? '服务名 / SID' : '数据库名';
        document.getElementById(prefix + 'Database').placeholder = oracle ? '例如 ORCL' : '例如 business_db';
        document.getElementById(prefix + 'OracleModeWrap').classList.toggle('hidden', !oracle);
    }

    function databaseConfig(prefix) {
        return {
            type: value(prefix + 'Type'),
            host: value(prefix + 'Host'),
            port: numberValue(prefix + 'Port'),
            database: value(prefix + 'Database'),
            oracleMode: value(prefix + 'OracleMode'),
            username: value(prefix + 'Username'),
            password: document.getElementById(prefix + 'Password').value,
            jdbcUrl: value(prefix + 'JdbcUrl')
        };
    }

    async function loadLastDatabaseConfig() {
        try {
            var result = await api('/api/config/last');
            if (!result.data || !result.data.source || !result.data.target) { return; }
            fillDatabaseConfig('source', result.data.source);
            fillDatabaseConfig('target', result.data.target);
            showToast('已自动回填最近一次数据库配置（含密码）');
        } catch (error) {
            showToast('最近配置读取失败：' + error.message, true);
        }
    }

    function fillDatabaseConfig(prefix, config) {
        document.getElementById(prefix + 'Type').value = config.type || 'MYSQL';
        updateDatabaseType(prefix);
        setValue(prefix + 'Host', config.host);
        if (config.port != null) { setValue(prefix + 'Port', config.port); }
        setValue(prefix + 'Database', config.database);
        setValue(prefix + 'OracleMode', config.oracleMode || 'SERVICE');
        setValue(prefix + 'Username', config.username);
        setValue(prefix + 'Password', config.password);
        setValue(prefix + 'JdbcUrl', config.jdbcUrl);
    }

    function setValue(id, inputValue) {
        document.getElementById(id).value = inputValue == null ? '' : inputValue;
    }

    async function clearSavedDatabaseConfig() {
        if (!window.confirm('确认删除服务本机保存的数据库配置和密码吗？当前页面已填写内容不会清空。')) {
            return;
        }
        var button = document.getElementById('clearSavedConfigButton');
        setBusy(button, true, '清除中…');
        try {
            await api('/api/config/last', { method: 'DELETE' });
            showToast('已清除服务本机保存的数据库配置');
        } catch (error) {
            showToast(error.message, true);
        } finally {
            setBusy(button, false);
        }
    }

    async function saveCurrentDatabaseConfig() {
        var button = document.getElementById('saveCurrentConfigButton');
        setBusy(button, true, '保存中…');
        try {
            await api('/api/config/last', {
                method: 'POST',
                body: JSON.stringify({ source: databaseConfig('source'), target: databaseConfig('target') })
            });
            showToast('两端数据库配置已加密保存，服务重启后会自动回填');
        } catch (error) {
            showToast(error.message, true);
        } finally {
            setBusy(button, false);
        }
    }

    async function testConnection(prefix, button) {
        var resultElement = document.getElementById(prefix + 'TestResult');
        setBusy(button, true, '测试中…');
        resultElement.className = 'test-result';
        resultElement.textContent = '正在连接，请稍候…';
        try {
            var result = await api('/api/connections/test', {
                method: 'POST', body: JSON.stringify({ database: databaseConfig(prefix) })
            });
            var data = result.data;
            resultElement.className = 'test-result success';
            resultElement.textContent = '✓ ' + data.message + ' · ' + data.databaseProduct + ' · ' + data.costMs + ' ms';
        } catch (error) {
            resultElement.className = 'test-result error';
            resultElement.textContent = '✕ ' + error.message;
        } finally {
            setBusy(button, false);
        }
    }

    function addMigrationStep() {
        var template = document.getElementById('migrationStepTemplate');
        var fragment = template.content.cloneNode(true);
        document.getElementById('stepsContainer').appendChild(fragment);
        renumberSteps();
    }

    function removeMigrationStep(stepElement) {
        var container = document.getElementById('stepsContainer');
        if (container.children.length <= 1) {
            showToast('至少保留一条迁移 SQL', true);
            return;
        }
        stepElement.remove();
        renumberSteps();
    }

    function renumberSteps() {
        document.querySelectorAll('.migration-step').forEach(function (element, index) {
            element.querySelector('.migration-step-title').textContent = 'SQL ' + (index + 1);
            var name = element.querySelector('.step-name');
            if (!name.value) { name.placeholder = '步骤 ' + (index + 1); }
        });
    }

    function collectSteps() {
        return Array.prototype.map.call(document.querySelectorAll('.migration-step'), function (element, index) {
            return {
                name: element.querySelector('.step-name').value.trim() || ('SQL ' + (index + 1)),
                targetTable: element.querySelector('.target-table').value.trim(),
                cursorColumn: element.querySelector('.cursor-column').value.trim(),
                insertMode: element.querySelector('.insert-mode').value,
                sourceSql: element.querySelector('.source-sql').value.trim()
            };
        });
    }

    async function createTask() {
        var button = document.getElementById('createTaskButton');
        var payload = {
            name: value('taskName'),
            batchSize: numberValue('batchSize'),
            source: databaseConfig('source'),
            target: databaseConfig('target'),
            steps: collectSteps()
        };
        setBusy(button, true, '正在创建…');
        try {
            var result = await api('/api/tasks', { method: 'POST', body: JSON.stringify(payload) });
            showToast('任务已创建，请在任务列表核对后启动');
            state.selectedTaskId = result.data.id;
            state.selectedLogs = result.data.logs || [];
            state.lastLogSequence = result.data.lastLogSequence || 0;
            await refreshTasks();
            openLogs(result.data.id);
            document.querySelector('.task-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
        } catch (error) {
            showToast(error.message, true);
        } finally {
            setBusy(button, false);
        }
    }

    async function refreshTasks() {
        if (state.refreshing) { return; }
        state.refreshing = true;
        try {
            var result = await api('/api/tasks');
            state.tasks = result.data || [];
            renderTasks();
            if (state.selectedTaskId) {
                await refreshSelectedTaskLogs();
            }
        } catch (error) {
            if (document.visibilityState === 'visible') { showToast('刷新任务失败：' + error.message, true); }
        } finally {
            state.refreshing = false;
        }
    }

    function renderTasks() {
        var container = document.getElementById('tasksContainer');
        var empty = document.getElementById('emptyTasks');
        empty.classList.toggle('hidden', state.tasks.length > 0);
        container.innerHTML = state.tasks.map(renderTaskCard).join('');
    }

    function renderTaskCard(task) {
        var selected = task.id === state.selectedTaskId ? ' selected' : '';
        var actions = taskActions(task);
        var stepHtml = (task.steps || []).map(function (step) {
            return '<div class="step-progress-item"><strong>' + escapeHtml(step.name) + ' · '
                + escapeHtml(statusName(step.status)) + '</strong>'
                + escapeHtml(step.targetTable || '') + '<br>已处理 ' + formatNumber(step.processedRows)
                + ' / ' + formatNumber(step.totalRows) + '，写入 ' + formatNumber(step.insertedRows)
                + '，跳过 ' + formatNumber(step.ignoredRows) + '</div>';
        }).join('');
        return '<article class="task-card' + selected + '" data-task-id="' + escapeHtml(task.id) + '">'
            + '<div class="task-head"><div><div class="task-name">' + escapeHtml(task.name)
            + '<span class="status-badge ' + escapeHtml(task.status) + '">' + escapeHtml(statusName(task.status))
            + '</span></div><div class="task-meta"><span>ID：<span class="task-id">' + escapeHtml(task.id)
            + '</span></span><span>源：' + escapeHtml(task.source.type + ' ' + task.source.endpoint)
            + '</span><span>目标：' + escapeHtml(task.target.type + ' ' + task.target.endpoint)
            + '</span><span>批次：' + formatNumber(task.batchSize) + '</span></div></div>'
            + '<div class="task-actions">' + actions + '</div></div>'
            + '<div class="progress-row"><div class="progress-track"><div class="progress-fill" style="width:'
            + Math.max(0, Math.min(100, task.percent || 0)) + '%"></div></div><div class="progress-text">'
            + formatNumber(task.processedRows) + ' / ' + formatNumber(task.totalRows) + ' · ' + (task.percent || 0)
            + '%</div></div><div class="current-message">' + escapeHtml(task.currentMessage || '') + '</div>'
            + '<div class="step-progress-list">' + stepHtml + '</div></article>';
    }

    function taskActions(task) {
        var buttons = ['<button class="button ghost small" data-action="logs">查看日志</button>'];
        if (task.status === 'CREATED') {
            buttons.push('<button class="button primary small" data-action="start">启动</button>');
            buttons.push('<button class="button danger small" data-action="delete">清除</button>');
        } else if (['QUEUED', 'VALIDATING', 'RUNNING'].indexOf(task.status) >= 0) {
            buttons.push('<button class="button warn small" data-action="pause">暂停</button>');
            buttons.push('<button class="button danger small" data-action="terminate">终止</button>');
        } else if (['PAUSING', 'PAUSED'].indexOf(task.status) >= 0) {
            buttons.push('<button class="button primary small" data-action="resume">继续</button>');
            buttons.push('<button class="button danger small" data-action="terminate">终止</button>');
        } else if (task.status === 'STOPPING') {
            buttons.push('<button class="button danger small" disabled>终止中…</button>');
        } else {
            buttons.push('<button class="button danger small" data-action="delete">清除</button>');
        }
        return buttons.join('');
    }

    async function handleTaskAction(event) {
        var button = event.target.closest('button[data-action]');
        var card = event.target.closest('.task-card');
        if (!button || !card) { return; }
        var id = card.dataset.taskId;
        var action = button.dataset.action;
        if (action === 'logs') {
            openLogs(id);
            return;
        }
        if (action === 'delete' && !window.confirm('确认从页面中清除该任务记录吗？这不会删除数据库中的数据。')) {
            return;
        }
        setBusy(button, true, '处理中…');
        try {
            if (action === 'delete') {
                await api('/api/tasks/' + encodeURIComponent(id), { method: 'DELETE' });
                if (state.selectedTaskId === id) { closeLogs(); }
                showToast('任务记录已清除');
            } else {
                await api('/api/tasks/' + encodeURIComponent(id) + '/' + action, { method: 'POST' });
                showToast(actionMessage(action));
            }
            await refreshTasks();
        } catch (error) {
            showToast(error.message, true);
        } finally {
            setBusy(button, false);
        }
    }

    function openLogs(id) {
        if (state.selectedTaskId !== id) {
            state.selectedLogs = [];
            state.selectedTaskName = '';
            state.selectedStructureSyncScript = '';
            state.lastLogSequence = 0;
        }
        state.selectedTaskId = id;
        document.getElementById('logPanel').classList.remove('hidden');
        renderStructureSyncScript();
        renderTasks();
        refreshSelectedTaskLogs(true);
    }

    function closeLogs() {
        state.selectedTaskId = null;
        state.selectedLogs = [];
        state.selectedTaskName = '';
        state.selectedStructureSyncScript = '';
        state.lastLogSequence = 0;
        document.getElementById('logPanel').classList.add('hidden');
        renderTasks();
    }

    async function refreshSelectedTaskLogs(scrollIntoView) {
        if (!state.selectedTaskId) { return; }
        try {
            var result = await api('/api/tasks/' + encodeURIComponent(state.selectedTaskId)
                + '?afterLog=' + state.lastLogSequence);
            var task = result.data;
            var logs = task.logs || [];
            if (logs.length) {
                state.selectedLogs = state.selectedLogs.concat(logs);
                state.lastLogSequence = task.lastLogSequence || state.lastLogSequence;
            }
            state.selectedTaskName = task.name || 'migration-task';
            state.selectedStructureSyncScript = task.structureSyncScript || '';
            document.getElementById('logTaskName').textContent = task.name + ' · ' + statusName(task.status);
            renderLogs();
            renderStructureSyncScript();
            if (scrollIntoView) {
                document.getElementById('logPanel').scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
        } catch (error) {
            if (error.status === 400) { closeLogs(); }
        }
    }

    function renderLogs() {
        var content = document.getElementById('logContent');
        content.innerHTML = state.selectedLogs.map(function (log) {
            return '<div class="log-line ' + escapeHtml(log.level) + '"><span class="time">'
                + escapeHtml(log.time) + '</span><span class="level">' + escapeHtml(log.level)
                + '</span><span>' + escapeHtml(log.message) + '</span></div>';
        }).join('');
        if (document.getElementById('autoScroll').checked) { content.scrollTop = content.scrollHeight; }
    }

    function renderStructureSyncScript() {
        var panel = document.getElementById('structureSyncPanel');
        var textarea = document.getElementById('structureSyncScript');
        textarea.value = state.selectedStructureSyncScript;
        panel.classList.toggle('hidden', !state.selectedStructureSyncScript);
    }

    function copyStructureScript() {
        var script = state.selectedStructureSyncScript;
        if (!script) { return; }
        if (navigator.clipboard && window.isSecureContext) {
            navigator.clipboard.writeText(script).then(function () {
                showToast('表结构同步脚本已复制');
            }).catch(copyStructureScriptFallback);
            return;
        }
        copyStructureScriptFallback();
    }

    function copyStructureScriptFallback() {
        var textarea = document.getElementById('structureSyncScript');
        textarea.focus();
        textarea.select();
        var copied = document.execCommand('copy');
        showToast(copied ? '表结构同步脚本已复制' : '复制失败，请在脚本框中手动全选复制', !copied);
    }

    function downloadStructureScript() {
        if (!state.selectedStructureSyncScript) { return; }
        var blob = new Blob([state.selectedStructureSyncScript], { type: 'text/sql;charset=utf-8' });
        var url = window.URL.createObjectURL(blob);
        var link = document.createElement('a');
        var taskName = (state.selectedTaskName || 'migration-task').replace(/[^A-Za-z0-9_-]+/g, '_');
        link.href = url;
        link.download = taskName + '-structure-sync.sql';
        document.body.appendChild(link);
        link.click();
        link.remove();
        window.URL.revokeObjectURL(url);
        showToast('表结构同步脚本已下载');
    }

    async function api(url, options) {
        var requestOptions = options || {};
        requestOptions.headers = Object.assign({ 'Content-Type': 'application/json' }, requestOptions.headers || {});
        var response = await window.fetch(url, requestOptions);
        if (response.status === 204) { return { success: true }; }
        var data;
        try { data = await response.json(); } catch (ignored) { data = null; }
        if (!response.ok || !data || data.success === false) {
            var error = new Error(data && data.message ? data.message : ('请求失败，HTTP ' + response.status));
            error.status = response.status;
            throw error;
        }
        return data;
    }

    function setBusy(button, busy, text) {
        if (!button) { return; }
        if (busy) {
            button.dataset.originalText = button.textContent;
            button.textContent = text || '处理中…';
            button.disabled = true;
        } else {
            button.textContent = button.dataset.originalText || button.textContent;
            button.disabled = false;
        }
    }

    function showToast(message, isError) {
        var toast = document.getElementById('toast');
        window.clearTimeout(state.toastTimer);
        toast.textContent = message;
        toast.className = 'toast show' + (isError ? ' error' : '');
        state.toastTimer = window.setTimeout(function () { toast.className = 'toast'; }, 3500);
    }

    function value(id) {
        var element = document.getElementById(id);
        return element ? element.value.trim() : '';
    }

    function numberValue(id) {
        var parsed = parseInt(value(id), 10);
        return isNaN(parsed) ? null : parsed;
    }

    function statusName(status) { return statusNames[status] || status || '-'; }
    function formatNumber(value) { return Number(value || 0).toLocaleString('zh-CN'); }

    function actionMessage(action) {
        return { start: '任务已启动', pause: '已提交暂停请求', resume: '任务已继续', terminate: '已提交终止请求' }[action]
            || '操作成功';
    }

    function escapeHtml(valueToEscape) {
        return String(valueToEscape == null ? '' : valueToEscape)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
}());
