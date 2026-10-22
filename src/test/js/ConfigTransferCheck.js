'use strict';

var assert = require('assert');
var fs = require('fs');
var path = require('path');
var vm = require('vm');
var transfer = require('../../main/resources/static/config-transfer.js');
var checked = 0;

function check(name, callback) {
    callback();
    checked++;
    console.log('PASS ' + name);
}

function clone(value) { return JSON.parse(JSON.stringify(value)); }

function migration() {
    return {
        format: transfer.FORMAT,
        version: transfer.VERSION,
        task: {
            taskType: 'MIGRATION', name: '业务迁移', batchSize: 1000,
            steps: [{ name: '客户', targetTable: 'CUSTOMER', cursorColumn: 'ID',
                insertMode: 'IGNORE_DUPLICATE', sourceSql: 'select ID, NAME from CUSTOMER' }],
            archive: null
        }
    };
}

function archive() {
    return {
        format: transfer.FORMAT,
        version: transfer.VERSION,
        task: {
            taskType: 'ARCHIVE', name: '业务归档', batchSize: 500, steps: [],
            archive: {
                mainTables: [
                    { name: '客户', nodeId: 'customer', sourceTable: 'CUSTOMER', targetTable: 'CUSTOMER_BAK',
                        cursorColumn: 'ID', sourceSql: 'select * from CUSTOMER where STATE = 0' },
                    { name: '员工', nodeId: 'staff', sourceTable: 'STAFF', targetTable: 'STAFF_BAK',
                        cursorColumn: 'ID', sourceSql: 'select * from STAFF' }
                ],
                childTables: [
                    { name: '订单', nodeId: 'order', parentNodeId: 'customer', sourceTable: 'ORDERS',
                        targetTable: 'ORDERS_BAK', cursorColumn: 'ID',
                        relations: [{ childColumn: 'CUSTOMER_ID', mainColumn: 'ID' }] },
                    { name: '明细', nodeId: 'detail', parentNodeId: 'order', sourceTable: 'DETAIL',
                        targetTable: 'DETAIL_BAK', cursorColumn: '',
                        relations: [{ childColumn: 'ORDER_ID', mainColumn: 'ID' }] },
                    { name: '权限', nodeId: 'permission', parentNodeId: 'staff', sourceTable: 'PERMISSION',
                        targetTable: 'PERMISSION_BAK', cursorColumn: '', relations: [] }
                ]
            }
        }
    };
}

function expectInvalid(factory, modify, pattern) {
    var value = factory();
    modify(value);
    assert.throws(function () { transfer.normalize(value); }, pattern);
}

check('迁移和多主表、多层子表归档配置 JSON 往返保留所有业务字段', function () {
    [migration(), archive()].forEach(function (input) {
        assert.deepStrictEqual(transfer.normalize(clone(transfer.normalize(input))), input);
    });
    var strict = migration();
    strict.task.steps[0].insertMode = 'STRICT_INSERT';
    assert.deepStrictEqual(transfer.normalize(strict), strict);
});

check('所有层只输出白名单字段，数据库配置和删除确认不会进入文件', function () {
    [migration(), archive()].forEach(function (input) {
        var expected = clone(input);
        function inject(value) {
            if (!value || typeof value !== 'object') { return; }
            if (Array.isArray(value)) { value.forEach(inject); return; }
            Object.keys(value).forEach(function (key) { inject(value[key]); });
            value.source = { password: 'source-secret', jdbcUrl: 'jdbc:source' };
            value.target = { password: 'target-secret', database: 'private-db' };
            value.database = 'private-db';
            value.password = 'plain-secret';
            value.jdbcUrl = 'jdbc:private';
            value.confirmSourceDelete = true;
        }
        inject(input);
        if (input.task.taskType === 'MIGRATION') {
            input.task.archive = { confirmSourceDelete: true, password: 'ignored' };
        } else {
            input.task.steps = [{ password: 'ignored' }];
        }
        var result = transfer.normalize(input);
        assert.deepStrictEqual(result, expected);
        assert(!/source-secret|target-secret|plain-secret|private-db|jdbc:private|confirmSourceDelete/.test(
            JSON.stringify(result)));
    });
});

check('深度冻结输入仍可处理，输出不共享对象和数组', function () {
    var input = archive();
    var snapshot = clone(input);
    function freeze(value) {
        if (!value || typeof value !== 'object') { return; }
        Object.keys(value).forEach(function (key) { freeze(value[key]); });
        Object.freeze(value);
    }
    freeze(input);
    var result = transfer.normalize(input);
    result.task.archive.childTables[0].relations[0].childColumn = 'CHANGED';
    result.task.archive.mainTables.push({});
    assert.deepStrictEqual(input, snapshot);
    var migrationInput = migration();
    freeze(migrationInput);
    transfer.normalize(migrationInput).task.steps[0].sourceSql = 'changed';
    assert.strictEqual(migrationInput.task.steps[0].sourceSql, 'select ID, NAME from CUSTOMER');
});

