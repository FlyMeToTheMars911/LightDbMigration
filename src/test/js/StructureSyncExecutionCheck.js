'use strict';

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
    throw new Error('This DOM check needs jsdom. Set LIGHTDB_DOM_MODULE to an installed jsdom module.');
}

const {JSDOM, VirtualConsole} = loadJsdom();
const projectRoot = path.resolve(__dirname, '..', '..', '..');
const staticDirectory = path.join(projectRoot, 'src', 'main', 'resources', 'static');

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

function tick() {
    return new Promise(function (resolve) { setImmediate(resolve); });
}

function deferred() {
    let resolve;
    const promise = new Promise(function (complete) { resolve = complete; });
    return {promise: promise, resolve: resolve};
}

function task(id, type) {
    return {
        id: id, name: '任务-' + id, taskType: type || 'MIGRATION', status: 'FAILED', steps: [],
        source: {type: 'MYSQL', endpoint: 'source.example/test', username: 'source-account'},
        target: {type: 'MYSQL', endpoint: 'target.example/test', username: 'target-account'},
        structureSyncDigest: 'digest-' + id,
        structureSyncScript: '-- plan ' + id + '\nALTER TABLE `FIXTURE_' + id + '` ADD COLUMN `VALUE` INT;',
        structureSyncExecution: {status: 'READY', totalStatements: 2, executedStatements: 0, message: ''},
        logs: [], lastLogSequence: 0
    };
}

function response(data) {
    return {ok: true, status: 200, json: async function () {
        return {success: true, data: JSON.parse(JSON.stringify(data))};
    }};
}

