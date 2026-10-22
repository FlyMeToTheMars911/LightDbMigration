'use strict';

// Install jsdom outside this checkout, then set LIGHTDB_DOM_MODULE to its module path if needed.
// Default fallback: npm install --prefix <system-temp>/lightdbmigration-config-dom-check jsdom@16.7.0
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

function loadJsdom() {
    const candidates = [process.env.LIGHTDB_DOM_MODULE, 'jsdom',
        path.join(os.tmpdir(), 'lightdbmigration-config-dom-check', 'node_modules', 'jsdom')];
    for (const candidate of candidates.filter(Boolean)) {
        try {
            return require(candidate);
        } catch (error) {
            if (error.code !== 'MODULE_NOT_FOUND') { throw error; }
        }
    }
    throw new Error('This DOM check needs jsdom. Install it in a temporary directory and set LIGHTDB_DOM_MODULE.');
}

const {JSDOM, VirtualConsole} = loadJsdom();
const projectRoot = path.resolve(__dirname, '..', '..', '..');
const staticDirectory = path.join(projectRoot, 'src', 'main', 'resources', 'static');
const databaseFields = ['Type', 'Host', 'Port', 'Database', 'OracleMode', 'Username', 'Password', 'JdbcUrl'];
const forbiddenKeys = new Set(['source', 'target', 'type', 'host', 'port', 'database', 'oracleMode',
    'username', 'password', 'jdbcUrl']);
const fileSizeLimit = 5 * 1024 * 1024;

function waitFor(predicate, message) {
    const deadline = Date.now() + 5000;
    return new Promise(function (resolve, reject) {
        function check() {
            if (predicate()) { resolve(); return; }
            if (Date.now() >= deadline) { reject(new Error(message)); return; }
            setTimeout(check, 5);
        }
        check();
    });
}

