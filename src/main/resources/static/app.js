(function () {
    'use strict';

    var ARCHIVE_MAIN_NODE_ID = 'MAIN';
    var ARCHIVE_MAIN_NODE_PREFIX = 'MAIN_';
    var ARCHIVE_CHILD_NODE_PREFIX = 'CHILD_';
    var MIN_ARCHIVE_MAIN_TABLES = 1;
    var MIN_MIGRATION_STEPS = 1;
    var MIGRATION_STEP_SELECTOR = '#stepsContainer .migration-step';
    var TASK_TYPE_ARCHIVE = 'ARCHIVE';
    var DB_MYSQL = 'MYSQL';
    var DB_ORACLE = 'ORACLE';
    var DATABASE_TYPE_SETTINGS = {
        MYSQL: {port: '3306', label: '数据库名', placeholder: '例如 business_db', jdbcUrl: 'jdbc:mysql://...'},
        ORACLE: {port: '1521', label: '服务名 / SID', placeholder: '例如 ORCL', jdbcUrl: 'jdbc:oracle:thin:@...'},
        DM: {port: '5236', label: '模式名 / Schema（选填）', placeholder: '留空使用登录用户名', jdbcUrl: 'jdbc:dm://主机:5236'}
    };

    var state = {
        tasks: [],
        selectedTaskId: null,
        selectedLogs: [],
        selectedTaskName: '',
        selectedStructureSyncScript: '',
        lastLogSequence: 0,
        refreshing: false,
        archiveMainNodeSequence: 0,
        archiveNodeSequence: 0,
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
        addArchiveMain();
        addArchiveChild();
        updateTaskType();
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
        document.getElementById('exportConfigButton').addEventListener('click', exportTaskConfiguration);
        document.getElementById('importConfigButton').addEventListener('click', function () {
            document.getElementById('configFileInput').click();
        });
        document.getElementById('configFileInput').addEventListener('change', importTaskConfiguration);
        document.getElementById('taskType').addEventListener('change', updateTaskType);
        document.getElementById('addArchiveMainButton').addEventListener('click', addArchiveMain);
        document.getElementById('addArchiveChildButton').addEventListener('click', addArchiveChild);
        document.getElementById('stepsContainer').addEventListener('click', function (event) {
            if (event.target.classList.contains('remove-step')) {
                removeMigrationStep(event.target.closest('.migration-step'));
            }
        });
        document.getElementById('archiveMainsContainer').addEventListener('click', function (event) {
            if (event.target.classList.contains('remove-archive-main')) {
                removeArchiveMain(event.target.closest('.archive-main-table'));
            }
        });
        document.getElementById('archiveMainsContainer').addEventListener('input', function (event) {
            if (event.target.classList.contains('archive-main-name')) { refreshArchiveParentOptions(); }
        });
        document.getElementById('archiveChildrenContainer').addEventListener('click', function (event) {
            if (event.target.classList.contains('remove-archive-child')) {
                var removed = event.target.closest('.archive-child-table');
                var removedNodeId = removed.dataset.nodeId;
                var resetCount = resetRemovedArchiveParent(removedNodeId);
                removed.remove();
                renumberArchiveChildren();
                if (resetCount > 0) {
                    showToast('已删除节点，' + resetCount + ' 张下级从表的上级已重置为主表，请重新核对关联关系', true);
                }
            }
        });
        document.getElementById('archiveChildrenContainer').addEventListener('input', function (event) {
            if (event.target.classList.contains('archive-child-name')) { refreshArchiveParentOptions(); }
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
        var settings = DATABASE_TYPE_SETTINGS[type] || DATABASE_TYPE_SETTINGS[DB_MYSQL];
        var oracle = type === DB_ORACLE;
        document.getElementById(prefix + 'Port').value = settings.port;
        document.getElementById(prefix + 'DatabaseLabel').textContent = settings.label;
        document.getElementById(prefix + 'Database').placeholder = settings.placeholder;
        document.getElementById(prefix + 'JdbcUrl').placeholder = settings.jdbcUrl;
        document.getElementById(prefix + 'OracleModeWrap').classList.toggle('hidden', !oracle);
    }

    function updateTaskType() {
        var archiveMode = value('taskType') === 'ARCHIVE';
        document.getElementById('targetDatabaseCard').classList.toggle('hidden', archiveMode);
        document.getElementById('databaseDirection').classList.toggle('hidden', archiveMode);
        document.getElementById('databaseGrid').classList.toggle('archive-mode', archiveMode);
        document.getElementById('migrationConfig').classList.toggle('hidden', archiveMode);
        document.getElementById('archiveConfig').classList.toggle('hidden', !archiveMode);
        document.getElementById('addStepButton').classList.toggle('hidden', archiveMode);
        document.getElementById('databaseHeading').textContent = archiveMode ? '配置归档数据库' : '配置两端数据库';
        document.getElementById('sourceDatabaseTitle').textContent = archiveMode ? '归档数据库' : '源数据库';
        document.getElementById('configurationHeading').textContent = archiveMode ? '配置主表与关联子表' : '编排迁移 SQL';
        document.getElementById('taskTypeHelp').textContent = archiveMode
            ? '归档在同一个数据库连接内执行，归档表和源表可使用模式名.表名。'
            : '数据迁移使用独立的源库和目标库连接。';
        document.getElementById('createTaskButton').textContent = archiveMode ? '创建归档任务' : '创建迁移任务';
        document.getElementById('createHint').textContent = archiveMode
            ? '创建后请核对主表范围、全部关联字段和备份状态，再在任务列表中启动。'
            : '请在下方任务列表中再次点击“启动”，便于最后核对。';
        document.getElementById('taskName').placeholder = archiveMode ? '例如：历史业务数据归档' : '例如：客户基础数据迁移';
        var batchSize = document.getElementById('batchSize');
        batchSize.max = archiveMode ? '500' : '10000';
        if (archiveMode && Number(batchSize.value) > 500) { batchSize.value = '500'; }
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
        document.getElementById(prefix + 'Type').value = config.type || DB_MYSQL;
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

    function exportTaskConfiguration() {
        try {
            var taskType = value('taskType');
            var archiveMode = taskType === TASK_TYPE_ARCHIVE;
            var config = MigrationConfigTransfer.normalize({
                format: MigrationConfigTransfer.FORMAT,
                version: MigrationConfigTransfer.VERSION,
                task: {
                    taskType: taskType,
                    name: value('taskName'),
                    batchSize: Number(value('batchSize')),
                    steps: archiveMode ? [] : collectSteps(),
                    archive: archiveMode ? collectArchiveConfig() : null
                }
            });
            var blob = new Blob([JSON.stringify(config, null, 2)], { type: 'application/json;charset=utf-8' });
            if (blob.size > MigrationConfigTransfer.MAX_FILE_BYTES) {
                throw new Error('配置文件超过 5 MiB，请精简规则后导出');
            }
            var url = window.URL.createObjectURL(blob);
            var link = document.createElement('a');
            link.href = url;
            link.download = (archiveMode ? 'archive' : 'migration') + '-config.json';
            document.body.appendChild(link);
            link.click();
            link.remove();
            window.setTimeout(function () { window.URL.revokeObjectURL(url); }, 1000);
            logConfigurationTransfer('导出完成', config.task);
            showToast('配置已导出，不含数据库连接配置');
        } catch (error) {
            console.warn('配置导出失败：' + error.message);
            showToast('配置导出失败：' + error.message, true);
        }
    }

    async function importTaskConfiguration() {
        var input = document.getElementById('configFileInput');
        var file = input.files && input.files[0];
        if (!file) { return; }
        var button = document.getElementById('importConfigButton');
        setBusy(button, true, '导入中…');
        try {
            if (file.size > MigrationConfigTransfer.MAX_FILE_BYTES) {
                throw new Error('配置文件超过 5 MiB，请选择较小的配置文件');
            }
            var text = await readConfigurationFile(file);
            var parsed;
            try {
                parsed = JSON.parse(text.replace(/^\uFEFF/, ''));
            } catch (ignored) {
                throw new Error('文件内容不是有效的 JSON，请检查后重试');
            }
            var config = MigrationConfigTransfer.normalize(parsed);
            applyTaskConfiguration(config.task);
            logConfigurationTransfer('导入完成', config.task);
            showToast(config.task.taskType === TASK_TYPE_ARCHIVE
                ? '归档配置已导入，数据库连接已保留；请核对规则并重新确认备份及删除源记录'
                : '迁移配置已导入，数据库连接已保留；请核对后创建任务');
        } catch (error) {
            console.warn('配置导入失败：' + error.message);
            showToast('配置导入失败：' + error.message, true);
        } finally {
            input.value = '';
            setBusy(button, false);
        }
    }

    function readConfigurationFile(file) {
        return new Promise(function (resolve, reject) {
            var reader = new FileReader();
            reader.onload = function () { resolve(reader.result); };
            reader.onerror = function () { reject(new Error('配置文件读取失败，请重新选择文件')); };
            reader.onabort = function () { reject(new Error('配置文件读取已取消')); };
            reader.readAsText(file, 'UTF-8');
        });
    }

    function applyTaskConfiguration(task) {
        setValue('taskType', task.taskType);
        setValue('taskName', task.name);
        updateTaskType();
        setValue('batchSize', task.batchSize);
        document.getElementById('archiveConfirmDelete').checked = false;
        if (task.taskType === TASK_TYPE_ARCHIVE) {
            fillArchiveConfiguration(task.archive);
        } else {
            document.getElementById('stepsContainer').innerHTML = '';
            task.steps.forEach(function (step) {
                var element = addMigrationStep();
                element.querySelector('.step-name').value = step.name;
                element.querySelector('.target-table').value = step.targetTable;
                element.querySelector('.cursor-column').value = step.cursorColumn;
                element.querySelector('.insert-mode').value = step.insertMode;
                element.querySelector('.source-sql').value = step.sourceSql;
            });
        }
    }

    function fillArchiveConfiguration(archive) {
        document.getElementById('archiveChildrenContainer').innerHTML = '';
        document.getElementById('archiveMainsContainer').innerHTML = '';
        var nodeIds = new Map();
        archive.mainTables.forEach(function (table) {
            var element = addArchiveMain();
            nodeIds.set(table.nodeId, element.dataset.nodeId);
            fillArchiveTable(element, 'main', table);
            element.querySelector('.archive-main-sql').value = table.sourceSql;
        });
        var childElements = archive.childTables.map(function (table) {
            var element = addArchiveChild();
            nodeIds.set(table.nodeId, element.dataset.nodeId);
            fillArchiveTable(element, 'child', table);
            element.querySelector('.archive-child-relations').value = table.relations.map(function (relation) {
                return relation.childColumn + '=' + relation.mainColumn;
            }).join(', ');
            return element;
        });
        refreshArchiveParentOptions();
        archive.childTables.forEach(function (table, index) {
            childElements[index].querySelector('.archive-child-parent-node').value = nodeIds.get(table.parentNodeId);
        });
    }

    function fillArchiveTable(element, kind, table) {
        var prefix = '.archive-' + kind;
        element.querySelector(prefix + '-name').value = table.name;
        element.querySelector(prefix + '-source-table').value = table.sourceTable;
        element.querySelector(prefix + '-target-table').value = table.targetTable;
        element.querySelector(prefix + '-cursor-column').value = table.cursorColumn;
    }

    function logConfigurationTransfer(action, task) {
        console.info('配置' + action, {
            taskType: task.taskType,
            stepCount: task.steps.length,
            mainTableCount: task.archive ? task.archive.mainTables.length : 0,
            childTableCount: task.archive ? task.archive.childTables.length : 0
        });
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
            var sourceConfig = databaseConfig('source');
            var archiveMode = value('taskType') === 'ARCHIVE';
            await api('/api/config/last', {
                method: 'POST',
                body: JSON.stringify({
                    source: sourceConfig,
                    target: archiveMode ? Object.assign({}, sourceConfig) : databaseConfig('target')
                })
            });
            showToast(archiveMode ? '归档数据库配置已加密保存' : '两端数据库配置已加密保存，服务重启后会自动回填');
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
        var element = fragment.querySelector('.migration-step');
        document.getElementById('stepsContainer').appendChild(fragment);
        renumberSteps();
        return element;
    }

    function removeMigrationStep(stepElement) {
        var container = document.getElementById('stepsContainer');
        if (container.children.length <= MIN_MIGRATION_STEPS) {
            showToast('至少保留一条迁移 SQL', true);
            return;
        }
        stepElement.remove();
        renumberSteps();
    }

    function renumberSteps() {
        document.querySelectorAll(MIGRATION_STEP_SELECTOR).forEach(function (element, index) {
            element.querySelector('.migration-step-title').textContent = 'SQL ' + (index + 1);
            var name = element.querySelector('.step-name');
            if (!name.value) { name.placeholder = '步骤 ' + (index + 1); }
        });
    }

    function addArchiveChild() {
        var template = document.getElementById('archiveChildTemplate');
        var fragment = template.content.cloneNode(true);
        var childTable = fragment.querySelector('.archive-child-table');
        state.archiveNodeSequence++;
        childTable.dataset.nodeId = ARCHIVE_CHILD_NODE_PREFIX + state.archiveNodeSequence;
        childTable.querySelector('.archive-child-node-id').value = childTable.dataset.nodeId;
        document.getElementById('archiveChildrenContainer').appendChild(fragment);
        renumberArchiveChildren();
        return childTable;
    }

    function addArchiveMain() {
        var template = document.getElementById('archiveMainTemplate');
        var fragment = template.content.cloneNode(true);
        var mainTable = fragment.querySelector('.archive-main-table');
        state.archiveMainNodeSequence++;
        mainTable.dataset.nodeId = ARCHIVE_MAIN_NODE_PREFIX + state.archiveMainNodeSequence;
        mainTable.querySelector('.archive-main-node-id').value = mainTable.dataset.nodeId;
        document.getElementById('archiveMainsContainer').appendChild(fragment);
        renumberArchiveMains();
        return mainTable;
    }

    function removeArchiveMain(mainElement) {
        var container = document.getElementById('archiveMainsContainer');
        if (container.children.length <= MIN_ARCHIVE_MAIN_TABLES) {
            showToast('至少保留一张主业务表', true);
            return;
        }
        var removedNodeId = mainElement.dataset.nodeId;
        document.querySelectorAll('.archive-child-parent-node').forEach(function (select) {
            if (select.value === removedNodeId) { select.dataset.resetToMain = 'true'; }
        });
        mainElement.remove();
        renumberArchiveMains();
        var resetCount = applyRemovedArchiveParentReset();
        if (resetCount > 0) {
            showToast('已删除主表节点，' + resetCount + ' 张直属从表已改挂到第一张主表，请重新核对关联关系', true);
        }
    }

    function renumberArchiveMains() {
        document.querySelectorAll('.archive-main-table').forEach(function (element, index) {
            element.querySelector('.archive-main-title').textContent = '主业务表 ' + (index + 1);
            var name = element.querySelector('.archive-main-name');
            if (!name.value) { name.placeholder = '主业务表 ' + (index + 1); }
        });
        refreshArchiveParentOptions();
    }

    function renumberArchiveChildren() {
        document.querySelectorAll('.archive-child-table').forEach(function (element, index) {
            element.querySelector('.archive-child-title').textContent = '子业务表 ' + (index + 1);
            var name = element.querySelector('.archive-child-name');
            if (!name.value) { name.placeholder = '子业务表 ' + (index + 1); }
        });
        refreshArchiveParentOptions();
    }

    function refreshArchiveParentOptions() {
        var childTables = Array.prototype.slice.call(document.querySelectorAll('.archive-child-table'));
        var nodes = [];
        document.querySelectorAll('.archive-main-table').forEach(function (element, index) {
            nodes.push({
                id: element.dataset.nodeId,
                name: element.querySelector('.archive-main-name').value.trim() || ('主业务表 ' + (index + 1))
            });
        });
        childTables.forEach(function (element, index) {
            nodes.push({
                id: element.dataset.nodeId,
                name: element.querySelector('.archive-child-name').value.trim() || ('子业务表 ' + (index + 1))
            });
        });
        childTables.forEach(function (element) {
            var select = element.querySelector('.archive-child-parent-node');
            var current = select.value || defaultArchiveMainNodeId();
            select.innerHTML = '';
            nodes.forEach(function (node) {
                if (node.id === element.dataset.nodeId) { return; }
                var option = document.createElement('option');
                option.value = node.id;
                option.textContent = node.name + '（' + node.id + '）';
                select.appendChild(option);
            });
            select.value = Array.prototype.some.call(select.options, function (option) {
                return option.value === current;
            }) ? current : defaultArchiveMainNodeId();
        });
    }

    function defaultArchiveMainNodeId() {
        var firstMain = document.querySelector('.archive-main-table');
        return firstMain ? firstMain.dataset.nodeId : ARCHIVE_MAIN_NODE_ID;
    }

    function resetRemovedArchiveParent(removedNodeId) {
        document.querySelectorAll('.archive-child-parent-node').forEach(function (select) {
            if (select.value === removedNodeId) {
                select.dataset.resetToMain = 'true';
            }
        });
        refreshArchiveParentOptions();
        return applyRemovedArchiveParentReset();
    }

    function applyRemovedArchiveParentReset() {
        var resetCount = 0;
        document.querySelectorAll('.archive-child-parent-node[data-reset-to-main]').forEach(function (select) {
            select.value = defaultArchiveMainNodeId();
            delete select.dataset.resetToMain;
            resetCount++;
        });
        return resetCount;
    }

    function collectSteps() {
        return Array.prototype.map.call(document.querySelectorAll(MIGRATION_STEP_SELECTOR), function (element, index) {
            return {
                name: element.querySelector('.step-name').value.trim() || ('SQL ' + (index + 1)),
                targetTable: element.querySelector('.target-table').value.trim(),
                cursorColumn: element.querySelector('.cursor-column').value.trim(),
                insertMode: element.querySelector('.insert-mode').value,
                sourceSql: element.querySelector('.source-sql').value.trim()
            };
        });
    }

    function collectArchiveConfig() {
        return {
            confirmSourceDelete: document.getElementById('archiveConfirmDelete').checked,
            mainTables: Array.prototype.map.call(document.querySelectorAll('.archive-main-table'),
                function (element, index) {
                    return {
                        name: element.querySelector('.archive-main-name').value.trim()
                            || ('主业务表 ' + (index + 1)),
                        nodeId: element.dataset.nodeId,
                        parentNodeId: null,
                        sourceTable: element.querySelector('.archive-main-source-table').value.trim(),
                        targetTable: element.querySelector('.archive-main-target-table').value.trim(),
                        cursorColumn: element.querySelector('.archive-main-cursor-column').value.trim(),
                        sourceSql: element.querySelector('.archive-main-sql').value.trim(),
                        relations: []
                    };
                }),
            childTables: Array.prototype.map.call(document.querySelectorAll('.archive-child-table'),
                function (element, index) {
                    return {
                        name: element.querySelector('.archive-child-name').value.trim() || ('子业务表 ' + (index + 1)),
                        nodeId: element.dataset.nodeId,
                        parentNodeId: element.querySelector('.archive-child-parent-node').value,
                        sourceTable: element.querySelector('.archive-child-source-table').value.trim(),
                        targetTable: element.querySelector('.archive-child-target-table').value.trim(),
                        cursorColumn: element.querySelector('.archive-child-cursor-column').value.trim(),
                        relations: parseArchiveRelations(element.querySelector('.archive-child-relations').value,
                            index + 1)
                    };
                })
        };
    }

    function parseArchiveRelations(input, childIndex) {
        var text = String(input || '').trim();
        if (!text) { return []; }
        return text.split(/[,，;；]+/).map(function (mapping) {
            var parts = mapping.split('=');
            if (parts.length !== 2 || !parts[0].trim() || !parts[1].trim()) {
                throw new Error('子业务表 ' + childIndex + ' 的关联字段格式不正确，请使用 子表字段=上级表字段');
            }
            return { childColumn: parts[0].trim(), mainColumn: parts[1].trim() };
        });
    }

    async function createTask() {
        var button = document.getElementById('createTaskButton');
        setBusy(button, true, '正在创建…');
        try {
            var taskType = value('taskType');
            var sourceConfig = databaseConfig('source');
            var payload = {
                taskType: taskType,
                name: value('taskName'),
                batchSize: numberValue('batchSize'),
                source: sourceConfig,
                target: taskType === 'ARCHIVE' ? Object.assign({}, sourceConfig) : databaseConfig('target'),
                steps: taskType === 'ARCHIVE' ? [] : collectSteps(),
                archive: taskType === 'ARCHIVE' ? collectArchiveConfig() : null
            };
            var result = await api('/api/tasks', { method: 'POST', body: JSON.stringify(payload) });
            showToast(taskType === 'ARCHIVE' ? '归档任务已创建，请核对后再启动' : '任务已创建，请在任务列表核对后启动');
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
            var archiveStep = task.taskType === 'ARCHIVE';
            var counts = archiveStep
                ? '已归档 ' + formatNumber(step.insertedRows) + '，已删除源记录 ' + formatNumber(step.deletedRows)
                : '写入 ' + formatNumber(step.insertedRows) + '，跳过 ' + formatNumber(step.ignoredRows);
            var graph = archiveStep ? '<br>节点 ' + escapeHtml(step.nodeId || ARCHIVE_MAIN_NODE_ID) + '，上级 '
                + escapeHtml(step.parentNodeId || '-') : '';
            return '<div class="step-progress-item"><strong>' + escapeHtml(step.name) + ' · '
                + escapeHtml(statusName(step.status)) + '</strong>'
                + (archiveStep ? escapeHtml((step.sourceTable || '') + ' → ' + (step.targetTable || ''))
                    : escapeHtml(step.targetTable || ''))
                + graph
                + '<br>已处理 ' + formatNumber(step.processedRows) + ' / ' + formatNumber(step.totalRows)
                + '，' + counts + '</div>';
        }).join('');
        var archiveTask = task.taskType === 'ARCHIVE';
        var targetMeta = archiveTask ? '<span>方式：同库归档</span>'
            : '<span>目标：' + escapeHtml(task.target.type + ' ' + task.target.endpoint) + '</span>';
        return '<article class="task-card' + selected + '" data-task-id="' + escapeHtml(task.id) + '">'
            + '<div class="task-head"><div><div class="task-name">' + escapeHtml(task.name)
            + '<span class="task-type-badge ' + escapeHtml(task.taskType || 'MIGRATION') + '">'
            + (archiveTask ? '归档' : '迁移') + '</span><span class="status-badge ' + escapeHtml(task.status) + '">'
            + escapeHtml(task.status === 'RUNNING' && archiveTask ? '归档中' : statusName(task.status))
            + '</span></div><div class="task-meta"><span>ID：<span class="task-id">' + escapeHtml(task.id)
            + '</span></span><span>源：' + escapeHtml(task.source.type + ' ' + task.source.endpoint)
            + '</span>' + targetMeta + '<span>批次：' + formatNumber(task.batchSize) + '</span></div></div>'
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
        var selectedTask = state.tasks.filter(function (task) { return task.id === id; })[0];
        if (action === 'start' && selectedTask && selectedTask.taskType === 'ARCHIVE'
            && !window.confirm('归档会永久删除已成功写入归档表的源记录。请确认已完成备份、已核对主表范围及全部子表关联，是否启动？')) {
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
