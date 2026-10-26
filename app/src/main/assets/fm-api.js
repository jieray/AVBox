/**
 * fm-api.js —— HTML 脚本（type 6）运行时的桥接层。
 *
 * 注入时机：页面加载完成后由 HtmlActivity 注入。
 * 脚本用法（与 Spider 接口一一对应，脚本自己返回数据，不是渲染宫格）：
 *
 *   function homeContent(filter) {
 *       var html = fm.req('https://...');          // 同步请求，自动走站点代理
 *       return { class: [...], list: [...] };     // 直接 return 即可
 *   }
 *
 * 也可以异步：fm.done({ class: [...], list: [...] });
 *
 * 说明：fm.req 是【同步】的（Java 侧 OkHttp 同步执行后把正文返回给 JS），
 * 这样脚本不需要写 Promise / await，与 py、js 脚本的写法保持一致。
 */
(function () {
  if (window.fm) return;

  var fm = {};
  var _done = false;

  fm.log = function (m) {
    try { window.FmBridge.log(String(m)); } catch (e) {}
  };

  fm.siteKey = function () {
    try { return window.FmBridge.siteKey(); } catch (e) { return ''; }
  };

  fm.ext = function () {
    try { return window.FmBridge.ext(); } catch (e) { return ''; }
  };

  /**
   * 同步 HTTP 请求。走 App 自己的网络栈，所以站点代理开关对它同样生效。
   * @param {string} url
   * @param {object} opt  { method, headers:{}, body }
   * @returns {string} 响应正文；失败返回空串
   */
  fm.req = function (url, opt) {
    opt = opt || {};
    var headers = '';
    try { headers = JSON.stringify(opt.headers || {}); } catch (e) {}
    try {
      return window.FmBridge.req(url, headers, opt.method || 'GET', opt.body || '');
    } catch (e) {
      fm.log('fm.req failed: ' + e);
      return '';
    }
  };

  fm.get = function (url, headers) {
    return fm.req(url, { method: 'GET', headers: headers });
  };

  fm.post = function (url, body, headers) {
    return fm.req(url, { method: 'POST', body: body, headers: headers });
  };

  /** 站点 ext 持久化（对应修改扩展参数弹窗写入的值） */
  fm.getVar = function (k, def) {
    try {
      var v = window.FmBridge.getVar(k);
      return (v === '' || v === null || v === undefined) ? def : v;
    } catch (e) { return def; }
  };

  fm.setVar = function (k, v) {
    try { window.FmBridge.setVar(k, String(v)); } catch (e) {}
  };

  /** 回传结果（异步场景用；同步 return 的场景不必调） */
  fm.done = function (result) {
    if (_done) return;
    _done = true;
    try {
      window.FmBridge.done(typeof result === 'string' ? result : JSON.stringify(result == null ? {} : result));
    } catch (e) {
      fm.log('fm.done failed: ' + e);
      try { window.FmBridge.done('{}'); } catch (e2) {}
    }
  };

  window.fm = fm;
})();
