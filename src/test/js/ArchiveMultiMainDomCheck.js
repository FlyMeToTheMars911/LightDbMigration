'use strict';

const fs = require('fs');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..', '..', '..');
const appSource = fs.readFileSync(path.join(projectRoot, 'src', 'main', 'resources', 'static', 'app.js'), 'utf8');
const htmlSource = fs.readFileSync(path.join(projectRoot, 'src', 'main', 'resources', 'static', 'index.html'), 'utf8');

function assertCondition(condition, message) {
    if (!condition) {
        throw new Error(message);
    }
}

assertCondition(htmlSource.indexOf('id="archiveMainsContainer"') >= 0,
    '归档页面缺少多主表容器');
assertCondition(htmlSource.indexOf('id="addArchiveMainButton"') >= 0,
    '归档页面缺少添加主业务表按钮');
assertCondition(htmlSource.indexOf('id="archiveMainTemplate"') >= 0,
    '归档页面缺少主业务表模板');
assertCondition(htmlSource.indexOf('id="archiveMainName"') < 0,
    '归档页面仍残留单主表固定控件');
assertCondition(appSource.indexOf("var ARCHIVE_MAIN_NODE_PREFIX = 'MAIN_';") >= 0,
    '主业务表节点未使用独立递增前缀');
assertCondition(appSource.indexOf("mainTables: Array.prototype.map.call(document.querySelectorAll('.archive-main-table')")
    >= 0, '创建请求未收集全部主业务表');
assertCondition(appSource.indexOf("document.querySelectorAll('.archive-main-table').forEach") >= 0,
    '从表上级节点选项未包含全部主业务表');
assertCondition(appSource.indexOf("showToast('至少保留一张主业务表'") >= 0,
    '页面未限制至少保留一张主业务表');
assertCondition(appSource.indexOf('addArchiveMain();') < appSource.indexOf('addArchiveChild();'),
    '页面初始化应先创建主业务表再创建默认从表');

console.log('ARCHIVE_MULTIPLE_MAIN_TABLE_DOM_CHECK_PASSED');
