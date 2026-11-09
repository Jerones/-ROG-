// ============================================================
// 施工安全规范智能问答系统 前端逻辑（原生 JS，无框架）
// 两个菜单：对话（chat） / 知识库（kb），通过 switchMenu 切换
// 对话走 SSE 流式接口：token 事件拼正文，citations 事件带引用
// ============================================================

const $ = (id) => document.getElementById(id);

// ---------------- 菜单切换 ----------------
function switchMenu(name) {
    $('menuChat').classList.toggle('active', name === 'chat');
    $('menuKb').classList.toggle('active', name === 'kb');
    $('viewChat').classList.toggle('hidden', name !== 'chat');
    $('viewKb').classList.toggle('hidden', name !== 'kb');
    if (name === 'kb') {
        loadStats();
        loadDocuments();
    }
}

function scrollMessages() {
    const box = $('messages');
    box.scrollTop = box.scrollHeight;
}

// ---------------- 对话 ----------------
$('questionInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendQuestion();
    }
});

// 输入框高度随内容自适应
$('questionInput').addEventListener('input', (e) => {
    e.target.style.height = 'auto';
    e.target.style.height = Math.min(e.target.scrollHeight, 160) + 'px';
});

/** 示例问题胶囊：点击直接发送。 */
function askExample(q) {
    $('questionInput').value = q;
    sendQuestion();
}

/**
 * 追加一条消息。
 * user：右对齐浅蓝气泡；assistant：通栏纯文本 + 左侧小蓝圆点标识，
 * 正文与引用都挂在 .msg-body 里。
 */
function appendMessage(role, text) {
    const wrap = document.createElement('div');
    wrap.className = 'msg ' + role;
    if (role === 'assistant') {
        const dot = document.createElement('span');
        dot.className = 'avatar-dot';
        wrap.appendChild(dot);
        const body = document.createElement('div');
        body.className = 'msg-body';
        const bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.textContent = text;
        body.appendChild(bubble);
        wrap.appendChild(body);
    } else {
        const bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.textContent = text;
        wrap.appendChild(bubble);
    }
    $('msgCol').appendChild(wrap);
    scrollMessages();
    return wrap;
}

/** 渲染引用溯源：折叠卡片，第一条默认展开，其余点击展开。 */
function renderCitations(msgWrap, citations) {
    if (!citations || citations.length === 0) return;
    const body = msgWrap.querySelector('.msg-body') || msgWrap;
    const box = document.createElement('div');
    box.className = 'citations';
    citations.forEach((c, i) => {
        const card = document.createElement('div');
        card.className = 'citation-card' + (i === 0 ? ' open' : '');
        const pageText = (c.page !== null && c.page !== undefined) ? ' · 第' + c.page + '页' : '';
        card.innerHTML =
            '<div class="citation-head"><span class="citation-title">[' + (i + 1) + '] ' +
            escapeHtml(c.docName) + ' · ' + escapeHtml(c.clause) + pageText + '</span>' +
            '<span class="citation-right"><span class="citation-score">相似度 ' +
            Number(c.score).toFixed(3) + '</span><span class="citation-arrow">▾</span></span></div>' +
            '<div class="citation-snippet">' + escapeHtml(c.snippet) + '</div>';
        card.querySelector('.citation-head').addEventListener('click', () => {
            card.classList.toggle('open');
        });
        box.appendChild(card);
    });
    body.appendChild(box);
    scrollMessages();
}