async function createBrowser(lastDatabaseConfig) {
    const browserErrors = [];
    const consoleMessages = [];
    const virtualConsole = new VirtualConsole();
    virtualConsole.on('jsdomError', function (error) { browserErrors.push(error); });
    ['info', 'warn', 'error', 'log'].forEach(function (level) {
        virtualConsole.on(level, function () { consoleMessages.push(Array.prototype.join.call(arguments, ' ')); });
    });
    const dom = new JSDOM(fs.readFileSync(path.join(staticDirectory, 'index.html'), 'utf8'), {
        url: 'http://localhost/', runScripts: 'outside-only', virtualConsole: virtualConsole
    });
    const window = dom.window;
    const requests = [];
    const downloads = [];
    const blobs = new Map();
    window.setInterval = function () { return 0; };
    window.fetch = async function (url, options) {
        const method = (options && options.method) || 'GET';
        requests.push({url: url, method: method});
        let data;
        if (method === 'GET' && url === '/api/system') { data = {version: 'dom-check'}; }
        else if (method === 'GET' && url === '/api/config/last') { data = lastDatabaseConfig || null; }
        else if (method === 'GET' && url === '/api/tasks') { data = []; }
        else { throw new Error('Unexpected network request in configuration transfer: ' + method + ' ' + url); }
        return {ok: true, status: 200, json: async function () { return {success: true, data: data}; }};
    };
    window.URL.createObjectURL = function (blob) {
        const url = 'blob:configuration-check-' + blobs.size;
        blobs.set(url, blob);
        return url;
    };
    window.URL.revokeObjectURL = function () {};
    window.HTMLAnchorElement.prototype.click = function () {
        downloads.push({filename: this.download, blob: blobs.get(this.href)});
    };
    window.confirm = function () { throw new Error('Configuration transfer unexpectedly requested confirmation'); };
    Array.from(window.document.querySelectorAll('script[src]')).forEach(function (script) {
        const scriptPath = path.join(staticDirectory, script.getAttribute('src').replace(/^\//, ''));
        window.eval(fs.readFileSync(scriptPath, 'utf8') + '\n//# sourceURL=' + scriptPath.replace(/\\/g, '/'));
    });
    await waitFor(function () {
        return window.document.querySelectorAll('#stepsContainer .migration-step').length === 1
            && requests.length === 3;
    }, 'Application initialization did not complete');
    await new Promise(function (resolve) { setImmediate(resolve); });
    return {dom: dom, window: window, document: window.document, requests: requests,
        downloads: downloads, browserErrors: browserErrors, consoleMessages: consoleMessages};
}

function setValue(browser, selector, value) {
    const element = browser.document.querySelector(selector);
    assert(element, 'Missing form control: ' + selector);
    element.value = value;
    return element;
}

function databaseSnapshot(browser) {
    const result = {};
    ['source', 'target'].forEach(function (prefix) {
        databaseFields.forEach(function (field) {
            result[prefix + field] = browser.document.getElementById(prefix + field).value;
        });
    });
    return result;
}

function verifyDatabaseTypes(browser, rememberedConfig) {
    ['source', 'target'].forEach(function (prefix) {
        const config = rememberedConfig[prefix];
        const rememberedPort = config.port == null ? '5236' : String(config.port);
        assert.strictEqual(browser.document.getElementById(prefix + 'Type').value, 'DM',
            'Saved Dameng database type was not restored');
        assert.strictEqual(browser.document.getElementById(prefix + 'Port').value, rememberedPort,
            'Saved Dameng port or its default was not restored');
        assert.strictEqual(browser.document.getElementById(prefix + 'Database').value, config.database,
            'Saved Dameng schema was not restored');
        assert.strictEqual(browser.document.getElementById(prefix + 'Username').value, config.username,
            'Saved Dameng username was not restored');
        assert.strictEqual(browser.document.getElementById(prefix + 'Password').value, config.password,
            'Saved Dameng password was not restored');
        const select = browser.document.getElementById(prefix + 'Type');
        [
            {type: 'ORACLE', port: '1521', label: '服务名 / SID', oracleHidden: false, jdbcPrefix: 'jdbc:oracle:'},
            {type: 'MYSQL', port: '3306', label: '数据库名', oracleHidden: true, jdbcPrefix: 'jdbc:mysql:'},
            {type: 'DM', port: '5236', label: '模式名 / Schema（选填）', oracleHidden: true, jdbcPrefix: 'jdbc:dm:'}
        ].forEach(function (expected) {
            select.value = expected.type;
            select.dispatchEvent(new browser.window.Event('change', {bubbles: true}));
            assert.strictEqual(select.value, expected.type, 'Database type is missing from the selector');
            assert.strictEqual(browser.document.getElementById(prefix + 'Port').value, expected.port);
            assert.strictEqual(browser.document.getElementById(prefix + 'DatabaseLabel').textContent, expected.label);
            assert.strictEqual(browser.document.getElementById(prefix + 'OracleModeWrap').classList.contains('hidden'),
                expected.oracleHidden, 'Oracle connection mode visibility is incorrect');
            assert(browser.document.getElementById(prefix + 'JdbcUrl').placeholder.startsWith(expected.jdbcPrefix),
                'JDBC URL help does not match the selected database type');
        });
        assert.strictEqual(browser.document.getElementById(prefix + 'Database').placeholder, '留空使用登录用户名');
    });
    console.log('DAMENG_DATABASE_TYPE_AND_REMEMBERED_CONFIG_UI_PASSED');
}

function fillDatabases(browser) {
    ['source', 'target'].forEach(function (prefix, index) {
        const values = [index === 0 ? 'DM' : 'MYSQL', prefix + '-retained-host',
            index === 0 ? '15236' : '13306', prefix + '_retained_db', 'SERVICE',
            prefix + '-retained-user', prefix + '-retained-secret', 'jdbc:retained:' + prefix];
        const type = setValue(browser, '#' + prefix + 'Type', values[0]);
        type.dispatchEvent(new browser.window.Event('change', {bubbles: true}));
        databaseFields.forEach(function (field, fieldIndex) {
            setValue(browser, '#' + prefix + field, values[fieldIndex]);
        });
    });
    return databaseSnapshot(browser);
}

function controlsSnapshot(browser, selector) {
    return Array.from(browser.document.querySelectorAll(selector + ' input, ' + selector + ' textarea, '
        + selector + ' select')).map(function (element) {
        return {id: element.id, className: element.className, value: element.value, checked: element.checked};
    });
}

function configurationSnapshot(browser) {
    return {
        type: browser.document.getElementById('taskType').value,
        name: browser.document.getElementById('taskName').value,
        batch: browser.document.getElementById('batchSize').value,
        batchMax: browser.document.getElementById('batchSize').max,
        migration: controlsSnapshot(browser, '#stepsContainer'),
        mains: controlsSnapshot(browser, '#archiveMainsContainer'),
        children: controlsSnapshot(browser, '#archiveChildrenContainer'),
        deletionConfirmed: browser.document.getElementById('archiveConfirmDelete').checked,
        nodeIds: Array.from(browser.document.querySelectorAll('[data-node-id]')).map(function (node) {
            return node.dataset.nodeId;
        }),
        migrationClass: browser.document.getElementById('migrationConfig').className,
        archiveClass: browser.document.getElementById('archiveConfig').className,
        databases: databaseSnapshot(browser)
    };
}

function readBlob(browser, blob) {
    return new Promise(function (resolve, reject) {
        const reader = new browser.window.FileReader();
        reader.onload = function () { resolve(reader.result); };
        reader.onerror = function () { reject(reader.error); };
        reader.readAsText(blob);
    });
}

async function exportConfiguration(browser) {
    const previousCount = browser.downloads.length;
    browser.document.getElementById('exportConfigButton').click();
    assert.strictEqual(browser.downloads.length, previousCount + 1, 'Export did not download a JSON file');
    const download = browser.downloads[browser.downloads.length - 1];
    assert(download.blob && /application\/json/.test(download.blob.type), 'Download is not a JSON blob');
    const text = await readBlob(browser, download.blob);
    return {filename: download.filename, text: text, config: JSON.parse(text)};
}

async function importFile(browser, file) {
    const input = browser.document.getElementById('configFileInput');
    Object.defineProperty(input, 'files', {configurable: true, value: file ? [file] : []});
    input.dispatchEvent(new browser.window.Event('change', {bubbles: true}));
    await waitFor(function () { return !browser.document.getElementById('importConfigButton').disabled; },
        'Import did not restore the import button');
    assert.strictEqual(input.value, '', 'File selection must reset so the same file can be imported again');
}

async function importText(browser, text) {
    const file = new browser.window.File([text], 'developer-config.json', {type: 'application/json'});
    await importFile(browser, file);
}

function assertNoDatabaseKeys(value) {
    if (value === null || typeof value !== 'object') { return; }
    Object.keys(value).forEach(function (key) {
        assert(!forbiddenKeys.has(key), 'Database connection field leaked into export: ' + key);
        assertNoDatabaseKeys(value[key]);
    });
}

function envelope(task) {
    return {format: 'light-db-migration-config', version: 1, task: task};
}

function archiveTask() {
    return {
        taskType: 'ARCHIVE', name: '跨主表多层归档', batchSize: 120,
        archive: {
            confirmSourceDelete: true,
            mainTables: [
                {name: '西区主表', nodeId: 'ROOT_WEST', sourceTable: 'WEST_MAIN', targetTable: 'WEST_MAIN_H',
                    cursorColumn: '', sourceSql: "SELECT * FROM WEST_MAIN WHERE CREATED_AT < '2025-01-01'"},
                {name: '东区主表', nodeId: 'ROOT_EAST', sourceTable: 'EAST_MAIN', targetTable: 'EAST_MAIN_H',
                    cursorColumn: 'ID', sourceSql: "SELECT * FROM EAST_MAIN WHERE CREATED_AT < '2025-01-01'"}
            ],
            childTables: [
                {name: '东区孙表', nodeId: 'LEAF_EAST', parentNodeId: 'BRANCH_EAST', sourceTable: 'EAST_LEAF',
                    targetTable: 'EAST_LEAF_H', cursorColumn: '',
                    relations: [{childColumn: 'DETAIL_ID', mainColumn: 'ID'}]},
                {name: '西区明细', nodeId: 'BRANCH_WEST', parentNodeId: 'ROOT_WEST', sourceTable: 'WEST_DETAIL',
                    targetTable: 'WEST_DETAIL_H', cursorColumn: '',
                    relations: [{childColumn: 'MAIN_ID', mainColumn: 'ID'}]},
                {name: '东区明细', nodeId: 'BRANCH_EAST', parentNodeId: 'ROOT_EAST', sourceTable: 'EAST_DETAIL',
                    targetTable: 'EAST_DETAIL_H', cursorColumn: 'DETAIL_ID',
                    relations: [{childColumn: 'TENANT_ID', mainColumn: 'TENANT_ID'},
                        {childColumn: 'MAIN_ID', mainColumn: 'ID'}]}
            ]
        }
    };
}

function archiveRelationships(browser) {
    const nodes = new Map();
    browser.document.querySelectorAll('.archive-main-table, .archive-child-table').forEach(function (element) {
        const main = element.classList.contains('archive-main-table');
        nodes.set(element.dataset.nodeId, element.querySelector(main
            ? '.archive-main-source-table' : '.archive-child-source-table').value);
    });
    const relationships = {};
    browser.document.querySelectorAll('.archive-child-table').forEach(function (element) {
        relationships[element.querySelector('.archive-child-source-table').value]
            = nodes.get(element.querySelector('.archive-child-parent-node').value);
    });
    return relationships;
}

async function verifyMigration(browser, retainedDatabases) {
    let pickerClicks = 0;
    browser.document.getElementById('configFileInput').addEventListener('click', function () { pickerClicks++; });
    browser.document.getElementById('importConfigButton').click();
    assert.strictEqual(pickerClicks, 1, 'Import button did not open the file selector');
    const expected = {taskType: 'MIGRATION', name: '客户迁移配置', batchSize: 275, steps: [
        {name: '客户基础', targetTable: 'TARGET_CUSTOMER', cursorColumn: 'ID', insertMode: 'STRICT_INSERT',
            sourceSql: "SELECT ID, NAME\nFROM SOURCE_CUSTOMER\nWHERE NAME = '中文示例'"},
        {name: '客户补充', targetTable: 'TARGET_EXTRA', cursorColumn: '', insertMode: 'IGNORE_DUPLICATE',
            sourceSql: 'SELECT ID, VALUE FROM SOURCE_EXTRA'}
    ]};
    setValue(browser, '#taskName', expected.name);
    setValue(browser, '#batchSize', expected.batchSize);
    browser.document.getElementById('addStepButton').click();
    browser.document.querySelectorAll('#stepsContainer .migration-step').forEach(function (element, index) {
        const step = expected.steps[index];
        [['.step-name', step.name], ['.target-table', step.targetTable], ['.cursor-column', step.cursorColumn],
            ['.insert-mode', step.insertMode], ['.source-sql', step.sourceSql]].forEach(function (entry) {
            element.querySelector(entry[0]).value = entry[1];
        });
    });
    setValue(browser, '.archive-main-name', '保留归档草稿');
    const archiveDraft = controlsSnapshot(browser, '#archiveMainsContainer');
    const exported = await exportConfiguration(browser);
    assert.strictEqual(exported.filename, 'migration-config.json');
    assert.strictEqual(exported.config.format, 'light-db-migration-config');
    assert.strictEqual(exported.config.version, 1);
    const actual = exported.config.task;
    assert.deepStrictEqual({taskType: actual.taskType, name: actual.name, batchSize: actual.batchSize,
        steps: actual.steps}, expected, 'Migration export did not preserve all configured steps');
    assertNoDatabaseKeys(exported.config);
    Object.values(retainedDatabases).filter(function (value) { return value.indexOf('retained') >= 0; })
        .forEach(function (secret) { assert(!exported.text.includes(secret), 'Database value leaked into export'); });

    setValue(browser, '#taskName', '将被导入替换');
    setValue(browser, '#batchSize', 3);
    setValue(browser, '#stepsContainer .source-sql', 'SELECT WRONG FROM WRONG_TABLE');
    browser.document.getElementById('archiveConfirmDelete').checked = true;
    exported.config.source = {host: 'must-not-apply-root'};
    exported.config.task.source = {type: 'MYSQL', host: 'must-not-apply-source', password: 'must-not-apply-secret'};
    exported.config.task.target = {type: 'ORACLE', port: 9999, jdbcUrl: 'jdbc:must-not-apply'};
    await importText(browser, '\uFEFF' + JSON.stringify(exported.config));
    assert(!browser.document.getElementById('toast').classList.contains('error'), 'Valid migration import failed');
    const roundTrip = (await exportConfiguration(browser)).config.task;
    assert.deepStrictEqual({taskType: roundTrip.taskType, name: roundTrip.name, batchSize: roundTrip.batchSize,
        steps: roundTrip.steps}, expected, 'Migration import did not restore the complete configuration');
    assert.deepStrictEqual(controlsSnapshot(browser, '#archiveMainsContainer'), archiveDraft,
        'Migration import overwrote the inactive archive draft');
    assert.strictEqual(browser.document.getElementById('archiveConfirmDelete').checked, false);
    assert.deepStrictEqual(databaseSnapshot(browser), retainedDatabases, 'Migration transfer changed connection fields');
    console.log('CONFIG_MIGRATION_UI_ROUND_TRIP_PASSED');
}

async function verifyArchive(browser, retainedDatabases) {
    const migrationDraft = controlsSnapshot(browser, '#stepsContainer');
    const fixture = archiveTask();
    await importText(browser, JSON.stringify(envelope(fixture)));
    assert(!browser.document.getElementById('toast').classList.contains('error'), 'Valid archive import failed');
    assert.strictEqual(browser.document.getElementById('taskType').value, 'ARCHIVE');
    assert.strictEqual(browser.document.getElementById('taskName').value, fixture.name);
    assert.strictEqual(browser.document.getElementById('batchSize').value, '120');
    assert.strictEqual(browser.document.getElementById('batchSize').max, '500');
    assert(browser.document.getElementById('migrationConfig').classList.contains('hidden'));
    assert(!browser.document.getElementById('archiveConfig').classList.contains('hidden'));
    assert.strictEqual(browser.document.getElementById('archiveConfirmDelete').checked, false,
        'Archive import restored another environment\'s delete confirmation');
    assert.deepStrictEqual(archiveRelationships(browser), {
        EAST_LEAF: 'EAST_DETAIL', WEST_DETAIL: 'WEST_MAIN', EAST_DETAIL: 'EAST_MAIN'
    }, 'Archive import lost out-of-order or cross-main parent relationships');
    assert.deepStrictEqual(controlsSnapshot(browser, '#stepsContainer'), migrationDraft,
        'Archive import overwrote the inactive migration draft');
    const exported = await exportConfiguration(browser);
    assert.strictEqual(exported.filename, 'archive-config.json');
    assertNoDatabaseKeys(exported.config);
    const archive = exported.config.task.archive;
    assert.strictEqual(archive.mainTables.length, 2);
    assert.strictEqual(archive.childTables.length, 3);
    assert.notStrictEqual(archive.confirmSourceDelete, true, 'Archive export carries deletion authorization');
    fixture.archive.mainTables.forEach(function (expected) {
        const actual = archive.mainTables.find(function (table) { return table.sourceTable === expected.sourceTable; });
        ['name', 'sourceTable', 'targetTable', 'cursorColumn', 'sourceSql'].forEach(function (key) {
            assert.strictEqual(actual[key], expected[key], 'Main table field lost: ' + key);
        });
    });
    fixture.archive.childTables.forEach(function (expected) {
        const actual = archive.childTables.find(function (table) { return table.sourceTable === expected.sourceTable; });
        ['name', 'sourceTable', 'targetTable', 'cursorColumn'].forEach(function (key) {
            assert.strictEqual(actual[key], expected[key], 'Child table field lost: ' + key);
        });
        assert.deepStrictEqual(actual.relations, expected.relations, 'Composite relation fields were not restored');
    });
    const sourceSql = browser.document.querySelector('.archive-main-sql');
    const originalSql = sourceSql.value;
    const downloadCount = browser.downloads.length;
    sourceSql.value = 'SELECT ' + 'x'.repeat(fileSizeLimit);
    browser.document.getElementById('exportConfigButton').click();
    assert.strictEqual(browser.downloads.length, downloadCount, 'Oversized export downloaded an unusable file');
    assert(browser.document.getElementById('toast').textContent.includes('5 MiB'),
        'Oversized export did not explain the file size limit');
    sourceSql.value = originalSql;
    await importText(browser, exported.text);
    assert.deepStrictEqual(archiveRelationships(browser), {
        EAST_LEAF: 'EAST_DETAIL', WEST_DETAIL: 'WEST_MAIN', EAST_DETAIL: 'EAST_MAIN'
    }, 'A repeated archive import changed the relationship graph');
    browser.document.getElementById('addArchiveMainButton').click();
    browser.document.getElementById('addArchiveChildButton').click();
    const nodes = Array.from(browser.document.querySelectorAll('[data-node-id]'));
    const nodeIds = nodes.map(function (node) { return node.dataset.nodeId; });
    assert.strictEqual(new Set(nodeIds).size, nodeIds.length, 'Adding tables after import created duplicate node IDs');
    nodes.forEach(function (node) {
        const control = node.querySelector('.archive-main-node-id, .archive-child-node-id');
        assert.strictEqual(control.value, node.dataset.nodeId, 'Visible node ID differs from its actual relation ID');
    });
    assert.deepStrictEqual(databaseSnapshot(browser), retainedDatabases, 'Archive transfer changed connection fields');
    console.log('CONFIG_ARCHIVE_GRAPH_UI_ROUND_TRIP_PASSED');
}

async function verifyInvalidFiles(browser) {
    browser.document.getElementById('archiveConfirmDelete').checked = true;
    const invalidFixtures = ['{', '', 'null', '[]'];
    const badVersion = envelope(archiveTask());
    badVersion.version = 999;
    invalidFixtures.push(JSON.stringify(badVersion));
    const badFormat = envelope(archiveTask());
    badFormat.format = 'unrelated-configuration-file';
    invalidFixtures.push(JSON.stringify(badFormat));
    const badBatch = envelope(archiveTask());
    badBatch.task.batchSize = 501;
    invalidFixtures.push(JSON.stringify(badBatch));
    const missingParent = envelope(archiveTask());
    missingParent.task.archive.childTables[0].parentNodeId = 'MISSING_PARENT';
    invalidFixtures.push(JSON.stringify(missingParent));
    const repeatedNode = envelope(archiveTask());
    repeatedNode.task.archive.childTables[0].nodeId = 'ROOT_EAST';
    invalidFixtures.push(JSON.stringify(repeatedNode));
    const cycle = envelope(archiveTask());
    cycle.task.archive.childTables[2].parentNodeId = 'LEAF_EAST';
    invalidFixtures.push(JSON.stringify(cycle));
    for (const text of invalidFixtures) {
        const before = configurationSnapshot(browser);
        await importText(browser, text);
        assert(browser.document.getElementById('toast').classList.contains('error'), 'Invalid file was accepted');
        assert.deepStrictEqual(configurationSnapshot(browser), before, 'Rejected import partially modified the form');
    }
    const beforeOversized = configurationSnapshot(browser);
    await importFile(browser, new browser.window.File(['x'.repeat(fileSizeLimit + 1)], 'too-large.json'));
    assert(browser.document.getElementById('toast').classList.contains('error'), 'Oversized import was accepted');
    assert.deepStrictEqual(configurationSnapshot(browser), beforeOversized, 'Oversized import changed the form');
    await importFile(browser, null);
    assert.deepStrictEqual(configurationSnapshot(browser), beforeOversized, 'Cancelled file selection changed the form');
    const NativeFileReader = browser.window.FileReader;
    for (const eventName of ['onerror', 'onabort']) {
        try {
            browser.window.FileReader = function () {
                this.readAsText = function () {
                    const reader = this;
                    Promise.resolve().then(function () { reader[eventName](); });
                };
            };
            await importText(browser, JSON.stringify(envelope(archiveTask())));
            assert(browser.document.getElementById('toast').classList.contains('error'),
                'File reading failure or cancellation was not reported');
            assert.deepStrictEqual(configurationSnapshot(browser), beforeOversized,
                'File reading failure or cancellation changed the form');
        } finally {
            browser.window.FileReader = NativeFileReader;
        }
    }
    console.log('CONFIG_INVALID_FILE_ATOMICITY_UI_PASSED');
}

async function main() {
    const rememberedConfig = {
        source: {type: 'DM', host: 'saved-source-host', port: 15236, database: '',
            username: 'SOURCE_SCHEMA', password: 'source-retained-secret', jdbcUrl: ''},
        target: {type: 'DM', host: 'saved-target-host', port: null, database: 'TARGET_SCHEMA',
            username: 'TARGET_USER', password: 'target-retained-secret', jdbcUrl: ''}
    };
    const browser = await createBrowser(rememberedConfig);
    try {
        const initialRequests = browser.requests.slice();
        verifyDatabaseTypes(browser, rememberedConfig);
        const retainedDatabases = fillDatabases(browser);
        await verifyMigration(browser, retainedDatabases);
        await verifyArchive(browser, retainedDatabases);
        await verifyInvalidFiles(browser);
        assert.deepStrictEqual(browser.requests, initialRequests,
            'Configuration transfer contacted an API, changed saved database configuration, or created/started a task');
        assert.deepStrictEqual(browser.browserErrors, [], 'Application raised browser errors');
        ['source-retained-secret', 'target-retained-secret'].forEach(function (secret) {
            assert(!browser.consoleMessages.join('\n').includes(secret), 'Transfer logging exposed a database password');
        });
        console.log('CONFIG_TRANSFER_DATABASE_AND_TASK_BOUNDARY_PASSED');
    } finally {
        browser.dom.window.close();
    }
}

main().catch(function (error) {
    console.error(error.stack || error);
    process.exitCode = 1;
});
