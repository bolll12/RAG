'use strict';
const $ = id => document.getElementById(id);
const el = (tag, value = '', className = '') => {
  const node = document.createElement(tag);
  node.textContent = value;
  if (className) node.className = className;
  return node;
};
const params = new URLSearchParams(location.search);
let collection = params.get('collection') || 'default';
let pageId = params.get('page');
let generation = 0;
let reading = 0;
let pollTimer;
let modelEnabled = false;
let asking = false;
let pageList = [];
let lastPublishedJob;
const kinds = {source: '来源摘要', topic: '主题知识', answer: '问答沉淀'};
const statusNames = {ready: '已更新', stale: '待更新'};
$('collection').value = collection;
$('scope-label').textContent = collection;
function showError(error) {
  $('error').textContent = error.message || String(error);
  $('error').hidden = false;
}
async function request(path, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 330000);
  const headers = {...options.headers};
  if ($('access-key').value.trim()) headers.Authorization = `Bearer ${$('access-key').value.trim()}`;
  try {
    const response = await fetch(path, {...options, headers, signal: controller.signal});
    if (!response.ok) {
      const data = await response.json();
      throw new Error(response.status === 401 ? '请在「连接设置」填写服务访问密钥，再重新连接。' : data.detail || '请求失败，请重试。');
    }
    return options.markdown ? await response.text() : await response.json();
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('模型响应超时，问题已保留，请稍后重试。');
    if (error instanceof TypeError) throw new Error('无法连接服务，请稍后重新连接。');
    throw error;
  } finally { clearTimeout(timeout); }
}
const scopeQuery = () => `collection=${encodeURIComponent(collection)}`;
const pageUrl = id => `/wiki?${scopeQuery()}&page=${encodeURIComponent(id)}`;
function updateLocation() {
  history.replaceState(null, '', pageId ? pageUrl(pageId) : `/wiki?${scopeQuery()}`);
  try { sessionStorage.setItem('rag.collection', collection); } catch (_) {}
}
function pageLink(id, title) {
  const link = el('a', title);
  link.href = pageUrl(id);
  link.addEventListener('click', event => {
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    openPage(id).catch(showError);
  });
  return link;
}
function renderList() {
  $('page-list').replaceChildren();
  const query = $('search').value.trim().toLocaleLowerCase();
  const visible = pageList.filter(page => page.title.toLocaleLowerCase().includes(query));
  $('page-count').textContent = `${pageList.length} 个知识页${query ? ` · 找到 ${visible.length} 个` : ''}`;
  visible.forEach(page => {
    const link = pageLink(page.id, page.title);
    link.append(el('small', `${kinds[page.kind]} · ${statusNames[page.status]}`, page.status === 'stale' ? 'stale' : ''));
    if (page.id === pageId) link.setAttribute('aria-current', 'page');
    $('page-list').append(link);
  });
  if (!visible.length) $('page-list').append(el('p', query ? '没有匹配的知识页。' : '整理完成后，知识页会出现在这里。', 'subtle'));
}
async function loadPages() {
  const seq = generation;
  const result = await request(`/api/wiki/pages?${scopeQuery()}`);
  if (seq !== generation) return;
  pageList = result;
  renderList();
  if (pageId && !pageList.some(page => page.id === pageId)) {
    pageId = null;
    updateLocation();
    emptyReader();
  }
}
function emptyReader() {
  const box = el('div', '', 'wiki-empty');
  box.append(el('span', '▤'), el('h2', '你的知识目录，从这里开始'),
    el('p', '从左侧选择一个知识页，或点击「生成 / 更新 Wiki」整理当前知识库的资料。'));
  $('reader').replaceChildren(box);
}
function renderFacts(facts, container) {
  if (!facts.length) container.append(el('p', '本页尚未提取到带来源的知识。', 'subtle'));
  facts.forEach(fact => {
    const item = el('section', '', 'wiki-fact');
    item.append(el('p', fact.text));
    const references = el('details');
    references.append(el('summary', `核对原文 · ${fact.citations.length} 处引用`));
    fact.citations.forEach(source => {
      references.append(el('p', `${source.title} · 版本 ${source.version} · 字符 ${source.start}–${source.end}`), el('blockquote', source.quote));
      references.append(el('p', `来源文件：${source.source}`, 'subtle'));
    });
    item.append(references);
    container.append(item);
  });
}
function renderRelations(title, links, container) {
  container.append(el('h3', title));
  const box = el('div', '', 'wiki-links');
  links.forEach(link => box.append(pageLink(link.id, link.title)));
  if (!links.length) box.append(el('span', '暂无', 'subtle'));
  container.append(box);
}
async function openPage(id) {
  const seq = ++reading;
  const scope = generation;
  const data = await request(`/api/wiki/pages/${encodeURIComponent(id)}?${scopeQuery()}`);
  if (seq !== reading || scope !== generation) return;
  pageId = id;
  updateLocation(); renderList();
  const reader = $('reader');
  reader.replaceChildren(el('h2', data.title), el('p', `${kinds[data.kind]} · 版本 ${data.version} · ${statusNames[data.status]}`, 'subtle'));
  if (data.status === 'stale') reader.append(el('p', '来源已更新或删除。这是历史内容，暂不用于 Wiki 问答，请重新整理。', 'wiki-warning'));
  const actions = el('div', '', 'wiki-page-actions');
  const download = el('button', '导出 Markdown', 'secondary');
  download.type = 'button';
  const exportUrl = `/api/wiki/pages/${encodeURIComponent(id)}/export?${scopeQuery()}`;
  download.addEventListener('click', async () => {
    download.disabled = true;
    try {
      const content = await request(exportUrl, {markdown: true});
      const url = URL.createObjectURL(new Blob([content], {type: 'text/markdown;charset=utf-8'}));
      const link = el('a'); link.href = url; link.download = `${data.title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 80)}.md`;
      document.body.append(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) { showError(error); }
    finally { download.disabled = false; }
  });
  actions.append(download); reader.append(actions);
  renderFacts(data.content.facts, reader);
  renderRelations('关联页面', data.links, reader);
  renderRelations('链接到本页', data.backlinks, reader);
  const history = el('details', '', 'wiki-history');
  history.append(el('summary', '查看历史版本（最近 20 版）'));
  const revisions = el('div'); history.append(revisions); reader.append(history);
  let loaded = false;
  const revisionUrl = `/api/wiki/pages/${encodeURIComponent(id)}/revisions?${scopeQuery()}`;
  history.addEventListener('toggle', async () => {
    if (!history.open || loaded) return;
    loaded = true;
    try {
      const versions = await request(revisionUrl);
      versions.forEach(version => {
        const detail = el('details');
        detail.append(el('summary', `版本 ${version.version} · ${version.created_at}`));
        const content = el('div');
        content.append(el('p', '历史快照，仅供对照。', 'subtle'));
        renderFacts(version.content.content.facts, content);
        detail.append(content); revisions.append(detail);
      });
    } catch (error) { loaded = false; showError(error); }
  });
}
async function loadStatus() {
  const seq = generation;
  const data = await request(`/api/wiki/status?${scopeQuery()}`);
  if (seq !== generation) return;
  modelEnabled = data.enabled;
  const working = data.job && ['queued', 'running'].includes(data.job.status);
  $('build').disabled = !modelEnabled || working;
  $('build').textContent = working ? '正在整理…' : '生成 / 更新 Wiki';
  $('ask').disabled = !modelEnabled || asking;
  $('model-label').textContent = modelEnabled ? `整理模型：${data.model}` : '生成模型尚未连接';
  $('notice').textContent = !modelEnabled
    ? '请由管理员连接生成模型后刷新页面，即可自动整理资料。已有知识页仍可浏览。'
    : data.stale > 0 ? `${data.stale} 个页面的来源发生变化，更新完成后会重新用于 Wiki 问答。`
      : data.auto_update ? '新增、更新或删除文档后会自动整理 Wiki。首次使用请点击生成，阅读现有资料。'
        : '点击「生成 / 更新 Wiki」整理现有资料；文档变化后，请手动更新 Wiki。';
  $('job').hidden = !data.job;
  if (data.job) {
    const labels = {queued: '排队中', running: '整理中', completed: '已完成', failed: '未完成', superseded: '来源已变化'};
    $('job-message').textContent = `${labels[data.job.status]} · ${data.job.message}`;
    $('job-progress').max = Math.max(data.job.total, 1);
    $('job-progress').value = data.job.status === 'completed' ? Math.max(data.job.total, 1) : data.job.done;
    if (data.job.status === 'completed' && lastPublishedJob !== data.job.id) {
      lastPublishedJob = data.job.id;
      await loadPages();
      if (pageId) await openPage(pageId);
    }
  }
  clearTimeout(pollTimer);
  pollTimer = setTimeout(() => {
    Promise.all([loadPages(), loadStatus()]).catch(showError);
  }, working ? 2000 : 20000);
}
async function refresh() {
  clearTimeout(pollTimer);
  $('error').hidden = true;
  try {
    await Promise.all([loadPages(), loadStatus()]);
    if (pageId) await openPage(pageId);
  } catch (error) { showError(error); }
}
$('scope-form').addEventListener('submit', event => {
  event.preventDefault();
  if (!$('collection').reportValidity()) return;
  generation++; reading++; collection = $('collection').value; pageId = null; lastPublishedJob = null;
  $('scope-label').textContent = collection; $('search').value = ''; $('answer').replaceChildren();
  $('lint-results').hidden = true; $('job').hidden = true; pageList = []; renderList(); emptyReader(); updateLocation(); refresh();
});
$('search-form').addEventListener('submit', event => { event.preventDefault(); renderList(); });
$('search').addEventListener('input', renderList);
$('refresh').addEventListener('click', refresh);
$('build').addEventListener('click', async () => {
  const seq = generation;
  $('error').hidden = true; $('build').disabled = true;
  try {
    await request('/api/wiki/build', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({collection})});
    if (seq === generation) await loadStatus();
  } catch (error) { if (seq === generation) { showError(error); $('build').disabled = !modelEnabled; } }
});
$('lint').addEventListener('click', async () => {
  const seq = generation;
  $('lint').disabled = true;
  try {
    const result = await request(`/api/wiki/lint?${scopeQuery()}`);
    if (seq !== generation) return;
    const panel = $('lint-results'); panel.hidden = false;
    panel.replaceChildren(el('h2', `已检查 ${result.pages_checked} 个页面`), el('p', result.scope, 'subtle'));
    if (!result.issues.length) panel.append(el('p', '未发现结构或来源状态问题。', 'subtle'));
    result.issues.forEach(issue => {
      const row = el('div', '', 'wiki-issue'); row.append(pageLink(issue.page_id, issue.title), el('p', issue.message)); panel.append(row);
    });
  } catch (error) { if (seq === generation) showError(error); }
  finally { $('lint').disabled = false; }
});
$('ask-form').addEventListener('submit', async event => {
  event.preventDefault();
  const question = $('question').value.trim();
  if (asking || !question) return;
  const seq = generation;
  asking = true; $('ask').disabled = true; $('ask').textContent = '正在整理回答…'; $('error').hidden = true;
  try {
    const result = await request('/api/wiki/ask', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({question, collection, save: $('save-answer').checked})});
    if (seq !== generation) return;
    const answer = $('answer'); answer.replaceChildren(el('h3', question));
    if (result.facts.length) renderFacts(result.facts, answer);
    else answer.append(el('p', result.answer));
    if (result.page_id) {
      answer.append(pageLink(result.page_id, '已保存为知识页 · 点击查看 →'));
      await loadPages();
    }
  } catch (error) { if (seq === generation) showError(error); }
  finally { asking = false; $('ask').disabled = !modelEnabled; $('ask').textContent = '查询 Wiki ↑'; }
});
$('chat-link').addEventListener('click', () => { try { sessionStorage.setItem('rag.collection', collection); } catch (_) {} });
refresh();