async function sendQuestion() {
    const input = $('questionInput');
    const question = input.value.trim();
    if (!question) return;
    input.value = '';
    input.style.height = 'auto';
    const empty = $('chatEmpty');
    if (empty) empty.classList.add('hidden');
    appendMessage('user', question);

    const btn = $('sendBtn');
    btn.disabled = true;
    $('sendIcon').classList.add('hidden');
    $('stopIcon').classList.remove('hidden');
    const msgWrap = appendMessage('assistant', '');
    const bubble = msgWrap.querySelector('.bubble');

    try {
        // 调用流式接口：逐个接收 token 事件拼接正文，
        // 结束时 citations 事件带回引用列表
        const resp = await fetch('/api/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                question: question,
                topK: Number($('topkSelect').value)
            })
        });
        if (!resp.ok || !resp.body) {
            // 后端统一返回 {status, message}，把原因原样显示，别只丢一个 500 给用户
            let reason = 'HTTP ' + resp.status;
            try {
                const err = await resp.json();
                if (err && err.message) reason = err.message;
            } catch (e) { /* 不是 JSON 就用状态码 */ }
            throw new Error(reason);
        }

        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        let citations = [];
        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            // SSE 以空行分隔事件
            const parts = buffer.split('\n\n');
            buffer = parts.pop();
            for (const part of parts) {
                const ev = parseSse(part);
                if (!ev) continue;
                if (ev.event === 'token') {
                    bubble.textContent += ev.data;
                    scrollMessages();
                } else if (ev.event === 'citations') {
                    try { citations = JSON.parse(ev.data); } catch (e) { /* 忽略解析失败 */ }
                }
            }
        }
        if (!bubble.textContent) bubble.textContent = '（无回答内容）';
        renderCitations(msgWrap, citations);
    } catch (err) {
        bubble.textContent = '请求出错：' + err.message;
    } finally {
        btn.disabled = false;
        $('sendIcon').classList.remove('hidden');
        $('stopIcon').classList.add('hidden');
    }
}

/** 解析一个 SSE 事件块（多行 data: 需要拼接）。 */
function parseSse(block) {
    let event = 'message';
    const dataLines = [];
    for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
    }
    if (dataLines.length === 0) return null;
    return { event: event, data: dataLines.join('\n') };
}

// ---------------- 知识库 ----------------
async function loadStats() {
    try {
        const s = await (await fetch('/api/kb/stats')).json();
        // 知识库页统计卡片
        $('statDocs').textContent = s.documentCount;
        $('statChunks').textContent = s.chunkCount;
        $('statDim').textContent = s.vectorDimension > 0 ? s.vectorDimension : '—';
        $('statModel').textContent = s.embeddingModel;
        $('statStore').textContent = s.vectorStore;
        // 边栏小卡片与状态提示
        $('sideStatDocs').textContent = s.documentCount;
        $('sideStatChunks').textContent = s.chunkCount;
        $('demoBadge').classList.toggle('hidden', !s.demoMode);
        $('modeHint').textContent = (s.demoMode ? '演示模式' : '真实模型') + ' · ' + s.vectorStore;
        $('storeFoot').textContent = String(s.vectorStore).replace(/VectorStore$/, '') + ' · ' + (s.vectorDimension > 0 ? s.vectorDimension + ' 维' : '—');
    } catch (e) { /* 统计加载失败不阻塞页面 */ }
}

async function loadDocuments() {
    const tbody = $('docTableBody');
    try {
        const docs = await (await fetch('/api/kb/documents')).json();
        if (!docs || docs.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="empty">暂无文档，请先上传</td></tr>';
            return;
        }
        tbody.innerHTML = '';
        for (const d of docs) {
            const tr = document.createElement('tr');
            tr.innerHTML =
                '<td class="doc-name-cell">' +
                '  <button class="doc-name-link" data-act="view" title="查看入库后的块原文">' +
                escapeHtml(d.docName) + '</button>' +
                '</td>' +
                '<td class="nowrap">' + formatSize(d.fileSize) + '</td>' +
                '<td class="nowrap">' + d.chunkCount + '</td>' +
                '<td class="nowrap">' + formatTime(d.ingestedAt) + '</td>' +
                '<td class="nowrap">' + escapeHtml(d.status) + '</td>' +
                '<td class="doc-actions">' +
                '  <button class="btn-view" data-act="view">查看</button>' +
                '  <button class="btn-danger" data-act="del">删除</button>' +
                '</td>';
            tr.querySelectorAll('[data-act="view"]').forEach((btn) => {
                btn.onclick = () => openChunks(d.docId, d.docName);
            });
            tr.querySelector('[data-act="del"]').onclick = () => deleteDoc(d.docId);
            tbody.appendChild(tr);
        }
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="6" class="empty">加载失败：' + escapeHtml(e.message) + '</td></tr>';
    }
}

// ---------- 导入内容：拖拽 / 文件夹 / 粘贴 → 预检 → 后台任务 ----------
const dropZone = $('dropZone');
const fileInput = $('fileInput');
const dirInput = $('dirInput');
const uploadList = $('uploadList');
/** 待入库队列（用户还没点"确认入库"） */
let pendingFiles = [];
/** 预检结果缓存：key = 文件名|大小 */
const previewCache = new Map();
/** 当前轮询中的任务 */
let pollingJobId = null;

