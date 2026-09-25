const fs = require('node:fs');
const assert = require('node:assert/strict');

const source = fs.readFileSync(
  'app/src/main/java/xiao/bu/tv/WebSourceView.java', 'utf8');
const popup = source.slice(source.indexOf('public boolean onCreateWindow'),
  source.indexOf('nextWebView.setDownloadListener'));

assert(!popup.includes('getHitTest()'),
  'Popup routing must not confuse an image src with the clicked link target');
assert(popup.includes('shouldOverrideUrlLoading') && popup.includes('onPageStarted'),
  'The popup must route the URL produced by the page/WebView navigation');

assert(source.includes(
  'MULTI_WEBVIEW_MIN_AVAILABLE_BYTES = 200L * 1024L * 1024L'));
assert(source.includes(
  'memory.availMem > MULTI_WEBVIEW_MIN_AVAILABLE_BYTES'),
  'Exactly 200 MiB is not enough; the requirement is strictly greater than 200 MiB');
assert(source.includes('retainedTabWebViews.put(tab, current)'));
assert(source.includes('releaseRetainedWebViews(true)'));
assert(source.includes('releaseRetainedWebViews(false)'));

console.log('PASS popup intent routing and memory-gated per-tab WebView retention');
