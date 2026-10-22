(function (root, factory) {
    'use strict';

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = factory();
    } else {
        root.MigrationConfigTransfer = factory();
    }
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
    'use strict';

    var FORMAT = 'light-db-migration-config';
    var VERSION = 1;
    var MAX_FILE_BYTES = 5 * 1024 * 1024;
    var TASK_MIGRATION = 'MIGRATION';
    var TASK_ARCHIVE = 'ARCHIVE';
    var INSERT_IGNORE = 'IGNORE_DUPLICATE';
    var INSERT_STRICT = 'STRICT_INSERT';
    var TEXT_TYPE = 'string';
    var PLAIN_OBJECT_TAG = '[object Object]';
    var EMPTY_TEXT = '';
    var MIN_ITEMS = 1;
    var MIN_BATCH_SIZE = 1;
    var MAX_MIGRATION_BATCH_SIZE = 10000;
    var MAX_ARCHIVE_BATCH_SIZE = 500;
    var MAX_STEPS = 50;
    var MAX_ARCHIVE_RELATIONS = 5;
    var RELATION_SEPARATOR_PATTERN = /[,，;；=]/;
    var hasOwn = Object.prototype.hasOwnProperty;

    function requireObject(value, path) {
        if (Object.prototype.toString.call(value) !== PLAIN_OBJECT_TAG) {
            throw new Error(path + ' 必须是对象');
        }
        return value;
    }

    function requireField(object, key, path) {
        if (!hasOwn.call(object, key)) {
            throw new Error(path + ' 缺少必填字段');
        }
        return object[key];
    }

    function requireText(object, key, path, optional) {
        if (optional && !hasOwn.call(object, key)) { return EMPTY_TEXT; }
        var result = requireField(object, key, path);
        if (typeof result !== TEXT_TYPE) {
            throw new Error(path + ' 必须是字符串');
        }
        return result;
    }

    function requireList(object, key, path, minimum, maximum) {
        var result = requireField(object, key, path);
        if (!Array.isArray(result)) {
            throw new Error(path + ' 必须是数组');
        }
        if (minimum && result.length < minimum) {
            throw new Error(path + ' 至少需要 ' + minimum + ' 项');
        }
        if (result.length > maximum) {
            throw new Error(path + ' 最多允许 ' + maximum + ' 项');
        }
        return result;
    }

    function requireNodeId(object, key, path) {
        var result = requireText(object, key, path);
        if (!result.trim()) {
            throw new Error(path + ' 不能为空');
        }
        return result;
    }

    function requireRelationColumn(object, key, path) {
        var result = requireText(object, key, path);
        if (!result.trim()) {
            throw new Error(path + ' 不能为空；未配置关联时请使用空 relations 数组');
        }
        if (RELATION_SEPARATOR_PATTERN.test(result)) {
            throw new Error(path + ' 不能包含关联映射分隔符（逗号、分号或等号）');
        }
        return result;
    }

    function normalizeStep(value, index) {
        var path = 'task.steps[' + index + ']';
        var step = requireObject(value, path);
        var insertMode = requireText(step, 'insertMode', path + '.insertMode');
        if (insertMode !== INSERT_IGNORE && insertMode !== INSERT_STRICT) {
            throw new Error(path + '.insertMode 只支持 ' + INSERT_IGNORE + ' 或 ' + INSERT_STRICT);
        }
        return {
            name: requireText(step, 'name', path + '.name', true),
            targetTable: requireText(step, 'targetTable', path + '.targetTable'),
            cursorColumn: requireText(step, 'cursorColumn', path + '.cursorColumn', true),
            insertMode: insertMode,
            sourceSql: requireText(step, 'sourceSql', path + '.sourceSql')
        };
    }

    function normalizeMain(value, index) {
        var path = 'task.archive.mainTables[' + index + ']';
        var table = requireObject(value, path);
        return {
            name: requireText(table, 'name', path + '.name', true),
            nodeId: requireNodeId(table, 'nodeId', path + '.nodeId'),
            sourceTable: requireText(table, 'sourceTable', path + '.sourceTable'),
            targetTable: requireText(table, 'targetTable', path + '.targetTable'),
            cursorColumn: requireText(table, 'cursorColumn', path + '.cursorColumn', true),
            sourceSql: requireText(table, 'sourceSql', path + '.sourceSql')
        };
    }

    function normalizeChild(value, index) {
        var path = 'task.archive.childTables[' + index + ']';
        var table = requireObject(value, path);
        var relations = requireList(table, 'relations', path + '.relations', null, MAX_ARCHIVE_RELATIONS);
        return {
            name: requireText(table, 'name', path + '.name', true),
            nodeId: requireNodeId(table, 'nodeId', path + '.nodeId'),
            parentNodeId: requireNodeId(table, 'parentNodeId', path + '.parentNodeId'),
            sourceTable: requireText(table, 'sourceTable', path + '.sourceTable'),
            targetTable: requireText(table, 'targetTable', path + '.targetTable'),
            cursorColumn: requireText(table, 'cursorColumn', path + '.cursorColumn', true),
            relations: relations.map(function (value, relationIndex) {
                var relationPath = path + '.relations[' + relationIndex + ']';
                var relation = requireObject(value, relationPath);
                return {
                    childColumn: requireRelationColumn(relation, 'childColumn', relationPath + '.childColumn'),
                    mainColumn: requireRelationColumn(relation, 'mainColumn', relationPath + '.mainColumn')
                };
            })
        };
    }

    function validateArchiveGraph(archive) {
        var nodes = new Map();
        var validated = new Set();
        function register(table, path) {
            if (nodes.has(table.nodeId)) {
                throw new Error(path + '.nodeId 节点标识重复：' + table.nodeId);
            }
            nodes.set(table.nodeId, { table: table, path: path });
        }
        archive.mainTables.forEach(function (table, index) {
            register(table, 'task.archive.mainTables[' + index + ']');
            validated.add(table.nodeId);
        });
        archive.childTables.forEach(function (table, index) {
            var path = 'task.archive.childTables[' + index + ']';
            register(table, path);
            if (table.nodeId === table.parentNodeId) {
                throw new Error(path + '.parentNodeId 不能指向自身');
            }
        });
        archive.childTables.forEach(function (table) {
            var visiting = new Set();
            var path = [];
            var current = nodes.get(table.nodeId);
            while (!validated.has(current.table.nodeId)) {
                var nodeId = current.table.nodeId;
                if (visiting.has(nodeId)) {
                    throw new Error(current.path + '.parentNodeId 存在循环关联：' + nodeId);
                }
                visiting.add(nodeId);
                path.push(nodeId);
                var parentNodeId = current.table.parentNodeId;
                if (!nodes.has(parentNodeId)) {
                    throw new Error(current.path + '.parentNodeId 上级节点不存在：' + parentNodeId);
                }
                current = nodes.get(parentNodeId);
            }
            path.forEach(function (nodeId) { validated.add(nodeId); });
        });
    }

    function normalizeArchive(value) {
        var archive = requireObject(value, 'task.archive');
        var mainTables = requireList(archive, 'mainTables', 'task.archive.mainTables', MIN_ITEMS, MAX_STEPS);
        var childTables = requireList(archive, 'childTables', 'task.archive.childTables', null, MAX_STEPS);
        if (mainTables.length + childTables.length > MAX_STEPS) {
            throw new Error('task.archive 主业务表和子业务表总数最多允许 ' + MAX_STEPS + ' 张');
        }
        var result = {
            mainTables: mainTables.map(normalizeMain),
            childTables: childTables.map(normalizeChild)
        };
        validateArchiveGraph(result);
        return result;
    }

    function normalize(value) {
        var document = requireObject(value, '配置文件');
        if (requireField(document, 'format', 'format') !== FORMAT) {
            throw new Error('format 配置文件格式不受支持');
        }
        if (requireField(document, 'version', 'version') !== VERSION) {
            throw new Error('version 配置文件版本不受支持，目前仅支持 ' + VERSION);
        }
        var task = requireObject(requireField(document, 'task', 'task'), 'task');
        var taskType = requireText(task, 'taskType', 'task.taskType');
        if (taskType !== TASK_MIGRATION && taskType !== TASK_ARCHIVE) {
            throw new Error('task.taskType 只支持 ' + TASK_MIGRATION + ' 或 ' + TASK_ARCHIVE);
        }
        var batchSize = requireField(task, 'batchSize', 'task.batchSize');
        var maximumBatchSize = taskType === TASK_ARCHIVE ? MAX_ARCHIVE_BATCH_SIZE : MAX_MIGRATION_BATCH_SIZE;
        if (!Number.isInteger(batchSize) || batchSize < MIN_BATCH_SIZE || batchSize > maximumBatchSize) {
            throw new Error('task.batchSize 必须是 ' + MIN_BATCH_SIZE + ' 到 ' + maximumBatchSize + ' 之间的整数');
        }
        var steps = taskType === TASK_MIGRATION
            ? requireList(task, 'steps', 'task.steps', MIN_ITEMS, MAX_STEPS).map(normalizeStep) : [];
        var archive = taskType === TASK_ARCHIVE
            ? normalizeArchive(requireField(task, 'archive', 'task.archive')) : null;
        return {
            format: FORMAT,
            version: VERSION,
            task: {
                taskType: taskType,
                name: requireText(task, 'name', 'task.name', true),
                batchSize: batchSize,
                steps: steps,
                archive: archive
            }
        };
    }

    return {
        FORMAT: FORMAT,
        VERSION: VERSION,
        MAX_FILE_BYTES: MAX_FILE_BYTES,
        normalize: normalize
    };
}));