function fileKey(f) {
    return f.name + '|' + f.size;
}

dropZone.addEventListener('click', (e) => {
    if (e.target === fileInput || e.target === dirInput) return;
    fileInput.click();
});
fileInput.addEventListener('change', () => {
    addPending(fileInput.files);
    fileInput.value = '';
});
dirInput.addEventListener('change', () => {
    addPending(dirInput.files);
    dirInput.value = '';
});
dropZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropZone.classList.add('dragging');
});
dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragging'));
dropZone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropZone.classList.remove('dragging');
    if (e.dataTransfer && e.dataTransfer.files) addPending(e.dataTransfer.files);
});

const ACCEPT_EXT = ['pdf', 'doc', 'docx', 'wps', 'txt', 'md', 'markdown', 'ppt', 'pptx', 'xls', 'xlsx', 'html', 'htm'];

function addPending(fileList) {
    const rejected = [];
    for (const f of fileList) {
        const ext = (f.name.split('.').pop() || '').toLowerCase();
        if (!ACCEPT_EXT.includes(ext)) {
            rejected.push(f.name);
            continue;
        }
        if (pendingFiles.some((p) => p.name === f.name && p.size === f.size)) continue;
        pendingFiles.push(f);
    }
    $('uploadTip').textContent = rejected.length
        ? '已跳过不支持的文件：' + rejected.slice(0, 3).join('、') + (rejected.length > 3 ? ' 等' : '')
        : (pendingFiles.length ? '已选 ' + pendingFiles.length + ' 个文件，正在预检…' : '');
    renderPending();
    previewPending();
}

/**
 * 预检：解析一遍但不写库，拿回"会切多少块 / 条款号覆盖率 / 是否重复 / 版本冲突"。
 * 一次请求带一批文件，失败不影响后续入库（预检只是提示，不是闸门）。
 */
async function previewPending() {
    const batch = pendingFiles.filter((f) => !previewCache.has(fileKey(f)));
    if (batch.length === 0) return;
    batch.forEach((f) => previewCache.set(fileKey(f), null)); // 占位，避免重复触发
    const form = new FormData();
    batch.forEach((f) => form.append('files', f));
    try {
        const resp = await fetch('/api/kb/preview', { method: 'POST', body: form });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const list = await resp.json();
        list.forEach((p, i) => {
            if (batch[i]) previewCache.set(fileKey(batch[i]), p);
        });
        const bad = list.filter((p) => p.warnings && p.warnings.length).length;
        $('uploadTip').textContent = bad
            ? '预检完成：' + list.length + ' 个文件，其中 ' + bad + ' 个有需要留意的地方（见下方黄色提示）'
            : '预检完成：' + list.length + ' 个文件，可以入库';
    } catch (e) {
        batch.forEach((f) => previewCache.set(fileKey(f), { error: e.message }));
        $('uploadTip').textContent = '预检失败（不影响入库）：' + e.message;
    }
    renderPending();
}

function warnHtml(p) {
    if (!p || p.error || !p.warnings || p.warnings.length === 0) return '';
    return '<div class="upload-warn">' +
        p.warnings.map((w) => '<div>· ' + escapeHtml(w) + '</div>').join('') +
        '</div>';
}

function sampleHtml(p) {
    if (!p || !p.samples || p.samples.length === 0) return '';
    const s = p.samples[0];
    return '<div class="upload-sample">' + escapeHtml(s.clause) + '：' + escapeHtml(s.text) + '</div>';
}

