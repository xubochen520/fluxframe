/* ============================================================
   纯享解析 → fluxframe 图片库集成
   ------------------------------------------------------------
   「保存到图片库」：把解析出的无水印视频 / 封面直接存入同源
   图片管理器（fluxframe），视频自动带「视频」标签，可附带平台
   来源标签（抖音/B站/快手…）。依赖 app.js 尾部暴露的
   window.__pureParse（S.task）。
   上传接口：POST /api/images/upload（multipart，文件字段 files）
   ============================================================ */
(function () {
  'use strict'

  var PP = window.__pureParse || {}
  var SAVE_BTN_ID = 'saveLib'

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    })
  }
  function cleanName(s) {
    var raw = String(s || '').trim().replace(/[\\/:*?"<>|\u0000-\u001f]/g, '').replace(/^[.\s]+|[.\s]+$/g, '')
    var head = Array.from(raw).slice(0, 60).join('')
    return head || '视频'
  }
  function fmtSize(b) {
    if (!isFinite(b) || b <= 0) return '—'
    if (b < 1048576) return (b / 1024).toFixed(1) + ' KB'
    return (b / 1048576).toFixed(2) + ' MB'
  }
  function extOf(task) {
    var t = (task.media && task.media.type) || ''
    if (/mp4/i.test(t)) return 'mp4'
    if (/webm/i.test(t)) return 'webm'
    if (/m4a|mov|mkv/i.test(t)) return 'mp4'
    return task.real ? 'mp4' : 'webm'
  }
  function fmtSizeHint(task) {
    var size = task.media && task.media.size
    return size ? fmtSize(size) : '—'
  }

  var mask = null

  function toast(msg, type, ms) {
    if (typeof PP.toast === 'function') PP.toast(msg, type || 'ok', ms || 3400)
  }

  function closePanel() {
    if (mask) {
      mask.remove()
      mask = null
    }
  }

  function setNote(el, cls, html) {
    if (!el) return
    el.className = 'ffsb-note' + (cls ? ' ' + cls : '')
    el.innerHTML = html
    el.style.display = 'block'
  }

  function el(tag, html, cls) {
    var node = document.createElement(tag)
    if (html != null) node.innerHTML = html
    if (cls) node.className = cls
    return node
  }

  /* ---------- 登录态检查 ---------- */
  async function checkLogin() {
    try {
      var res = await fetch('/api/me', { credentials: 'same-origin', signal: AbortSignal.timeout(8000) })
      if (res.ok) return { ok: true, user: await res.json() }
      if (res.status === 401) return { ok: false, msg: '未登录图片库' }
      return { ok: false, msg: '登录检查失败（HTTP ' + res.status + '）' }
    } catch (e) {
      return { ok: false, msg: '无法连接图片库服务：' + (e && e.message ? e.message : '网络错误') }
    }
  }

  /* ---------- 媒体拉取（同源代理流 / 演示 blob） ---------- */
  async function grabBlob(url, label) {
    var res = await fetch(url, { credentials: 'same-origin' })
    if (!res.ok) throw new Error(label + ' 下载失败（HTTP ' + res.status + '）')
    return res.blob()
  }

  async function ensureTag(name) {
    if (!name) return true
    var res = await fetch('/api/tags', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name, color: '#a78bfa' }),
    })
    return res.ok || res.status === 409
  }

  async function attachTag(imageId, name) {
    var res = await fetch('/api/images/' + encodeURIComponent(imageId) + '/tags', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name }),
    })
    return res.ok
  }

  async function uploadFiles(files, onStep) {
    var fd = new FormData()
    files.forEach(function (f) { fd.append('files', f.blob, f.filename) })
    var res = await fetch('/api/images/upload', { method: 'POST', body: fd, credentials: 'same-origin' })
    if (!res.ok) {
      var msg = '上传失败（HTTP ' + res.status + '）'
      try {
        var ej = await res.json()
        if (ej && ej.message) msg = ej.message
      } catch (e) { /* 非 JSON */ }
      throw new Error(msg)
    }
    var json = await res.json()
    onStep && onStep(json)
    return (json && json.items) || []
  }

  /* ---------- 面板 ---------- */
  async function openPanel() {
    closePanel()
    var task = (PP.getTask && PP.getTask()) || null
    if (!task || !task.media || !task.media.url) {
      toast('请先解析出一个视频结果', 'info')
      return
    }
    var login = await checkLogin()
    var platName = (task.plat && task.plat.name) || ''
    var nameDefault = cleanName(task.title || (task.name || '视频'))
    var isDemo = !task.real
    var hasCover = !!task.cover && (typeof task.cover === 'string' ? /^https?:|^\//.test(task.cover) : !!(task.cover && task.cover.url))
    var videoLabel = (task.real ? '无水印视频' : '演示视频片源') + ' · ' + (task.resLabel || '')
    var coverLabel = '封面图' + (isDemo ? '（演示）' : '')

    mask = el('div', '', 'ffsb-mask')
    var card = el('div', '', 'ffsb-card')
    mask.appendChild(card)
    document.body.appendChild(mask)

    card.innerHTML =
      '<div class="ffsb-head">' +
      '<span class="ffsb-ico"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg></span>' +
      '<div style="min-width:0"><b>保存到图片库</b><small>' + esc(platName || '') + ' · ' + esc(task.title || '') + '</small></div>' +
      '<button type="button" class="ffsb-close" title="取消">✕</button>' +
      '</div>' +
      '<div class="ffsb-field"><label class="ffsb-label">入库名称（将作为图片库中的文件名）</label>' +
      '<input class="ffsb-input" id="ffsbName" maxlength="80" value="' + esc(nameDefault) + '" /></div>' +
      '<div class="ffsb-field">' +
      '<label class="ffsb-opt"><input type="checkbox" id="ffsbMedia" checked /><span><b>' + esc(videoLabel) + '</b><small>大小 ' + esc(fmtSizeHint(task)) + ' · 自动带「视频」标签</small></span></label>' +
      (hasCover ? '<label class="ffsb-opt"><input type="checkbox" id="ffsbCover" checked /><span><b>' + esc(coverLabel) + '</b><small>与视频一同保存，无附加标签</small></span></label>' : '') +
      '<label class="ffsb-opt"><input type="checkbox" id="ffsbPlat" checked /><span><b>附带来源标签「' + esc(platName) + '」</b><small>便于在图片库按平台检索（视频自动带「视频」标签）</small></span></label>' +
      '</div>' +
      '<div class="ffsb-note" id="ffsbNote" style="display:none"></div>' +
      '<div class="ffsb-progress" id="ffsbProg" style="display:none"><div class="ffsb-bar"><i id="ffsbBar"></i></div><small id="ffsbProgTxt">准备中…</small></div>' +
      '<div class="ffsb-actions" id="ffsbActs">' +
      '<button type="button" class="btn btn-ghost" id="ffsbCancel">取消</button>' +
      '<button type="button" class="btn ffsb-go" id="ffsbGo">保存到图片库</button>' +
      '</div>' +
      '<div class="ffsb-linkrow" id="ffsbLinks" style="display:none"></div>'

    card.querySelector('.ffsb-close').addEventListener('click', closePanel)
    card.querySelector('#ffsbCancel').addEventListener('click', closePanel)
    card.addEventListener('click', function (e) { if (e.target === mask) closePanel() })
    mask.addEventListener('click', function (e) { if (e.target === mask) closePanel() })

    if (!login.ok) {
      setNote(card.querySelector('#ffsbNote'), 'err',
        '❌ ' + esc(login.msg) + '。请先登录图片库：<a href="/" target="_top" style="color:#9db8ff">前往登录页 →</a>' +
        '<br><small>登录后回到本页重新点「保存到图片库」即可</small>')
      card.querySelector('#ffsbGo').style.display = 'none'
      return
    }

    var goBtn = card.querySelector('#ffsbGo')
    var note = card.querySelector('#ffsbNote')
    var prog = card.querySelector('#ffsbProg')
    var progBar = card.querySelector('#ffsbBar')
    var progTxt = card.querySelector('#ffsbProgTxt')
    var nameInput = card.querySelector('#ffsbName')
    var ckMedia = card.querySelector('#ffsbMedia')
    var ckCover = card.querySelector('#ffsbCover')
    var ckPlat = card.querySelector('#ffsbPlat')
    if (!ckCover) ckCover = { checked: false }

    goBtn.addEventListener('click', async function () {
      var name = cleanName(nameInput.value)
      var doMedia = !!ckMedia && ckMedia.checked
      var doCover = !!ckCover && ckCover.checked
      if (!doMedia && !doCover) { setNote(note, 'err', '请至少勾选一项要保存的内容'); return }

      var files = []
      var steps = []
      var videoItemId = null
      if (doMedia) {
        steps.push({ key: 'media', label: '下载无水印视频' })
        var ext = extOf(task)
        var url = task.media.url
        if (typeof task.media.src === 'string' && task.media.src && !task.media.url.startsWith('/api/stream') && !task.media.url.startsWith('blob:')) {
          /* 直链拉取更快（同源代理兜底） */
          try {
            var r = await fetch(task.media.src, { mode: 'cors', signal: AbortSignal.timeout(60000) })
            if (r.ok) { url = task.media.src }
          } catch (e) { url = task.media.url }
        }
        files.push({ key: 'media', url: url, filename: name + '.' + ext })
      }
      if (doCover) {
        steps.push({ key: 'cover', label: '下载封面' })
        var cUrl = typeof task.cover === 'string' ? task.cover : (task.cover && task.cover.url)
        files.push({ key: 'cover', url: cUrl, filename: name + '-封面.jpg' })
      }
      var visible = files.filter(function (f) { return f.url })
      if (!visible.length) { setNote(note, 'err', '没有可下载的媒体文件'); return }

      goBtn.disabled = true
      nameInput.disabled = true
      ckMedia && (ckMedia.disabled = true)
      ckCover && (ckCover.disabled = true)
      ckPlat && (ckPlat.disabled = true)
      prog.style.display = 'block'
      progBar.style.width = '6%'

      try {
        var blobs = []
        for (var i = 0; i < visible.length; i++) {
          var f = visible[i]
          progTxt.textContent = '正在拉取 ' + (i + 1) + '/' + visible.length + '：' + (f.key === 'media' ? '无水印视频…' : '封面图…')
          progBar.style.width = Math.round(8 + (i / visible.length) * 42) + '%'
          try {
            var blob = await grabBlob(f.url, f.key === 'media' ? '视频' : '封面')
            blobs.push({ blob: blob, filename: f.filename })
          } catch (err) {
            if (f.key === 'cover') { continue /* 封面失败不阻塞视频入库 */ }
            throw err
          }
        }
        if (!blobs.length) { setNote(note, 'err', '媒体拉取失败，请检查网络后重试'); return }

        progTxt.textContent = '正在上传到图片库…'
        progBar.style.width = '58%'
        var items = await uploadFiles(blobs, function () { progBar.style.width = '84%' })
        progBar.style.width = '100%'
        progTxt.textContent = '完成'

        if (items.length === 0) {
          setNote(note, 'ok', '✅ 相同内容已在图片库中（自动去重），未重复入库。<br><small>去图片库即可找到它。</small>')
        } else {
          var savedTxt = '✅ 已保存 ' + items.length + ' 个文件到图片库'
          setNote(note, 'ok', esc(savedTxt))
          if (ckPlat && ckPlat.checked && platName) {
            var vid = items.filter(function (it) { return it && String(it.mimeType || '').indexOf('video') === 0 })
            if (vid.length && (await ensureTag(platName))) await attachTag(vid[0].id, platName)
          }
        }
        card.querySelector('#ffsbActs').style.display = 'none'
        var links = card.querySelector('#ffsbLinks')
        links.style.display = 'flex'
        links.innerHTML =
          '<span style="color:var(--ink-faint,#8b93ab);font-size:11px">视频自动带「视频」标签' + (ckPlat && ckPlat.checked && platName ? ' +「' + esc(platName) + '」' : '') + '</span>' +
          '<a href="/" target="_top">去图片库查看 →</a>'
      } catch (err) {
        goBtn.disabled = false
        nameInput.disabled = false
        ckMedia && (ckMedia.disabled = false)
        ckCover && (ckCover.disabled = false)
        ckPlat && (ckPlat.disabled = false)
        prog.style.display = 'none'
        setNote(note, 'err', '❌ ' + esc(err && err.message ? err.message : '保存失败，请稍后重试'))
      }
    })
  }

  function bind() {
    var btn = document.getElementById(SAVE_BTN_ID)
    if (!btn) return
    if (!btn.dataset.ffsbBound) {
      btn.dataset.ffsbBound = '1'
      btn.addEventListener('click', function (e) {
        e.preventDefault()
        void openPanel()
      })
    }
    /* 每次呈现新结果时刷新按钮副标题 */
    var hint = document.getElementById('saveLibHint')
    if (hint) {
      var task = (PP.getTask && PP.getTask()) || null
      if (task) {
        var meta = []
        if (task.plat && task.plat.name) meta.push(task.plat.name)
        if (task.resLabel) meta.push(task.resLabel)
        if (task.media && task.media.size) meta.push(fmtSize(task.media.size))
        hint.textContent = (meta.length ? meta.join(' · ') + ' → ' : '') + '一键存入图片库'
      } else {
        hint.textContent = '无水印视频入库 · 自动带「视频」标签'
      }
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bind)
  } else {
    bind()
  }
  /* 结果更新（每次 present 后 saveLib 始终在 DOM 中，按钮可用） */
  setInterval(function () {
    if (!mask && document.readyState !== 'loading') bind()
  }, 1200)
})()