check('缺省名称和游标转为空字符串，空文本和空关联可保存草稿', function () {
    var input = migration();
    delete input.task.name;
    delete input.task.steps[0].name;
    delete input.task.steps[0].cursorColumn;
    input.task.steps[0].targetTable = '';
    input.task.steps[0].sourceSql = '';
    var result = transfer.normalize(input);
    assert.strictEqual(result.task.name, '');
    assert.strictEqual(result.task.steps[0].name, '');
    assert.strictEqual(result.task.steps[0].cursorColumn, '');
    var archiveInput = archive();
    archiveInput.task.archive.mainTables.forEach(function (table) {
        delete table.name;
        delete table.cursorColumn;
        table.sourceTable = '';
        table.targetTable = '';
        table.sourceSql = '';
    });
    archiveInput.task.archive.childTables.forEach(function (table) {
        delete table.name;
        delete table.cursorColumn;
        table.sourceTable = '';
        table.targetTable = '';
        table.relations = [];
    });
    assert.strictEqual(transfer.normalize(archiveInput).task.archive.childTables[0].relations.length, 0);
    archiveInput.task.archive.childTables = [];
    assert.strictEqual(transfer.normalize(archiveInput).task.archive.childTables.length, 0);
});

check('拒绝不支持的格式、版本、任务类型、写入模式及字段类型', function () {
    [null, [], 'text', 1].forEach(function (value) {
        assert.throws(function () { transfer.normalize(value); }, /配置文件.*对象/);
    });
    expectInvalid(migration, function (v) { v.format = 'other'; }, /format/);
    [0, 2, '1', null].forEach(function (version) {
        expectInvalid(migration, function (v) { v.version = version; }, /version/);
    });
    expectInvalid(migration, function (v) { v.task = []; }, /task.*对象/);
    expectInvalid(migration, function (v) { v.task.taskType = 'OTHER'; }, /taskType/);
    expectInvalid(migration, function (v) { v.task.name = null; }, /task.name.*字符串/);
    expectInvalid(migration, function (v) { v.task.steps[0].insertMode = 'IGNORE_DUPLICATES'; }, /insertMode/);
    expectInvalid(migration, function (v) { v.task.steps[0] = null; }, /steps\[0\].*对象/);
    expectInvalid(migration, function (v) { v.task.steps[0].sourceSql = 12; }, /steps\[0\].sourceSql.*字符串/);
    expectInvalid(migration, function (v) { delete v.task.steps[0].targetTable; }, /targetTable.*缺少/);
    expectInvalid(archive, function (v) { v.task.archive.childTables[0].relations[0].childColumn = {}; },
        /relations\[0\].childColumn.*字符串/);
    expectInvalid(archive, function (v) { v.task.archive.mainTables[0].cursorColumn = []; }, /cursorColumn.*字符串/);
    expectInvalid(archive, function (v) { v.task.archive.childTables = null; }, /childTables.*数组/);
});

check('按任务类型检查批次整数边界', function () {
    [migration, archive].forEach(function (factory) {
        [0, -1, 1.5, '1', null, NaN, Infinity].forEach(function (batch) {
            expectInvalid(factory, function (v) { v.task.batchSize = batch; }, /batchSize/);
        });
        var input = factory();
        input.task.batchSize = 1;
        assert.strictEqual(transfer.normalize(input).task.batchSize, 1);
    });
    expectInvalid(migration, function (v) { v.task.batchSize = 10001; }, /batchSize/);
    expectInvalid(archive, function (v) { v.task.batchSize = 501; }, /batchSize/);
    var maximum = migration();
    maximum.task.batchSize = 10000;
    assert.strictEqual(transfer.normalize(maximum).task.batchSize, 10000);
});

check('关联字段拒绝会破坏表单回填的映射分隔符，并准确定位字段', function () {
    ['childColumn', 'mainColumn'].forEach(function (field) {
        [',', '，', ';', '；', '='].forEach(function (separator) {
            expectInvalid(archive, function (v) {
                v.task.archive.childTables[0].relations[0][field] = 'ID' + separator + 'OTHER_ID';
            }, new RegExp('childTables\\[0\\].relations\\[0\\].' + field + '.*分隔符'));
        });
    });
    ['childColumn', 'mainColumn'].forEach(function (field) {
        ['', '   '].forEach(function (empty) {
            expectInvalid(archive, function (v) {
                v.task.archive.childTables[0].relations[0][field] = empty;
            }, new RegExp('childTables\\[0\\].relations\\[0\\].' + field + '.*不能为空'));
        });
    });
});