function renderPending() {
    $('fileName').textContent = pendingFiles.length ? '待入库 ' + pendingFiles.length + ' 个文件' : '';
    uploadList.innerHTML = '';
    let totalChunks = 0;
    let ready = 0;
    pendingFiles.forEach((f, i) => {
        const p = previewCache.get(fileKey(f));
        let state;
        if (p === null || p === undefined) {
            state = formatSize(f.size) + ' · 预检中…';
        } else if (p.error) {
            state = formatSize(f.size) + ' · 预检失败：' + p.error;
        } else if (!p.supported) {
            state = formatSize(f.size) + ' · 类型不支持，入库会被拒绝';
        } else {
            totalChunks += p.chunks;
            ready++;
            state = formatSize(f.size) + ' · 预计 ' + p.chunks + ' 块 · 条款号 ' + p.clausePercent
                + '% · ' + p.embeddingCalls + ' 次向量调用';
        }
        const row = document.createElement('div');
        row.className = 'upload-item';
        row.innerHTML =
            '<div class="upload-main">' +
            '  <div class="upload-name">' + escapeHtml(f.name) + '</div>' +
            '  <div class="upload-state">' + escapeHtml(state) + '</div>' +
            sampleHtml(p) + warnHtml(p) +
            '</div>' +
            '<button class="btn-link">移除</button>';
        row.querySelector('button').onclick = () => {
            pendingFiles.splice(i, 1);
            renderPending();
        };
        uploadList.appendChild(row);
    });
    if (pendingFiles.length && ready === pendingFiles.length) {
        // 只保留一条汇总，避免每次重渲染都在后面重复追加"（合计约 N 块）"
        const raw = $('uploadTip').textContent.split('（合计')[0];
        const hasWarn = raw.indexOf('有需要留意') >= 0;
        $('uploadTip').textContent = (raw.indexOf('预检完成') === 0 ? raw : '预检完成')
            + '，合计约 ' + totalChunks + ' 块'
            + (hasWarn ? '，建议先看下面的黄色提示' : '，可以入库');
    }
}

async function startUpload() {
    if (pollingJobId) {
        $('uploadTip').textContent = '上一批还在入库，等它跑完再传（避免 embedding 接口并发超限）';
        return;
    }
    if (pendingFiles.length === 0) {
        $('uploadTip').textContent = '请先选择文件，或直接用下面的粘贴框';
        return;
    }
    const batch = pendingFiles.slice();
    pendingFiles = [];
    $('fileName').textContent = '';
    const form = new FormData();
    batch.forEach((f) => form.append('files', f));
    $('uploadTip').textContent = '已提交，正在入库…';

    // 先把行渲染出来，等服务端返回 jobId 后再按文件状态刷新
    renderJobRows(batch.map((f) => ({ name: f.name, size: f.size, stage: '排队中', status: 'PENDING', written: 0, total: 0 })));

    try {
        const resp = await fetch('/api/kb/uploads', { method: 'POST', body: form });
        if (!resp.ok) {
            const err = await resp.json().catch(() => ({}));
            throw new Error(err.message || ('HTTP ' + resp.status));
        }
        const job = await resp.json();
        pollingJobId = job.jobId;
        batch.forEach((f) => previewCache.delete(fileKey(f)));
        pollJob(job);
    } catch (e) {
        $('uploadTip').textContent = '提交失败：' + e.message;
        pollingJobId = null;
    }
}

/**
 * 粘贴入库：不挑文件、不存盘，直接把一段条文交给知识库。
 * 服务端会把它落成 txt 再走正常入库链路，所以重建索引不会丢掉它。
 */
async function startPaste() {
    const text = $('pasteText').value.trim();
    if (!text) {
        $('pasteMeta').textContent = '先把条文粘贴到上面的框里';
        return;
    }
    if (pollingJobId) {
        $('pasteMeta').textContent = '上一批还在入库，等它跑完再提交';
        return;
    }
    const title = $('pasteTitle').value.trim();
    $('pasteMeta').textContent = '正在入库…';
    try {
        const resp = await fetch('/api/kb/paste', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: title, text: text })
        });
        if (!resp.ok) {
            const err = await resp.json().catch(() => ({}));
            throw new Error(err.message || ('HTTP ' + resp.status));
        }
        const job = await resp.json();
        pollingJobId = job.jobId;
        renderJobRows(job.files);
        pollJob(job);
        $('pasteText').value = '';
        $('pasteTitle').value = '';
        $('pasteMeta').textContent = '';
    } catch (e) {
        $('pasteMeta').textContent = '入库失败：' + e.message;
    }
}

/** 粘贴框里的实时估算：字数 + 粗数的条款号（纯前端正则，不打扰服务端）。 */
function updatePasteMeta() {
    const t = $('pasteText').value;
    if (!t.trim()) {
        $('pasteMeta').textContent = '';
        return;
    }
    const articles = (t.match(/第[一二三四五六七八九十百零〇\d]+条/g) || []).length;
    const numbers = (t.match(/(^|\n)\s*\d{1,2}(\.\d{1,2}){1,3}(?![\d.])/g) || []).length;
    $('pasteMeta').textContent = t.length + ' 字 · 约 ' + (articles + numbers) + ' 个条款号';
}
$('pasteText').addEventListener('input', updatePasteMeta);

