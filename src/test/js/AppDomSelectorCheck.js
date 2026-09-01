'use strict';

const fs = require('fs');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..', '..', '..');
const appPath = path.join(projectRoot, 'src', 'main', 'resources', 'static', 'app.js');
const htmlPath = path.join(projectRoot, 'src', 'main', 'resources', 'static', 'index.html');
const appSource = fs.readFileSync(appPath, 'utf8');
const htmlSource = fs.readFileSync(htmlPath, 'utf8');

function assertCondition(condition, message) {
    if (!condition) {
        throw new Error(message);
    }
}

function functionSource(functionName) {
    const pattern = new RegExp('function ' + functionName + '\\([^)]*\\) \\{([\\s\\S]*?)\\n    \\}');
    const match = appSource.match(pattern);
    assertCondition(match, '未找到前端函数：' + functionName);
    return match[0];
}

function compileFrontendFunction(functionName, documentMock) {
    const factory = new Function('document', 'MIGRATION_STEP_SELECTOR',
        'return (' + functionSource(functionName) + ');');
    return factory(documentMock, '#stepsContainer .migration-step');
}

assertCondition(htmlSource.indexOf('class="migration-step archive-main-table"') >= 0,
    '回归前提不成立：归档主表不再复用 migration-step 样式类');
assertCondition(appSource.indexOf("var MIGRATION_STEP_SELECTOR = '#stepsContainer .migration-step';") >= 0,
    '普通迁移步骤选择器必须限定在 stepsContainer 内');
assertCondition(!/document\.querySelectorAll\(\s*['"]\.migration-step['"]\s*\)/.test(appSource),
    '禁止使用全页面 .migration-step 查询，否则会混入归档配置卡片');

['renumberSteps', 'collectSteps'].forEach(function (functionName) {
    assertCondition(functionSource(functionName).indexOf('document.querySelectorAll(MIGRATION_STEP_SELECTOR)') >= 0,
        functionName + ' 未使用限定范围的普通迁移步骤选择器');
});

const titleControl = {textContent: ''};
const nameControl = {value: '', placeholder: ''};
const migrationControls = {
    '.migration-step-title': titleControl,
    '.step-name': nameControl,
    '.target-table': {value: 'TARGET_TABLE'},
    '.cursor-column': {value: 'ID'},
    '.insert-mode': {value: 'STRICT_INSERT'},
    '.source-sql': {value: 'SELECT * FROM SOURCE_TABLE'}
};
const migrationStep = {
    querySelector: function (selector) {
        return migrationControls[selector] || null;
    }
};
const archiveStep = {
    querySelector: function () {
        throw new Error('普通迁移流程错误访问了归档配置卡片');
    }
};
const documentMock = {
    querySelectorAll: function (selector) {
        return selector === '#stepsContainer .migration-step' ? [migrationStep] : [migrationStep, archiveStep];
    }
};

compileFrontendFunction('renumberSteps', documentMock)();
assertCondition(titleControl.textContent === 'SQL 1', '迁移步骤编号结果不正确');
assertCondition(nameControl.placeholder === '步骤 1', '迁移步骤名称占位符不正确');

const collectedSteps = compileFrontendFunction('collectSteps', documentMock)();
assertCondition(collectedSteps.length === 1, '普通迁移步骤数量不正确');
assertCondition(collectedSteps[0].name === 'SQL 1', '普通迁移步骤默认名称不正确');
assertCondition(collectedSteps[0].targetTable === 'TARGET_TABLE', '普通迁移目标表读取不正确');
assertCondition(collectedSteps[0].cursorColumn === 'ID', '普通迁移游标字段读取不正确');
assertCondition(collectedSteps[0].insertMode === 'STRICT_INSERT', '普通迁移写入方式读取不正确');
assertCondition(collectedSteps[0].sourceSql === 'SELECT * FROM SOURCE_TABLE', '普通迁移SQL读取不正确');

console.log('MIGRATION_STEP_SELECTOR_SCOPE_PASSED');