check('遵循后端步骤、表总数和关联组数量限制', function () {
    expectInvalid(migration, function (v) { v.task.steps = []; }, /steps.*至少/);
    expectInvalid(archive, function (v) { v.task.archive.mainTables = []; }, /mainTables.*至少/);
    expectInvalid(migration, function (v) {
        v.task.steps = Array.from({ length: 51 }, function () { return clone(v.task.steps[0]); });
    }, /steps.*50/);
    var maximum = migration();
    maximum.task.steps = Array.from({ length: 50 }, function () { return clone(maximum.task.steps[0]); });
    assert.strictEqual(transfer.normalize(maximum).task.steps.length, 50);
    expectInvalid(archive, function (v) {
        v.task.archive.childTables = Array.from({ length: 49 }, function (_, index) {
            var table = clone(v.task.archive.childTables[0]);
            table.nodeId = 'node-' + index;
            return table;
        });
    }, /archive.*总数.*50/);
    expectInvalid(archive, function (v) {
        v.task.archive.childTables[0].relations = Array.from({ length: 6 }, function () {
            return { childColumn: 'PARENT_ID', mainColumn: 'ID' };
        });
    }, /relations.*5/);
});

check('归档图拒绝空标识、重复节点、缺失上级、自引用和循环', function () {
    expectInvalid(archive, function (v) { v.task.archive.mainTables[0].nodeId = ' '; }, /nodeId.*不能为空/);
    expectInvalid(archive, function (v) { v.task.archive.mainTables[1].nodeId = 'customer'; }, /节点标识重复/);
    expectInvalid(archive, function (v) { v.task.archive.childTables[0].nodeId = 'customer'; }, /节点标识重复/);
    expectInvalid(archive, function (v) { v.task.archive.childTables[1].nodeId = 'order'; }, /节点标识重复/);
    expectInvalid(archive, function (v) { v.task.archive.childTables[0].parentNodeId = 'missing'; }, /上级节点不存在/);
    expectInvalid(archive, function (v) { v.task.archive.childTables[0].parentNodeId = 'order'; }, /不能指向自身/);
    expectInvalid(archive, function (v) { v.task.archive.childTables[0].parentNodeId = 'detail'; }, /循环关联/);
    var reversed = archive();
    reversed.task.archive.childTables.reverse();
    assert.deepStrictEqual(transfer.normalize(reversed), reversed);
});

check('特殊对象键和节点标识不会造成原型污染，继承字段不被采纳', function () {
    var input = archive();
    input.task.archive.mainTables[0].nodeId = '__proto__';
    input.task.archive.childTables[0].parentNodeId = '__proto__';
    input.task.archive.childTables[0].nodeId = 'constructor';
    input.task.archive.childTables[1].parentNodeId = 'constructor';
    var raw = JSON.stringify(input).replace('"version":1',
        '"version":1,"__proto__":{"polluted":true},"constructor":{"prototype":{"polluted":true}}');
    var result = transfer.normalize(JSON.parse(raw));
    assert.deepStrictEqual(result, input);
    assert.strictEqual({}.polluted, undefined);
    assert.strictEqual(Object.getPrototypeOf(result), Object.prototype);
    assert(!Object.prototype.hasOwnProperty.call(result, '__proto__'));
    var inheritedTask = Object.create(migration().task);
    var inherited = migration();
    inherited.task = inheritedTask;
    assert.throws(function () { transfer.normalize(inherited); }, /taskType.*缺少/);
});

check('浏览器全局与 CommonJS 使用同一公开接口', function () {
    var scriptPath = path.resolve(__dirname, '../../main/resources/static/config-transfer.js');
    var context = {};
    vm.runInNewContext(fs.readFileSync(scriptPath, 'utf8'), context);
    assert.strictEqual(context.MigrationConfigTransfer.FORMAT, transfer.FORMAT);
    assert.strictEqual(context.MigrationConfigTransfer.VERSION, transfer.VERSION);
    assert.strictEqual(context.MigrationConfigTransfer.MAX_FILE_BYTES, 5 * 1024 * 1024);
    assert.strictEqual(JSON.stringify(context.MigrationConfigTransfer.normalize(migration())), JSON.stringify(migration()));
});

console.log('Config transfer checks passed: ' + checked);