function renderJobRows(files) {
    uploadList.innerHTML = '';
    files.forEach((f) => {
        const row = document.createElement('div');
        row.className = 'upload-item';
        row.dataset.name = f.name;
        row.innerHTML =
            '<div class="upload-main">' +
            '  <div class="upload-name">' + escapeHtml(f.name) + '</div>' +
            '  <div class="upload-state"></div>' +
            '  <div class="upload-bar"><div class="upload-bar-fill" style="width:0%"></div></div>' +
            '</div>';
        uploadList.appendChild(row);
    });
}

function updateJobRows(files) {
    let done = 0;
    let failed = 0;
    for (const f of files) {
        const row = uploadList.querySelector('.upload-item[data-name="' + cssEscape(f.name) + '"]');
        if (!row) continue;
        const state = row.querySelector('.upload-state');
        const fill = row.querySelector('.upload-bar-fill');
        const pct = f.total > 0 ? Math.round((f.written / f.total) * 100) : 0;
        if (f.status === 'DONE') {
            done++;
            fill.style.width = '100%';
            fill.classList.add('ok');
            state.textContent = '已完成 · ' + f.total + ' 块';
        } else if (f.status === 'FAILED') {
            failed++;
            fill.style.width = '100%';
            fill.classList.add('bad');
            const msg = f.message || '未知原因';
            // 行内只放前 100 字，完整原因（含"源文件已保留，可点重建索引补录"）挂 title，
            // 否则一条网络故障说明能把上传区撑高好几行。
            state.textContent = '失败：' + (msg.length > 100 ? msg.slice(0, 100) + '…' : msg);
            state.title = msg;
            state.classList.add('bad-text');
        } else if (f.stage === '向量化') {
            fill.style.width = pct + '%';
            state.textContent = '向量化 ' + f.written + '/' + f.total + ' 块';
        } else {
            state.textContent = f.stage || '排队中';
        }
    }
    $('uploadTip').textContent = '已完成 ' + done + '/' + files.length + (failed ? '，失败 ' + failed : '');
}

async function pollJob(job) {
    updateJobRows(job.files);
    if (job.finished) {
        finishJob(job);
        return;
    }
    setTimeout(async () => {
        try {
            const resp = await fetch('/api/kb/uploads/' + encodeURIComponent(job.jobId));
            if (!resp.ok) throw new Error('任务查询失败 HTTP ' + resp.status);
            const next = await resp.json();
            updateJobRows(next.files);
            if (next.finished) {
                finishJob(next);
            } else {
                pollJob(next);
            }
        } catch (e) {
            $('uploadTip').textContent = '进度查询失败：' + e.message;
            pollingJobId = null;
        }
    }, 700);
}

function finishJob(job) {
    pollingJobId = null;
    const failed = job.files.filter((f) => f.status === 'FAILED').length;
    $('uploadTip').textContent = failed
        ? '入库结束：成功 ' + (job.files.length - failed) + ' 个，失败 ' + failed + ' 个（失败原因见上）'
        : '全部入库完成，共 ' + job.files.length + ' 个文件';
    // 失败行保留在列表里让用户看清原因，别一结束就清空
    loadStats();
    loadDocuments();
}

