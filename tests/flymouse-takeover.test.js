const fs = require('fs'), vm = require('vm'), assert = require('assert/strict');
const source = fs.readFileSync('app/src/main/assets/control/js/pages/flymouse.js', 'utf8');
const sent = [], elements = {}, messages = [];
let bridgeCalls = 0, gyroStopped = false;
const context = {
  state: {settings: {remoteCatalogUrl: 'http://controller:9966', flyMouseEnabled: false},
    remotePlayback: {sourceUrl: 'rtsp://controller/cast'}},
  pointerWasTakenOver: false, gyroRunning: true,
  NtvPointerQueue: function () {},
  XMLHttpRequest: function () {
    this.open = (method, path) => sent.push({method, path});
    this.setRequestHeader = () => {};
    this.send = () => { this.readyState = 4; this.status = 200; this.responseText = '{"ok":true}'; this.onreadystatechange(); };
  },
  document: {getElementById: id => elements[id] || (elements[id] = {})},
  setSensorStatus: text => messages.push(text),
  stopGyroscope: () => { gyroStopped = true; },
  NtvChannelPicker: {update() {}},
  api: () => { throw Error('Must not enable flymouse on a taken-over receiver'); }
};
context.NtvPointerQueue.sendLocal = () => { bridgeCalls++; return false; };
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('function flyMouseTakenOver('), source.indexOf('function pointerMove(')), context);
vm.runInContext(source.slice(source.indexOf('function renderPageState('), source.indexOf('\nsetupTouchpad();')), context);
let failure;
context.sendPointerRequest('/api/pointer', {action: 'move', dx: 30}, e => { failure = e; });
assert.match(failure.message, /当前设备被接管/);
assert.equal(sent.length, 0);
assert.equal(bridgeCalls, 0, 'Block before native bridge or controller redirect');
context.renderPageState();
assert.match(elements.touchpad.textContent, /当前设备被接管/);
assert(gyroStopped);
assert.equal(context.state.settings.flyMouseEnabled, false);
context.state.settings = {remoteCatalogUrl: '', flyMouseEnabled: true};
context.renderPageState();
assert.match(messages.at(-1), /接管已结束/);
assert(!elements.touchpad.textContent.includes('请勿使用飞鼠'));
context.sendPointerRequest('/api/pointer', {action: 'move', dx: 30}, e => { failure = e; });
assert.equal(failure, null);
assert.equal(bridgeCalls, 1);
assert.deepEqual(sent, [{method: 'POST', path: '/api/pointer'}]);
console.log('PASS receiver blocks HTTP/native forwarding, takeover notice, gyro stop and input recovery');
