(() => {
  if (window.__testtrDiscovery) return;
  window.__testtrDiscovery = true;
  const candidates = 'input:not([type="hidden"]),button,select,textarea,a[href],[role="button"],[role="textbox"],[role="combobox"],[contenteditable="true"]';
  const attr = (key, value) => `[${key}="${CSS.escape(value)}"]`;
  const unique = selector => { try { return document.querySelectorAll(selector).length === 1; } catch { return false; } };
  function describe(el) {
    const name = el.labels?.[0]?.textContent?.trim() || el.getAttribute('aria-label') ||
      (el.getAttribute('aria-labelledby') || '').split(/\s+/).map(id => document.getElementById(id)?.textContent || '').join(' ').trim() ||
      el.getAttribute('placeholder') || (el.matches('button,a,[role="button"]') ? el.textContent.trim() : '') || el.getAttribute('name') || el.id || el.tagName.toLowerCase();
    const choices = ['data-testid','data-test','data-qa','id','name','aria-label'].filter(a => el.getAttribute(a)).map(a => attr(a, el.getAttribute(a)));
    let css = choices.find(unique), quality = css ? 'attribute' : 'structural';
    if (!css) {
      const parts = []; let node = el;
      while (node?.nodeType === 1) {
        const siblings = node.parentElement ? [...node.parentElement.children].filter(n => n.tagName === node.tagName) : [node];
        parts.unshift(node.tagName.toLowerCase() + (siblings.length > 1 ? `:nth-of-type(${siblings.indexOf(node) + 1})` : ''));
        css = parts.join(' > '); if (unique(css)) break; node = node.parentElement;
      }
    }
    const type = el.matches('select') ? 'select' : el.matches('input[type="checkbox"]') ? 'checkbox' : el.matches('input[type="radio"]') ? 'radio' : el.matches('button,[role="button"],input[type="submit"],input[type="button"]') ? 'button' : el.matches('input,textarea,[contenteditable="true"],[role="textbox"]') ? 'input' : 'text';
    return {name: name.replace(/\s+/g,' ').slice(0,160), css, type, quality, matches: document.querySelectorAll(css).length,
      visible: !!el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden', enabled: !el.matches(':disabled,[aria-disabled="true"]')};
  }
  function send(payload) { return window.__testtrEmit(payload).catch(e => { console.warn('Discovery recording failed', String(e)); }); }
  function scan() {
    return send({kind:'scan', objects:[...document.querySelectorAll(candidates)].map(describe)});
  }
  let timer, pendingSince = 0;
  function schedule() {
    if (!pendingSince) pendingSince = Date.now();
    clearTimeout(timer);
    timer = setTimeout(() => { pendingSince = 0; scan(); }, Date.now() - pendingSince > 1000 ? 0 : 250);
  }
  function start() {
    scan();
    new MutationObserver(schedule).observe(document.documentElement, {subtree:true,childList:true,attributes:true,characterData:true});
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, {once:true}); else start();
  for (const event of ['click','input','change']) document.addEventListener(event, e => {
    if (!e.isTrusted) return;
    const el = e.target.closest?.(candidates);
    if (!el || el.matches('input[type="password"]')) return;
    // Values, key presses and selected option text deliberately never leave the page.
    send({kind:'interaction', action:event === 'click' ? 'click' : el.matches('select') ? 'select' : el.matches('input[type="checkbox"],input[type="radio"]') ? 'check' : 'fill', object:describe(el)});
    schedule();
  }, true);
  addEventListener('popstate',schedule); addEventListener('hashchange',schedule);
  for (const method of ['pushState','replaceState']) {
    const original = history[method]; history[method] = function(...args) { const result = original.apply(this,args); schedule(); return result; };
  }
  window.__testtrScan = scan;
})();