/** CSS 属性选择器里的名字需要转义（文件名里常有括号、空格） */
function cssEscape(s) {
    return String(s).replace(/["\\]/g, '\\$&');
}

async function deleteDoc(docId) {
    if (!confirm('确定删除该文档？其全部向量块会一并删除。')) return;
    const resp = await fetch('/api/kb/documents?docId=' + encodeURIComponent(docId), { method: 'DELETE' });
    if (resp.ok) { loadStats(); loadDocuments(); }
    else alert('删除失败');
}

// ---------- 查看入库内容：某篇文档的块原文（弹层 + 关键字过滤 + 分页） ----------
const CHUNK_PAGE_SIZE = 20;
/** 弹层状态；seq 用于丢弃"打字太快导致的乱序响应" */
const chunkState = { docId: null, docName: '', offset: 0, matched: 0, total: 0, query: '', seq: 0 };
let chunkSearchTimer = null;

function openChunks(docId, docName) {
    chunkState.docId = docId;
    chunkState.docName = docName;
    chunkState.offset = 0;
    chunkState.query = '';
    $('chunkSearch').value = '';
    $('chunkDocName').textContent = docName || docId;
    $('chunkMeta').textContent = '加载中…';
    $('chunkList').innerHTML = '<p class="hint">正在读取块原文…</p>';
    $('chunkModal').classList.remove('hidden');
    document.body.classList.add('modal-open');
    loadChunks();
}

function closeChunks() {
    $('chunkModal').classList.add('hidden');
    document.body.classList.remove('modal-open');
    chunkState.docId = null;
}

/** 切换页码：dir = -1 / 1 */
function chunkPage(dir) {
    const next = chunkState.offset + dir * CHUNK_PAGE_SIZE;
    if (next < 0 || next >= chunkState.matched) return;
    chunkState.offset = next;
    loadChunks();
}

async function loadChunks() {
    if (!chunkState.docId) return;
    const seq = ++chunkState.seq;
    $('chunkList').classList.add('loading');
    try {
        // 关键字过滤放在后端：一篇规范可能上千块，全量下发会让首屏 JSON 到几 MB
        const url = '/api/kb/documents/' + encodeURIComponent(chunkState.docId) + '/chunks'
            + '?offset=' + chunkState.offset
            + '&limit=' + CHUNK_PAGE_SIZE
            + '&q=' + encodeURIComponent(chunkState.query);
        const resp = await fetch(url);
        if (!resp.ok) {
            const err = await resp.json().catch(() => ({}));
            throw new Error(err.message || ('HTTP ' + resp.status));
        }
        const data = await resp.json();
        if (seq !== chunkState.seq) return; // 已经有更新的请求了，丢弃这次结果
        chunkState.matched = data.matched;
        chunkState.total = data.total;
        chunkState.offset = data.offset;
        renderChunks(data);
    } catch (e) {
        if (seq !== chunkState.seq) return;
        $('chunkList').innerHTML = '<p class="hint">读取失败：' + escapeHtml(e.message) + '</p>';
        $('chunkMeta').textContent = '读取失败';
    } finally {
        if (seq === chunkState.seq) $('chunkList').classList.remove('loading');
    }
}

function renderChunks(data) {
    const box = $('chunkList');
    const kw = chunkState.query.trim();
    $('chunkMeta').textContent = kw
        ? '共 ' + data.total + ' 块，命中 ' + data.matched + ' 块（关键字「' + kw + '」）'
        : '共 ' + data.total + ' 块';

    if (!data.chunks || data.chunks.length === 0) {
        box.innerHTML = '<p class="hint">' + (kw ? '没有匹配的块，换个关键字试试。' : '这篇文档没有块。') + '</p>';
        renderPager();
        return;
    }

    box.innerHTML = '';
    for (const c of data.chunks) {
        const pageText = (c.page !== null && c.page !== undefined) ? '第 ' + c.page + ' 页 · ' : '';
        const card = document.createElement('div');
        card.className = 'chunk-card';
        card.innerHTML =
            '<div class="chunk-head">' +
            '  <span class="chunk-clause">' + escapeHtml(c.clause) + '</span>' +
            '  <span class="chunk-meta">第 ' + (c.chunkIndex + 1) + ' 块 · ' + pageText + c.chars + ' 字</span>' +
            '</div>' +
            '<div class="chunk-text">' + highlight(c.text, kw) + '</div>';
        box.appendChild(card);
    }
    renderPager();
    $('chunkList').scrollTop = 0;
}

function renderPager() {
    const from = chunkState.matched === 0 ? 0 : chunkState.offset + 1;
    const to = Math.min(chunkState.offset + CHUNK_PAGE_SIZE, chunkState.matched);
    $('chunkPager').textContent = chunkState.matched === 0
        ? '无结果' : (from + '–' + to + ' / ' + chunkState.matched + ' 块');
    $('chunkPrev').disabled = chunkState.offset <= 0;
    $('chunkNext').disabled = chunkState.offset + CHUNK_PAGE_SIZE >= chunkState.matched;
}

/** 把关键字包成 <mark>：先转义再替换，避免正文里的尖括号被当成标签 */
function highlight(text, kw) {
    const safe = escapeHtml(text);
    if (!kw) return safe;
    const needle = escapeHtml(kw);
    // 关键字里可能有正则元字符，用 split/join 做纯文本替换最稳
    return safe.split(needle).join('<mark>' + needle + '</mark>');
}

$('chunkSearch').addEventListener('input', () => {
    clearTimeout(chunkSearchTimer);
    // 每敲一个字就发请求会把彼此挤掉，250ms 停顿后才查
    chunkSearchTimer = setTimeout(() => {
        chunkState.query = $('chunkSearch').value;
        chunkState.offset = 0;
        loadChunks();
    }, 250);
});
$('chunkSearch').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        clearTimeout(chunkSearchTimer);
        chunkState.query = $('chunkSearch').value;
        chunkState.offset = 0;
        loadChunks();
    }
});
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !$('chunkModal').classList.contains('hidden')) closeChunks();
});

