const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const flymouse = fs.readFileSync(
  'app/src/main/assets/control/js/pages/flymouse.js', 'utf8');
const main = fs.readFileSync(
  'app/src/main/java/xiao/bu/tv/MainActivity.java', 'utf8');
const server = fs.readFileSync(
  'app/src/main/java/xiao/bu/tv/LocalControlServer.java', 'utf8');

const gestureEnd = flymouse.slice(flymouse.indexOf('function end(cancelled)'),
  flymouse.indexOf('pad.addEventListener(', flymouse.indexOf('function end(cancelled)')));
assert(gestureEnd.includes('pointerAction("rightclick")'));
assert(!gestureEnd.includes('openSmartContextMenu()'),
  'A two-finger tap must emit a real secondary click before the smart menu is inspected');

let clicked = '', notice = '', copied = '';
const parent = { removeChild(node) { node.parentNode = null; } };
const context = vm.createContext({
  state: { browserAction: { id: 1, type: 'download',
    value: '/api/browser/download?id=1', message: '已在手机打开图片下载' } },
  browserActionReady: true,
  lastBrowserActionId: 0,
  sessionStorage: { setItem() {} },
  document: {
    hidden: false,
    body: { appendChild(node) { node.parentNode = parent; } },
    createElement() { return { style: {}, click() { clicked = this.href; } }; }
  },
  navigator: { clipboard: { writeText(value) { copied = value; return Promise.resolve(); } } },
  window: { isSecureContext: true },
  toast(message) { notice = message; },
  setTimeout(callback) { callback(); }
});
vm.runInContext(flymouse.slice(flymouse.indexOf('function handleBrowserAction('),
  flymouse.indexOf('function switchControlMode(')), context);
context.handleBrowserAction();
assert.equal(clicked, '/api/browser/download?id=1');
assert.equal(notice, '已在手机打开图片下载');

context.state.browserAction = { id: 2, type: 'clipboard', value: '选中文字',
  message: '文字已复制' };
context.handleBrowserAction();
assert.equal(copied, '选中文字');
assert.equal(notice, '文字已复制');

assert(main.includes('showFlyMouseSmartContext();'));
assert(main.includes('root.put("browserAction", browserActionJson())'));
assert(main.includes('MAX_BROWSER_IMAGE_DOWNLOAD_BYTES = 24 * 1024 * 1024'));
assert(server.includes('Content-Disposition: attachment; filename='));
assert(server.includes('"/api/browser/download".equals(path)'));

console.log('PASS two-finger right click, phone download event and clipboard notice');
