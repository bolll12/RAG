'use strict';
const $ = (id) => document.getElementById(id);
let busy = false;
let collection = 'default';
try { collection = sessionStorage.getItem('rag.collection') || 'default'; } catch (_) {}
$('collection').value = collection;
$('active-collection').textContent = collection;
$('upload-target').textContent = collection;
$('wiki-link').href = `/wiki?collection=${encodeURIComponent(collection)}`;
let libraryRequest = 0;
let turnNumber = 0;
const strategyLabels = { paragraph: '段落优先', fixed: '固定长度', sentence: '句子优先' };
const modes = { generated: '模型回答', extractive: '原文摘录', refused: '证据不足' };
try { $('show-process').checked = localStorage.getItem('rag.showProcess') !== 'false'; } catch (_) {}
$('show-process').addEventListener('change', () => {
  try { localStorage.setItem('rag.showProcess', String($('show-process').checked)); } catch (_) {}
  document.querySelectorAll('.process-detail').forEach(node => { node.hidden = !$('show-process').checked; });
});
const text = (tag, content, className) => {
  const node = document.createElement(tag);
  node.textContent = content;
  if (className) node.className = className;
  return node;
};
async function request(path, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 120000);
  const headers = { ...options.headers };
  const key = $('access-key').value.trim();
  if (key) headers.Authorization = `Bearer ${key}`;
  try {
    const response = await fetch(path, { ...options, headers, signal: controller.signal });
    const data = await response.json();
    if (!response.ok) {
      if (response.status === 401) throw new Error('请在「查询设置」填写正确的服务访问密钥，再重试。');
      throw new Error(typeof data.detail === 'string' ? data.detail : `请求失败（${response.status}），请检查输入后重试。`);
    }
    return data;
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('请求等待超过 2 分钟，请稍后重试。');
    if (error instanceof TypeError) throw new Error('无法连接服务，请检查服务是否运行，再重试。');
    throw error;
  } finally { clearTimeout(timer); }
}
async function loadLibrary() {
  const sequence = ++libraryRequest;
  const selected = collection;
  $('library-status').textContent = '正在读取文档…';
  $('documents').replaceChildren();
  try {
    const docs = await request(`/documents?collection=${encodeURIComponent(selected)}`);
    if (sequence !== libraryRequest) return;
    $('library-status').textContent = docs.length ? `${docs.length} 份文档可供检索` : '该知识库还没有文档，请在下方上传。';
    docs.forEach((doc) => {
      const row = document.createElement('li');
      const content = text('div', '', 'document-content');
      content.append(text('span', doc.title, 'document-title'), text('small', `版本 ${doc.version} · ${strategyLabels[doc.chunk_strategy] || '段落优先'} · ${doc.chunk_size || 800}/${doc.chunk_overlap ?? 120}`, 'document-version'));
      const actions = text('div', '', 'document-actions');
      for (const [action, label] of [['update', '更新'], ['delete', '删除']]) {
        const button = text('button', label, action === 'delete' ? 'document-delete' : '');
        button.type = 'button';
        button.setAttribute('aria-label', `${label}文档 ${doc.title}`);
        button.addEventListener('click', () => openDocumentAction(action, doc, selected));
        actions.append(button);
      }
      content.append(actions);
      row.append(text('span', '▤', 'doc-icon'), content);
      row.title = `${doc.source} · 版本 ${doc.version}`;
      $('documents').append(row);
    });
  } catch (error) {
    if (sequence === libraryRequest) $('library-status').textContent = error.message;
  }
}
let documentAction = null;
let documentActionBusy = false;
function openDocumentAction(action, doc, scope) {
  documentAction = { action, doc, scope };
  $('document-dialog-title').textContent = action === 'update' ? '更新文档' : '删除文档';
  $('document-dialog-description').textContent = action === 'update'
    ? `替换「${doc.title}」（${scope}，版本 ${doc.version}）。新内容入库成功后生效。`
    : `确定删除「${doc.title}」（${scope}）？文档及其检索片段将被删除，此操作无法撤销。`;
  $('replacement-fields').hidden = action !== 'update';
  $('replace-chunk-fields').disabled = action !== 'update';
  $('replacement-file').required = action === 'update';
  $('replacement-file').value = '';
  $('replace-strategy').value = doc.chunk_strategy || 'paragraph';
  $('replace-size').value = doc.chunk_size || 800;
  $('replace-overlap').value = doc.chunk_overlap ?? 120;
  updateChunkHelp('replace');
  $('document-confirm').textContent = action === 'update' ? '更新并重新入库' : '确认删除';
  $('document-confirm').classList.toggle('danger', action === 'delete');
  $('document-action-error').hidden = true;
  $('document-dialog').showModal();
  if (action === 'delete') $('document-cancel').focus();
}
$('document-cancel').addEventListener('click', () => $('document-dialog').close());
$('document-dialog').addEventListener('cancel', (event) => {
  if (documentActionBusy) event.preventDefault();
});
$('document-action-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!documentAction || documentActionBusy) return;
  const { action, doc, scope } = documentAction;
  documentActionBusy = true;
  $('document-action-error').hidden = true;
  $('document-confirm').disabled = true;
  $('document-cancel').disabled = true;
  $('replacement-file').disabled = true;
  $('document-confirm').textContent = action === 'update' ? '正在重新入库…' : '正在删除…';
  try {
    let message;
    if (action === 'update') {
      const file = $('replacement-file').files[0];
      if (!file) throw new Error('请选择替换文件。');
      if (!/\.(md|txt|pdf|html|htm|doc|docx)$/i.test(file.name)) throw new Error('仅支持 MD、TXT、HTML、Word（DOC/DOCX）和 PDF。');
      if (!file.size || file.size > 10 * 1024 * 1024) throw new Error('文件不能为空，且不能超过 10 MB。');
      const form = new FormData();
      form.append('file', file);
      form.append('collection', scope);
      form.append('source', doc.source);
      appendChunkOptions(form, 'replace');
      const result = await request('/documents/upload', { method: 'POST', body: form });
      message = result.unchanged ? '内容未变化，无需重复入库。' : `文档已更新至版本 ${result.version}。`;
    } else {
      await request(`/documents/${encodeURIComponent(doc.id)}`, { method: 'DELETE' });
      message = '文档已删除。';
    }
    $('document-status').textContent = `${message} 已有回答保留历史内容，请重新提问获取最新结果。`;
    $('document-status').hidden = false;
    $('document-dialog').close();
    await loadLibrary();
  } catch (error) {
    $('document-action-error').textContent = error.message;
    $('document-action-error').hidden = false;
  } finally {
    documentActionBusy = false;
    $('document-confirm').disabled = false;
    $('document-cancel').disabled = false;
    $('replacement-file').disabled = false;
    $('document-confirm').textContent = action === 'update' ? '更新并重新入库' : '确认删除';
  }
});
async function refresh() {
  try {
    const status = await request('/health');
    $('connection').textContent = '服务已连接';
    $('connection-dot').classList.add('online');
    $('mode').textContent = status.generation === 'model' ? '模型问答已启用' : '原文摘录模式';
    $('mode-notice').hidden = status.generation === 'model';
    $('mode-notice').textContent = status.reranking === 'jev'
      ? `Jev（${status.jev_model}）已启用：判断并重排检索证据。当前回答为原文摘录，Jev 不生成回答文本。`
      : '当前返回知识库原文摘录；尚未启用生成模型。';
  } catch (error) {
    $('connection').textContent = '服务未连接';
    $('connection-dot').classList.remove('online');
    $('mode').textContent = '连接不可用';
    $('mode-notice').hidden = false;
    $('mode-notice').textContent = error.message;
  }
  await loadLibrary();
}
function answerContent(answer, target, prefix, citations) {
  const valid = new Set(citations.map((item) => item.citation));
  // 所有模型文本使用 textContent/文本节点，绝不作为 HTML 注入。
  const pattern = /\[(\d+)\]/g;
  let end = 0;
  for (const match of answer.matchAll(pattern)) {
    target.append(document.createTextNode(answer.slice(end, match.index)));
    const id = Number(match[1]);
    if (valid.has(id)) {
      const button = text('button', match[0], 'citation-link');
      button.type = 'button';
      button.setAttribute('aria-label', `查看引用 ${id}`);
      button.addEventListener('click', () => {
        const source = $(`${prefix}-source-${id}`);
        source.open = true;
        source.scrollIntoView({ behavior: 'smooth', block: 'center' });
        source.querySelector('summary').focus({ preventScroll: true });
      });
      target.append(button);
    } else target.append(document.createTextNode(match[0]));
    end = match.index + match[0].length;
  }
  target.append(document.createTextNode(answer.slice(end)));
}
function renderAnswer(question, data, scope) {
  const prefix = `turn-${++turnNumber}`;
  const turn = text('article', '', 'turn');
  turn.append(text('h2', question, 'question-bubble'));
  const heading = text('div', '', 'answer-heading');
  heading.append(text('span', '✳'), text('span', '知识助手'), text('small', `${modes[data.mode] || '回答'} · ${scope}`));
  turn.append(heading);
  if (data.process) {
    const process = data.process;
    const jev = process.jev || {};
    const detail = text('details', '', 'source process-detail');
    detail.hidden = !$('show-process').checked;
    detail.append(text('summary', `检索与判断过程 · ${jev.called ? '已调用 Jev' : jev.enabled ? '无候选，未调用 Jev' : 'Jev 未启用'}`));
    detail.append(text('p', `${process.retrieval}${process.embed_model ? ` · ${process.embed_model}` : ''}`, 'source-meta'));
    if (jev.called) {
      detail.append(text('p', `${jev.model} · ${jev.request_count || 1} 次请求 · ${jev.duration_ms} ms`));
      detail.append(text('p', `评估 ${jev.candidate_count} 个候选，${jev.passed_count} 个达到阈值 ${jev.threshold}；最终使用 ${process.evidence_count} 个证据片段。`));
      detail.append(text('p', '判断标准：片段是否提供能直接回答问题的证据；仅关键词提及、目录或课程预告不算。返回值为 noul 判断概率，不是回答正确率。'));
      const list = text('ul', '', 'process-judgments');
      (jev.judgments || []).forEach(item => {
        list.append(text('li', `${item.title} · ${item.chunk_id} · ${Number(item.probability).toFixed(3)} · ${item.passed ? '通过阈值' : '已过滤'}`));
      });
      detail.append(list);
    }
    detail.append(text('p', `结果：${modes[data.mode] || data.mode} · 总耗时 ${process.total_ms} ms。以上是实际处理记录，接口不返回模型内部思考。`, 'source-meta'));
    turn.append(detail);
  }
  const body = text('div', '', 'answer-body');
  answerContent(data.answer, body, prefix, data.citations);
  turn.append(body);
  if (data.citations.length) {
    const sources = text('div', '', 'sources');
    sources.append(text('p', `${data.citations.length} 个参考片段 · 点击展开原文`));
    data.citations.forEach((source) => {
      const detail = text('details', '', 'source');
      detail.id = `${prefix}-source-${source.citation}`;
      detail.append(text('summary', `[${source.citation}] ${source.title}`));
      detail.append(text('p', `${source.source} · 版本 ${source.version} · 字符 ${source.start}–${source.end}`, 'source-meta'));
      detail.append(text('pre', source.text));
      sources.append(detail);
    });
    turn.append(sources);
  }
  $('messages').append(turn);
  while ($('messages').children.length > 20) $('messages').firstElementChild.remove();
  turn.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
$('ask-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const question = $('question').value.trim();
  if (busy || !question || !applyCollection()) return;
  busy = true;
  const scope = collection;
  $('error').hidden = true;
  $('send').disabled = true;
  $('send').textContent = '查询中…';
  $('new-chat').disabled = true;
  $('question').readOnly = true;
  $('pending').hidden = false;
  $('welcome').hidden = true;
  $('pending').scrollIntoView({ block: 'center', behavior: 'smooth' });
  try {
    const data = await request('/ask', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, collection: scope, top_k: Number($('top-k').value) })
    });
    renderAnswer(question, data, scope);
    $('question').value = '';
  } catch (error) {
    $('error').textContent = error.message + ' 问题已保留，可再次发送。';
    $('error').hidden = false;
    $('welcome').hidden = $('messages').children.length > 0;
  } finally {
    busy = false;
    $('send').disabled = false;
    $('send').textContent = '发送 ↑';
    $('new-chat').disabled = false;
    $('question').readOnly = false;
    $('pending').hidden = true;
    $('question').focus({ preventScroll: true });
  }
});
$('question').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    $('ask-form').requestSubmit();
  }
});
$('new-chat').addEventListener('click', () => {
  $('messages').replaceChildren();
  $('welcome').hidden = false;
  $('error').hidden = true;
  $('question').value = '';
  $('question').focus();
});
document.querySelectorAll('[data-question]').forEach((button) => button.addEventListener('click', () => {
  $('question').value = button.dataset.question;
  $('question').focus();
}));
$('collection-form').addEventListener('submit', (event) => {
  event.preventDefault();
  applyCollection();
});
function applyCollection() {
  if (!$('collection').reportValidity()) return false;
  const next = $('collection').value;
  if (next === collection) return true;
  collection = next;
  try { sessionStorage.setItem('rag.collection', collection); } catch (_) {}
  $('document-status').hidden = true;
  $('active-collection').textContent = collection;
  $('upload-target').textContent = collection;
  $('wiki-link').href = `/wiki?collection=${encodeURIComponent(collection)}`;
  loadLibrary();
  return true;
}
$('collection').addEventListener('change', applyCollection);
let uploading = false;
$('upload-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (uploading || !applyCollection()) return;
  const files = Array.from($('upload-files').files);
  if (!files.length) return;
  const target = collection;
  const chunkParameters = new FormData();
  appendChunkOptions(chunkParameters, 'upload');
  uploading = true;
  $('upload-chunk-fields').disabled = true;
  $('upload-submit').disabled = true;
  $('upload-files').disabled = true;
  $('collection').disabled = true;
  $('collection-form').querySelector('button').disabled = true;
  $('upload-results').replaceChildren();
  let successes = 0;
  try {
    for (const [index, file] of files.entries()) {
      $('upload-submit').textContent = `正在入库 ${index + 1}/${files.length}…`;
      const row = text('li', `${file.name}：正在上传并建立索引…`);
      $('upload-results').append(row);
      try {
        if (!/\.(md|txt|pdf|html|htm|doc|docx)$/i.test(file.name)) throw new Error('仅支持 MD、TXT、HTML、Word（DOC/DOCX）和 PDF。');
        if (file.size > 10 * 1024 * 1024) throw new Error('文件超过 10 MB。');
        if (!file.size) throw new Error('文件为空。');
        const form = new FormData();
        form.append('file', file);
        form.append('collection', target);
        for (const [key, value] of chunkParameters) form.append(key, value);
        const result = await request('/documents/upload', { method: 'POST', body: form });
        row.textContent = result.unchanged
          ? `${file.name}：已存在相同内容，无需重复入库。`
          : `${file.name}：已入库，版本 ${result.version}，${result.chunks} 个片段。`;
        row.className = 'upload-success';
        successes++;
      } catch (error) {
        row.textContent = `${file.name}：${error.message} 可重新选择文件重试。`;
        row.className = 'upload-failure';
      }
    }
    if (successes === files.length) $('upload-files').value = '';
  } finally {
    uploading = false;
    $('upload-chunk-fields').disabled = false;
    $('upload-submit').disabled = false;
    $('upload-submit').textContent = '上传到当前知识库';
    $('upload-files').disabled = false;
    $('collection').disabled = false;
    $('collection-form').querySelector('button').disabled = false;
    await loadLibrary();
  }
});
function appendChunkOptions(form, prefix) {
  form.append('chunk_strategy', $(`${prefix}-strategy`).value);
  form.append('chunk_size', $(`${prefix}-size`).value);
  form.append('chunk_overlap', $(`${prefix}-overlap`).value);
}
function updateChunkHelp(prefix) {
  const descriptions = {
    paragraph: '优先在换行或中文句号处切分，适合制度、手册。',
    fixed: '按指定字符数切分，不考虑句子边界，适合比较切片参数。',
    sentence: '优先在句末标点处切分，适合叙述性文本；超长句按长度上限切开。'
  };
  $(`${prefix}-strategy-help`).textContent = descriptions[$(`${prefix}-strategy`).value];
  $(`${prefix}-overlap`).max = Math.max(0, Number($(`${prefix}-size`).value) - 1);
}
for (const prefix of ['upload', 'replace']) {
  $(`${prefix}-strategy`).addEventListener('change', () => updateChunkHelp(prefix));
  $(`${prefix}-size`).addEventListener('input', () => updateChunkHelp(prefix));
}
$('refresh').addEventListener('click', refresh);
refresh();