async function reindex() {
    const btn = $('reindexBtn');
    if (btn.disabled) return;
    if (!confirm('将按 data/docs 中的原文件全量重建索引（几千块时要几分钟），期间无法上传或删除，继续？')) return;

    // 重建是同步长请求：不给反馈的话页面会静默三分钟，看起来就像卡死了
    const startedAt = Date.now();
    btn.disabled = true;
    const timer = setInterval(() => {
        $('reindexTip').textContent = '重建中…已用 ' + Math.round((Date.now() - startedAt) / 1000) + ' 秒';
    }, 1000);
    $('reindexTip').textContent = '重建中…已用 0 秒';

    try {
        const resp = await fetch('/api/kb/reindex', { method: 'POST' });
        const data = await resp.json().catch(() => ({}));
        if (!resp.ok) throw new Error(data.message || ('HTTP ' + resp.status));
        const failed = data.failed || [];
        const parts = ['重新入库 ' + data.reindexedDocuments + ' 篇'];
        if (data.skippedDuplicates) parts.push('跳过重复 ' + data.skippedDuplicates + ' 篇');
        if (failed.length) parts.push('失败 ' + failed.length + ' 篇');
        $('reindexTip').textContent = '重建完成：' + parts.join(' · ');
        if (failed.length) {
            alert('以下文件重建失败（原文件仍在 data/docs，可修复后再次重建）：\n\n'
                + failed.map((f) => f.name + '：' + f.reason).join('\n'));
        }
        loadStats();
        loadDocuments();
    } catch (e) {
        $('reindexTip').textContent = '重建失败：' + e.message;
    } finally {
        clearInterval(timer);
        btn.disabled = false;
    }
}

async function searchTest() {
    const query = $('searchInput').value.trim();
    if (!query) return;
    const box = $('searchResults');
    box.innerHTML = '<p class="hint">检索中…</p>';
    try {
        const resp = await fetch('/api/kb/search-test', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: query, topK: 5 })
        });
        const hits = await resp.json();
        if (!hits || hits.length === 0) {
            box.innerHTML = '<p class="hint">没有召回任何片段（低于相似度阈值会被过滤）。</p>';
            return;
        }
        box.innerHTML = '';
        hits.forEach((h, i) => {
            const card = document.createElement('div');
            card.className = 'hit-card';
            const pageText = (h.page !== null && h.page !== undefined) ? ' · 第' + h.page + '页' : '';
            const pct = Math.max(0, Math.min(100, Number(h.score) * 100)).toFixed(0);
            card.innerHTML =
                '<div class="hit-head"><span>#' + (i + 1) + ' ' + escapeHtml(h.docName) +
                ' · ' + escapeHtml(h.clause) + pageText + '</span>' +
                '<span class="hit-score-wrap"><span class="hit-score-bar"><span class="hit-score-fill" style="width:' +
                pct + '%"></span></span><span class="hit-score-num">' +
                Number(h.score).toFixed(3) + '</span></span></div>' +
                '<div class="hit-snippet">' + escapeHtml(h.snippet) + '</div>';
            box.appendChild(card);
        });
    } catch (e) {
        box.innerHTML = '<p class="hint">检索失败：' + escapeHtml(e.message) + '</p>';
    }
}

$('searchInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') searchTest(); });

// ---------------- 工具函数 ----------------
function escapeHtml(s) {
    if (s === null || s === undefined) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(2) + ' MB';
}

function formatTime(iso) {
    if (!iso) return '-';
    return String(iso).replace('T', ' ').slice(0, 19);
}

// 页面打开时先拉一次统计，用于边栏小卡片、演示模式角标与模式提示
loadStats();