async function createBrowser() {
    const browserErrors = [];
    const virtualConsole = new VirtualConsole();
    virtualConsole.on('jsdomError', function (error) { browserErrors.push(error); });
    const dom = new JSDOM(fs.readFileSync(path.join(staticDirectory, 'index.html'), 'utf8'), {
        url: 'http://localhost/', runScripts: 'outside-only', virtualConsole: virtualConsole
    });
    const window = dom.window;
    const serverTasks = {A: task('A'), B: task('B', 'ARCHIVE'), C: task('C'), D: task('D')};
    const requests = [];
    const confirmations = [];
    let confirmed = true;
    let poll;
    let executeHandler = async function (id) {
        serverTasks[id].structureSyncExecution = {
            status: 'SUCCEEDED', totalStatements: 2, executedStatements: 2, message: '执行完成'
        };
        return response(serverTasks[id]);
    };
    let detailHandler = null;
    window.setInterval = function (callback) { poll = callback; return 1; };
    window.confirm = function (message) { confirmations.push(message); return confirmed; };
    window.fetch = async function (url, options) {
        const method = options && options.method || 'GET';
        const request = {url: url, method: method, body: options && options.body ? JSON.parse(options.body) : null};
        requests.push(request);
        if (method === 'GET' && url === '/api/system') { return response({version: 'structure-sync-check'}); }
        if (method === 'GET' && url === '/api/config/last') { return response(null); }
        if (method === 'GET' && url === '/api/tasks') { return response(Object.values(serverTasks)); }
        const detail = url.match(/^\/api\/tasks\/([^/?]+)\?afterLog=/);
        if (method === 'GET' && detail) {
            if (detailHandler) {
                const handled = detailHandler(detail[1]);
                if (handled) { return handled; }
            }
            return response(serverTasks[detail[1]]);
        }
        const execute = url.match(/^\/api\/tasks\/([^/?]+)\/structure-sync\/execute$/);
        if (method === 'POST' && execute) { return executeHandler(execute[1], request); }
        throw new Error('Unexpected request: ' + method + ' ' + url);
    };
    window.HTMLElement.prototype.scrollIntoView = function () {};
    for (const script of Array.from(window.document.querySelectorAll('script[src]'))) {
        const sourcePath = path.join(staticDirectory, script.getAttribute('src').replace(/^\//, ''));
        window.eval(fs.readFileSync(sourcePath, 'utf8'));
    }
    await waitFor(function () { return window.document.querySelectorAll('.task-card').length === 4; },
        'Task initialization did not complete');
    await tick();
    ['source', 'target'].forEach(function (prefix) {
        const values = {Type: 'MYSQL', Host: prefix + '-host', Port: '3306', Database: prefix + '_db',
            OracleMode: 'SERVICE', Username: prefix + '-user', Password: prefix + '-password', JdbcUrl: ''};
        Object.keys(values).forEach(function (suffix) {
            window.document.getElementById(prefix + suffix).value = values[suffix];
        });
    });
    return {dom: dom, window: window, document: window.document, tasks: serverTasks, requests: requests,
        confirmations: confirmations, browserErrors: browserErrors,
        confirm: function (value) { confirmed = value; },
        execute: function (handler) { executeHandler = handler; },
        details: function (handler) { detailHandler = handler; },
        poll: async function () { await poll(); await tick(); }};
}

async function openTask(browser, id) {
    browser.document.querySelector('[data-task-id="' + id + '"] [data-action="logs"]').click();
    await waitFor(function () {
        return browser.document.getElementById('logTaskName').textContent.startsWith(browser.tasks[id].name);
    }, 'Task logs did not open: ' + id);
    await tick();
}

function posts(browser) {
    return browser.requests.filter(function (request) { return request.method === 'POST'; });
}

async function run() {
    const browser = await createBrowser();
    const executeButton = browser.document.getElementById('executeStructureScriptButton');
    const status = browser.document.getElementById('structureSyncExecutionStatus');
    try {
        await openTask(browser, 'A');
        assert.strictEqual(executeButton.disabled, false, 'Ready script should be executable');
        assert.strictEqual(browser.document.getElementById('structureSyncScript').readOnly, true,
            'Generated script must stay read-only');
        browser.confirm(false);
        executeButton.click();
        await tick();
        assert.strictEqual(posts(browser).length, 0, 'Cancelling confirmation must not execute SQL');
        assert(browser.confirmations[0].includes('任务-A') && browser.confirmations[0].includes('target.example/test'),
            'Confirmation must identify the selected task and target');
        assert(browser.confirmations[0].includes('无法回滚'), 'Confirmation must describe partial DDL execution');
        assert(!browser.confirmations[0].includes('target-password'), 'Confirmation must not display a password');

        browser.confirm(true);
        const pending = deferred();
        browser.execute(function () { return pending.promise; });
        browser.document.getElementById('taskType').value = 'ARCHIVE';
        executeButton.click();
        await waitFor(function () { return posts(browser).length === 1; }, 'Confirmed execution was not submitted');
        const migrationRequest = posts(browser)[0];
        assert.strictEqual(migrationRequest.url, '/api/tasks/A/structure-sync/execute');
        assert.strictEqual(migrationRequest.body.confirmed, true);
        assert.strictEqual(migrationRequest.body.scriptDigest, 'digest-A');
        assert.strictEqual(migrationRequest.body.database.host, 'target-host',
            'Selected migration must use target fields even when form mode is archive');
        assert(!Object.prototype.hasOwnProperty.call(migrationRequest.body, 'script')
            && !Object.prototype.hasOwnProperty.call(migrationRequest.body, 'sql'),
            'Execution must not upload browser SQL');
        assert.strictEqual(executeButton.disabled, true, 'In-flight request must disable execution');
        executeButton.click();
        assert.strictEqual(posts(browser).length, 1, 'In-flight click must not duplicate execution');
        browser.tasks.A.structureSyncExecution.status = 'RUNNING';
        await browser.poll();
        assert(status.textContent.includes('执行中'), 'Polling must display running status');

        await openTask(browser, 'B');
        browser.tasks.A.structureSyncExecution = {status: 'SUCCEEDED', totalStatements: 2, executedStatements: 2};
        pending.resolve(response(browser.tasks.A));
        await tick();
        await tick();
        assert(browser.document.getElementById('logTaskName').textContent.startsWith('任务-B'),
            'Late execution response must not replace another selected task');
        assert(browser.document.getElementById('structureSyncScript').value.includes('plan B'));

        browser.document.getElementById('taskType').value = 'MIGRATION';
        browser.execute(async function (id) {
            browser.tasks[id].structureSyncExecution = {status: 'FAILED', totalStatements: 2,
                executedStatements: 1, message: '第二条执行失败'};
            return response(browser.tasks[id]);
        });
        executeButton.click();
        await waitFor(function () { return executeButton.textContent === '执行失败'; },
            'Partial execution failure was not shown');
        const archiveRequest = posts(browser)[1];
        assert.strictEqual(archiveRequest.body.database.host, 'source-host',
            'Selected archive must use source fields even when form mode is migration');
        assert(status.textContent.includes('第二条执行失败') && status.textContent.includes('1 / 2'));
        assert.strictEqual(executeButton.disabled, true, 'Failed script must not be replayed');
        const completedPostCount = posts(browser).length;
        executeButton.click();
        await tick();
        assert.strictEqual(posts(browser).length, completedPostCount, 'Partial script must not replay');

        await openTask(browser, 'A');
        assert.strictEqual(executeButton.textContent, '已执行');
        assert.strictEqual(executeButton.disabled, true, 'Succeeded script must not be replayed');
        assert(status.textContent.includes('重新创建任务'), 'Success must instruct a fresh preflight');

        await openTask(browser, 'C');
        browser.execute(async function () { throw new Error('fixture network interruption'); });
        executeButton.click();
        await waitFor(function () { return status.textContent.includes('结果待核实'); },
            'Unknown network result was not shown');
        assert.strictEqual(executeButton.disabled, true, 'Unknown request outcome must remain disabled');
        const uncertainPostCount = posts(browser).length;
        await browser.poll();
        executeButton.click();
        await tick();
        assert.strictEqual(posts(browser).length, uncertainPostCount, 'Network failure must not auto-retry');
        browser.tasks.C.structureSyncExecution = {status: 'SUCCEEDED', totalStatements: 2, executedStatements: 2};
        await browser.poll();
        assert.strictEqual(executeButton.textContent, '已执行', 'Polling must recover the final server result');

        browser.tasks.D.structureSyncExecution.totalStatements = 0;
        await openTask(browser, 'D');
        assert.strictEqual(executeButton.disabled, true, 'Comment-only suggestion cannot execute');
        assert(status.textContent.includes('没有可执行'), 'Comment-only suggestion needs an explanation');
        browser.tasks.D.structureSyncExecution.totalStatements = 2;
        browser.tasks.D.status = 'RUNNING';
        await browser.poll();
        assert.strictEqual(executeButton.disabled, true, 'Data task must be failed before executing a script');

        const lateDetails = deferred();
        browser.details(function (id) { return id === 'A' ? lateDetails.promise : null; });
        browser.document.querySelector('[data-task-id="A"] [data-action="logs"]').click();
        await openTask(browser, 'B');
        lateDetails.resolve(response(browser.tasks.A));
        await tick();
        await tick();
        assert(browser.document.getElementById('logTaskName').textContent.startsWith('任务-B'),
            'Late log response must not replace another selected task');
        assert(browser.document.getElementById('structureSyncScript').value.includes('plan B'));
        assert.strictEqual(posts(browser).filter(function (request) { return /\/start$/.test(request.url); }).length,
            0, 'Structure execution must not automatically start data migration');
        assert.deepStrictEqual(browser.browserErrors, [], 'Browser script emitted errors');
        console.log('STRUCTURE_SYNC_CONFIRMATION_EXECUTION_DOM_CHECK_PASSED');
    } finally {
        browser.dom.window.close();
    }
}

run().catch(function (error) { console.error(error); process.exitCode = 1; });
