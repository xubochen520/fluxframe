/* ============================================================
   纯享解析 PureParse · 视频解析去水印工具 —— 前端逻辑
   ------------------------------------------------------------
   架构说明（双模式自动切换）：
   1) 真实模式：页面由本地代理服务提供时（node server.mjs 后访问
      http://localhost:8020），启动时自动探测同源 /api/ping 并启用真实
      解析——服务端直连平台官方接口（B站/b23.tv 全链真实），媒体经
      同源代理流播放/下载（绕过防盗链与 CORS）。
   2) 演示模式：直接双击 index.html（file://）或未连接代理时启用，
      由内置引擎在本地模拟解析全流程（片源 Canvas 本地生成），界面会
      明确标注「演示片源」，绝不冒充真实内容。
   ============================================================ */
"use strict";

/* ======================= 0. 配置 ======================= */
const API = {
  /** 真实解析服务地址（需后端转发以绕过平台防盗链）。
   *  留空 "" 时启动会自动探测同源代理（/api/ping）；也可手动指定远端服务。 */
  endpoint: "",
  timeout: 20000,
};

/** 启动探测：页面由本地代理服务提供时自动启用真实解析（异步、幂等、可 await） */
const probePromise = (async () => {
  try {
    if (!/^https?:/i.test(location.protocol)) return false; // file:// 无法同源探测
    const r = await fetch(`${location.origin}/api/ping`, { signal: AbortSignal.timeout(1500) });
    if (r.ok && (await r.text()) === "pong") {
      API.endpoint = `${location.origin}/api/parse`;
      return true;
    }
  } catch (e) { /* 无代理 → 演示模式 */ }
  return false;
})();

const DEMO = {
  baseW: 640,
  baseH: 360,
  dur: 8,          // 演示片源时长（秒）
  /* 帧率按输出分辨率递减，留足软件编码吞吐余量（帧数驱动终止 + MediaRecorder
     消费不足时自行丢帧，不再存在积压挂起问题） */
  fps: [24, 15, 5],
  bps: 900e3,      // 基准码率（原片）
};

/* ======================= 1. 工具函数 ======================= */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

const sleep = (ms) => new Promise((res) => setTimeout(res, ms));

function hashStr(s) {
  let h = 2166136261 >>> 0;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return h >>> 0;
}
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const pick = (arr, r) => arr[Math.floor(r() * arr.length)];
const fmtSize = (b) => {
  if (!isFinite(b)) return "—";
  if (b < 1024) return b + " B";
  if (b < 1048576) return (b / 1024).toFixed(1) + " KB";
  return (b / 1048576).toFixed(2) + " MB";
};
const fmtNum = (n) =>
  n >= 10000 ? (n >= 1e8 ? (n / 1e8).toFixed(1).replace(/\.0$/, "") + "亿" : (n / 1e4).toFixed(1).replace(/\.0$/, "") + "万") : String(n);
const fmtTime = (s) => {
  s = Math.max(0, Math.floor(s || 0));
  const m = (s / 60) | 0;
  return `${String(m).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
};
const toEl = (html) => {
  const t = document.createElement("template");
  t.innerHTML = html.trim();
  return t.content.firstElementChild;
};
const esc = (s) =>
  String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

/* ======================= 2. 平台库 ======================= */
const PLATFORMS = [
  {
    id: "douyin", name: "抖音", char: "抖", hosts: ["douyin.com", "iesdouyin.com"],
    tag: "短视频 / 图文", c1: "#31071a", c2: "#0b1220", ac: "#ff3355",
    bg: "linear-gradient(135deg,#25f4ee,#fe2c55)",
    desc: "支持去掉右下角抖音号水印，返回无水印直链",
  },
  {
    id: "kuaishou", name: "快手", char: "快", hosts: ["kuaishou.com", "chenzhongtech.com", "gifshow.com"],
    tag: "短视频", c1: "#33130a", c2: "#0d0f1a", ac: "#ff7a1a",
    bg: "linear-gradient(135deg,#ffb45e,#ff5e00)",
    desc: "支持快手极速版分享链接解析",
  },
  {
    id: "bilibili", name: "哔哩哔哩", char: "B", hosts: ["bilibili.com", "b23.tv", "bilibili.tv"],
    tag: "视频 / 番剧", c1: "#141230", c2: "#0a0c16", ac: "#fb7299",
    bg: "linear-gradient(135deg,#8aa8ff,#fb7299)",
    desc: "BV / av / b23.tv 短链均可识别",
  },
  {
    id: "xiaohongshu", name: "小红书", char: "红", hosts: ["xiaohongshu.com", "xhslink.com"],
    tag: "视频笔记", c1: "#36090d", c2: "#120a13", ac: "#ff2442",
    bg: "linear-gradient(135deg,#ff9e9e,#ff2442)",
    desc: "支持图文笔记中的视频提取",
  },
  {
    id: "weibo", name: "微博", char: "博", hosts: ["weibo.com", "weibo.cn"],
    tag: "视频 / 回放", c1: "#360a0d", c2: "#110b0e", ac: "#ff6b4a",
    bg: "linear-gradient(135deg,#ffa08c,#e6162d)",
    desc: "微博视频 / 秒拍直链解析",
  },
  {
    id: "shipinhao", name: "视频号", char: "视", hosts: ["weixin.qq.com", "channels.weixin.qq.com", "sph.com.cn"],
    tag: "短视频", c1: "#04241a", c2: "#0a1110", ac: "#07c160",
    bg: "linear-gradient(135deg,#4be38b,#07c160)",
    desc: "支持微信视频号分享页解析",
  },
  {
    id: "xigua", name: "西瓜视频", char: "瓜", hosts: ["ixigua.com"],
    tag: "中视频 / 4K", c1: "#310c05", c2: "#110b09", ac: "#f04142",
    bg: "linear-gradient(135deg,#ffa05e,#f04142)",
    desc: "支持 4K / HDR 片源直链",
  },
  {
    id: "pipixia", name: "皮皮虾", char: "皮", hosts: ["pipix.com"],
    tag: "搞笑短视频", c1: "#33060d", c2: "#0e0b11", ac: "#ff4b57",
    bg: "linear-gradient(135deg,#ff9db0,#ff4b57)",
    desc: "支持站内短视频与图文解析",
  },
].map((p) => ({ ...p, re: new RegExp(p.hosts.map((h) => h.replace(/\./g, "\\.")).join("|"), "i") }));

/* ======================= 3. 演示内容库（种子随机） ======================= */
const D_CREATORS = [
  ["林小满", "travel_lin", "旅行风光"], ["一只陈皮", "chenpi_cat", "萌宠日常"], ["阿澈", "ache_film", "城市漫游"],
  ["屿森", "yusen_photo", "摄影美学"], ["苏晚晴", "wanqing_kitchen", "美食手作"], ["老周在路上", "oldzhou_go", "自驾旅拍"],
  ["南屿風", "nanyu_sky", "风光延时"], ["桃桃乌龙", "taotao_drink", "探店打卡"], ["野原拾光", "yeyuan_time", "治愈vlog"],
  ["像素猫咖", "pixel_cat", "手工好物"], ["贰筒", "ertong_fm", "生活随笔"], ["海岸电台", "coast_fm", "情绪短片"],
];
const D_TITLES = [
  (p, t, th) => `在${p}的${t}，随手一拍都是电影画面`,
  (p, t, th) => `${p}绝美瞬间｜${t}治愈系镜头美学`,
  (p, t, th) => `沉浸式体验：${p}的${t}`,
  (p, t, th) => `收藏！${p}必去的${n()}个地方，${t}去刚好`,
  (p, t, th) => `别再错过${p}的${t}了，每一帧都想截图`,
  (p, t, th) => `${th}天花板教程，连看${n()}遍都不腻`,
  (p, t, th) => `关于${th}的${t}，99%的人不知道的秘密`,
  (p, t, th) => `${p}慢生活${n()}日｜${th}与${t}`,
  (p, t, th) => `我愿称之为${th}之神！${t}的快乐如此简单`,
  (p, t, th) => `${t}的${p}有多绝？这条视频告诉你`,
];
const D_PLACES = ["川西", "大理", "冰岛", "拉萨", "京都", "垦丁", "阿尔卑斯", "喀纳斯", "威海", "重庆", "三亚", "呼伦贝尔"];
const D_TIMES = ["清晨", "黄昏", "深夜", "雨后", "雪后", "日出前"];
const D_THINGS = ["露营", "咖啡拉花", "城市夜景", "街边小吃", "胶片调色", "猫咪", "vlog 转场", "手冲咖啡", "海钓", "骑行"];
let _nCnt = 0;
const n = () => 2 + (_nCnt++ % 9);
const genTitle = (r) => {
  const fn = pick(D_TITLES, r);
  return fn(pick(D_PLACES, r), pick(D_TIMES, r), pick(D_THINGS, r));
};

/* ======================= 4. 场景渲染（本地片源） ======================= */
const S_PARTICLES = (() => {
  const rnd = mulberry32(9981);
  return Array.from({ length: 30 }, () => ({
    x: rnd(), y: rnd(), r: 0.6 + rnd() * 2.2, sp: 0.012 + rnd() * 0.03, ph: rnd() * 6.28, a: 0.05 + rnd() * 0.13,
  }));
})();

/** 在 640×360 逻辑坐标系内绘制一帧（任意目标尺寸同绘，保证超分后文字/图形真实变清晰） */
function drawScene(ctx, w, h, t, dur, cfg) {
  const k = w / 640;
  ctx.save();
  ctx.scale(k, k);
  const g = ctx.createLinearGradient(0, 0, 0, 360);
  g.addColorStop(0, cfg.c1);
  g.addColorStop(0.55, cfg.c2);
  g.addColorStop(1, "#05060d");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 640, 360);

  // 缓慢漂移的光晕
  const blob = (cx, cy, rad, col, alp) => {
    const rg = ctx.createRadialGradient(cx, cy, 0, cx, cy, rad);
    rg.addColorStop(0, col);
    rg.addColorStop(1, "transparent");
    ctx.globalAlpha = alp;
    ctx.fillStyle = rg;
    ctx.fillRect(cx - rad, cy - rad, rad * 2, rad * 2);
    ctx.globalAlpha = 1;
  };
  blob(150 + Math.sin(t * 0.6) * 70, 120 + Math.cos(t * 0.5) * 40, 240, cfg.ac, 0.17);
  blob(520 + Math.sin(t * 0.4 + 2) * 90, 90 + Math.cos(t * 0.3) * 60, 210, "#38bdf8", 0.13);
  blob(360, 340 + Math.sin(t * 0.7) * 26, 300, "#f0abfc", 0.08);

  // 周期性横贯流光
  const lp = ((t / 3.2) % 1.35) * 900 - 260;
  const lgrad = ctx.createLinearGradient(lp - 160, 0, lp + 160, 0);
  lgrad.addColorStop(0, "transparent");
  lgrad.addColorStop(0.5, "rgba(255,255,255,0.05)");
  lgrad.addColorStop(1, "transparent");
  ctx.fillStyle = lgrad;
  ctx.save();
  ctx.rotate(-0.16);
  ctx.translate(0, 90);
  ctx.fillRect(0, 0, 1000, 90);
  ctx.restore();

  // 粒子
  for (const p of S_PARTICLES) {
    const y = ((p.y - t * p.sp) % 1 + 1) % 1;
    ctx.globalAlpha = p.a * (0.6 + 0.4 * Math.sin(t * 1.4 + p.ph));
    ctx.fillStyle = "#ffffff";
    ctx.beginPath();
    ctx.arc(p.x * 640, y * 360, p.r, 0, 6.29);
    ctx.fill();
  }
  ctx.globalAlpha = 1;

  // 玻璃信息条（左下）
  ctx.globalAlpha = 0.5;
  ctx.fillStyle = "rgba(255,255,255,0.1)";
  roundRect(ctx, 18, 322, 214, 24, 12);
  ctx.fill();
  ctx.globalAlpha = 1;
  ctx.fillStyle = "rgba(255,255,255,0.85)";
  ctx.font = "600 12px 'Noto Sans SC','PingFang SC',sans-serif";
  ctx.fillText("PureParse · 本地演示片源 · 无水印", 30, 338);

  // 平台徽标（顶部左）
  ctx.globalAlpha = 0.92;
  ctx.fillStyle = cfg.ac;
  roundRect(ctx, 18, 16, 96, 28, 14);
  ctx.fill();
  ctx.fillStyle = "rgba(0,0,0,0.72)";
  ctx.font = "700 13px 'Noto Sans SC','PingFang SC',sans-serif";
  ctx.fillText(`● ${cfg.platName} 演示`, 32, 35);

  // 主标题（双行自适应）
  const lines = wrapLines(ctx, cfg.title, 560, "800 40px 'Noto Sans SC','PingFang SC',sans-serif");
  const bob = Math.sin(t * 1.1) * 2.4;
  let ty = 132 + bob;
  ctx.save();
  ctx.shadowColor = "rgba(0,0,0,0.65)";
  ctx.shadowBlur = 16;
  ctx.fillStyle = "#ffffff";
  ctx.font = "800 40px 'Noto Sans SC','PingFang SC',sans-serif";
  lines.forEach((ln, i) => {
    const wpx = ctx.measureText(ln).width;
    ctx.fillText(ln, (640 - wpx) / 2, ty + i * 56);
  });
  ctx.restore();

  // 底部呼吸装饰环
  const rr = 26 + Math.sin(t * 1.3) * 4;
  ctx.strokeStyle = cfg.ac;
  ctx.globalAlpha = 0.5;
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.arc(320, 264, rr, 0, 6.29);
  ctx.stroke();
  ctx.globalAlpha = 0.25;
  ctx.beginPath();
  ctx.arc(320, 264, rr + 14, 0, 6.29);
  ctx.stroke();
  ctx.globalAlpha = 1;
  ctx.restore();
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}
function wrapLines(ctx, text, maxW, font) {
  ctx.font = font;
  const words = String(text).split("");
  const lines = [];
  let cur = "";
  for (const ch of words) {
    const test = cur + ch;
    if (ctx.measureText(test).width > maxW && cur) {
      lines.push(cur);
      cur = ch;
    } else cur = test;
  }
  if (cur) lines.push(cur);
  return lines.length > 2 ? [lines[0], lines[1].slice(0, Math.floor(lines[1].length * 0.6)) + "…"] : lines;
}

/* ======================= 5. 本地渲染编码器 ======================= */
const clipCache = new Map(); // `${hash}-${scale}` -> Promise<Clip>（重复链接直接复用，秒开）

function cachedClip(key, factory) {
  if (clipCache.has(key)) return clipCache.get(key);
  const p = factory().catch((err) => { clipCache.delete(key); throw err; });
  clipCache.set(key, p);
  return p;
}

function makeCover(cfg, w, h) {
  return new Promise((resolve) => {
    const cv = document.createElement("canvas");
    cv.width = w; cv.height = h;
    const ctx = cv.getContext("2d");
    drawScene(ctx, w, h, 0.6, DEMO.dur, cfg);
    cv.toBlob((b) => {
      if (!b) return resolve(null);
      resolve({ url: URL.createObjectURL(b), blob: b, w, h, size: b.size, type: "image/png" });
    }, "image/png");
  });
}

function mimePick() {
  const list = ["video/webm;codecs=vp8", "video/webm;codecs=vp9", "video/webm"];
  if (typeof MediaRecorder === "undefined") return null;
  for (const m of list) {
    try { if (MediaRecorder.isTypeSupported(m)) return m; } catch (e) { /* noop */ }
  }
  return "video/webm";
}

/**
 * 渲染并编码一段本地演示视频（Canvas → MediaRecorder）。
 * scale: 1 / 2 / 4 —— 2x、4x 即“超分重绘”，输出为真正更高分辨率的文件。
 */
function encodeClip(opts) {
  const { cfg, scale = 1, onProgress, signal } = opts;
  const mime = mimePick();
  if (!mime || !HTMLCanvasElement.prototype.captureStream) return Promise.resolve(null);

  const w = Math.round(640 * scale);
  const h = Math.round(360 * scale);
  const fps = DEMO.fps[Math.min(scale, 4) === 4 ? 2 : scale === 2 ? 1 : 0];
  const dur = DEMO.dur;
  const total = dur * fps;
  const cv = document.createElement("canvas");
  cv.width = w; cv.height = h;
  const ctx = cv.getContext("2d", { alpha: false });
  const stream = cv.captureStream(fps);
  const rec = new MediaRecorder(stream, {
    mimeType: mime,
    videoBitsPerSecond: Math.min(6e6, Math.round(DEMO.bps * (w * h) / (640 * 360))),
  });
  const chunks = [];

  return new Promise((resolve) => {
    rec.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
    rec.onstop = () => {
      stream.getTracks().forEach((tk) => tk.stop());
      if (signal && signal.cancelled) return resolve(null);
      const blob = new Blob(chunks, { type: mime });
      resolve({ url: URL.createObjectURL(blob), blob, w, h, dur, size: blob.size, type: mime });
    };
    try { rec.start(); } catch (e) { return resolve(null); }

    const t0 = performance.now();
    const frameCount = Math.ceil(dur * fps);
    let last = -1;
    let done = 0;
    const frame = (now) => {
      if (signal && signal.cancelled) { try { rec.stop(); } catch (e) { /* noop */ } return; }
      /* 按目标帧率节流绘制（rAF 回调通常 60fps，超出的帧直接跳过） */
      if (last < 0) last = now;
      if (now - last < 1000 / fps) { requestAnimationFrame(frame); return; }
      last = now;
      /* 场景时间由“帧计数”驱动而非 wall-clock：保证画面时间与帧一一对应，
         即使页面被节流暂停再恢复，文件内容与时长依然正确 */
      const t = done / fps;
      drawScene(ctx, w, h, t, dur, cfg);
      done++;
      window.__enc = { done, total: frameCount, ts: now }; // 诊断钩子（开发用）
      if (onProgress) onProgress(Math.min(0.99, done / frameCount), t);
      /* 终止：帧数达标即收尾；wall-clock 仅作异常兜底（如 rAF 被系统暂停） */
      const wallSec = (now - t0) / 1000;
      if (done < frameCount && wallSec < dur * 4) requestAnimationFrame(frame);
      else { window.__enc.flushing = true; try { rec.stop(); } catch (e) { resolve(null); } }
    };
    requestAnimationFrame(frame);
  });
}

/* ======================= 6. DOM ======================= */
const dom = {};
const $id = (id) => (dom[id] ??= document.getElementById(id));
const D = {
  get input() { return $id("urlInput"); },
  get parseBtn() { return $id("parseBtn"); },
  get parseBtnTxt() { return $id("parseBtn").querySelector("span"); },
  get flow() { return $id("parseFlow"); },
  get flowFill() { return $id("flowBarFill"); },
  get flowMsg() { return $id("flowMsg"); },
  get result() { return $id("resultWrap"); },
  get video() { return $id("vid"); },
};

/* ======================= 7. 状态 ======================= */
const S = {
  busy: false,
  parseToken: 0,
  task: null,       // 当前任务（含解析信息与媒体）
  lastUrl: "",
  lastShareText: "", // 最近一次解析的完整口令原文（透传真实解析服务做深度解析）
  lastTail: "",      // 最近一次解析提取出的口令尾缀
  historyKey: "pp_history_v1",
};

let injectedCss = false;
function injectCssOnce() {
  if (injectedCss) return;
  injectedCss = true;
  const st = document.createElement("style");
  st.textContent = `
    .shake { animation: shake .45s ease; }
    @keyframes shake { 20% { transform: translateX(-9px); } 40% { transform: translateX(8px); }
                       60% { transform: translateX(-6px); } 80% { transform: translateX(4px); } }
    .video-shell.playing .cover-shade { opacity: 1; }
    .video-shell.paused .ctrl { opacity: 1; transform: none; }
    .raw-json { white-space: pre-wrap; word-break: break-all; overflow-wrap: anywhere; }
    .plat-badge:not([data-empty]) .plat-badge-inner { color:#fff; }
    .op-dl.loading svg { animation: spin 1s linear infinite; }
    .toast a { color:#7dd3fc; text-decoration: underline; }
  `;
  document.head.appendChild(st);
}

/* ======================= 8. Toast ======================= */
function toast(msg, type = "ok", ms = 3200) {
  const box = $id("toasts");
  const icon = type === "ok" ? "✓" : type === "err" ? "✕" : "i";
  const el = toEl(`<div class="toast ${type === "err" ? "err-msg" : ""}"><span class="t-ico t-${type === "err" ? "err" : type === "info" ? "info" : "ok"}">${icon}</span><span></span></div>`);
  el.querySelector("span:last-child").innerHTML = msg;
  box.appendChild(el);
  setTimeout(() => {
    el.classList.add("out");
    setTimeout(() => el.remove(), 400);
  }, ms);
}

/* ======================= 9. 平台识别 / 分享文本深度提取 ======================= */
function detectPlatform(url) {
  for (const p of PLATFORMS) if (p.re.test(url)) return p;
  return null;
}

/* ---- 分享文案深度链接提取（兼容中文标点 / emoji / 平台口令 / 多链接） ---- */
/* URL 主体终止字符：空白、中文标点与装饰符号、引号括号。
   抖音「kpD:/ O@x.sr :4pm」等口令尾巴以空格与链接分隔；万一无空格粘连，
   由下方「短链白名单段数裁剪」清理主链接，原文则完整保留（见 extractShareUrl）。 */
const URL_STOP = `\\s"'<>《》〈〉【】〔〕「」『』（）()\\[\\]{}“”‘’、，。；：？！…—–·～|｜`;
const URL_RE = new RegExp(`https?://[^${URL_STOP}]+`, "gi");
/* 提取后清理：去掉 URL 尾部误带的标点（中文句读 / 省略号 / 半角标点） */
const TAIL_NOISE = /[，。、；：？！…·,;:!]+$/;
/* 平台短链域名 → 允许的路径段数：超过即判定为口令/日期等噪声粘连并裁剪。
   注：xhslink 短链形如 /a/xxx（两段）；iesdouyin 完整形态 share/video/id（三段）。 */
const SHORT_MAX_SEG = new Map([
  ["v.douyin.com", 1], ["iesdouyin.com", 3],
  ["v.kuaishou.com", 1], ["chenzhongtech.com", 1],
  ["xhslink.com", 2], ["b23.tv", 1], ["pipix.com", 2],
]);
/* 主链接是否属于「短链口令」形态（后文常附验签尾巴，如 kpD:/ O@x.sr :4pm） */
function isShortLinkHost(u) {
  try { return SHORT_MAX_SEG.has(new URL(u).hostname.toLowerCase()); } catch (e) { return false; }
}

function normalizeShortUrl(u) {
  try {
    const parsed = new URL(u);
    const max = SHORT_MAX_SEG.get(parsed.hostname.toLowerCase());
    if (max == null) return u;
    const segs = parsed.pathname.split("/").filter(Boolean);
    if (segs.length <= max) return u;
    parsed.pathname = "/" + segs.slice(0, max).join("/") + "/";
    return parsed.toString();
  } catch (e) {
    return u;
  }
}

/**
 * 从任意文本（整段中文分享文案 / 裸链接）中提取最可能的分享链接，并保留口令上下文。
 *  - url        主链接：短链口令只取标准 URL 部分（如 https://v.douyin.com/kIFVd-Q23eg/），
 *               这是浏览器/分享体系唯一可访问的入口；
 *  - tail       主链接同行之后的附随文本（验签口令等，如 kpD:/ O@x.sr :4pm 02/04）——
 *               它们不是独立 URL，但可能是平台验签所需，完整保留供真实解析服务使用；
 *  - shareText  用户粘贴的完整原文（未做任何删改）。
 *  @returns {{url: string|null, candidates: string[], tail: string, shareText: string}}
 */
function extractShareUrl(raw) {
  const text = String(raw || "");
  const found = [];
  URL_RE.lastIndex = 0;
  let m;
  while ((m = URL_RE.exec(text))) {
    const clean = m[0].replace(TAIL_NOISE, "");
    if (clean) found.push({ clean, start: m.index, end: m.index + m[0].length });
  }
  const cleaned = [...new Set(found.map((f) => normalizeShortUrl(f.clean)))];
  if (!cleaned.length) return { url: null, candidates: [], tail: "", shareText: text.trim() };
  /* 文本中存在多个链接时，优先选择命中支持平台的候选（口令文本常含正文引用链接） */
  const url = cleaned.find((u) => detectPlatform(u)) || cleaned[0];
  /* 主链接之后同一行的附随文本 = 口令/说明尾巴（换行或下一个链接处截断） */
  const chosen = found.find((f) => normalizeShortUrl(f.clean) === url) || found[0];
  let tail = text.slice(chosen.end).split(/\r?\n/)[0].trim();
  if (!tail || /^https?:\/\//i.test(tail)) tail = "";
  return { url, candidates: cleaned, tail, shareText: text.trim() };
}

function refreshDetect() {
  const raw = D.input.value;
  const badge = $id("platBadge");
  const inner = $id("platBadgeInner");
  const line = $id("detectLine");
  const urlEl = $id("detectUrl");
  const nameEl = $id("detectName");
  const reasonEl = $id("detectReason");
  const okEl = $id("detectOk");
  const ext = extractShareUrl(raw);
  const url = ext.url;

  if (!url) {
    badge.dataset.empty = "1";
    inner.innerHTML = `<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3l7 3v5c0 4.6-3 8.6-7 10-4-1.4-7-5.4-7-10V6z" opacity=".9"/><path d="M9.5 11.8l2 2 3.5-4" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
    inner.style.background = "";
    inner.style.color = "";
    line.hidden = true;
    return { url: null, plat: null };
  }

  const plat = detectPlatform(url);
  if (!plat) {
    badge.dataset.empty = "1";
    inner.innerHTML = `<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.3 3.9L2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4.5M12 17.2v.1"/></svg>`;
    inner.style.background = "";
    inner.style.color = "#fb7185";
    line.hidden = false;
    nameEl.textContent = "外部链接";
    reasonEl.textContent = "不在支持平台列表，请核对";
    urlEl.textContent = url;
    urlEl.title = `复制：${url}`;
    okEl.textContent = "⚠ 解析前请确认";
    okEl.classList.add("warn");
    return { url, plat: null };
  }

  delete badge.dataset.empty;
  inner.innerHTML = `<span style="background:${plat.bg};-webkit-background-clip:text;background-clip:text;-webkit-text-fill-color:transparent;">${plat.char}</span>`;
  inner.style.background = plat.bg;
  inner.style.webkitTextFillColor = "";
  inner.style.color = "";
  nameEl.textContent = plat.name;
  reasonEl.textContent =
    raw.trim() === url ? "检测到平台链接"
    : ext.candidates.length > 1 ? "多链接 · 已自动选中平台链接"
    : "从分享文本中自动提取";
  urlEl.textContent = url;
  urlEl.title = `复制：${url}`;
  okEl.textContent = "✓ 链接有效";
  okEl.classList.remove("warn");
  /* 口令尾巴提示：短链口令的附随验签文本不是独立链接，但会随原文保留并提交解析服务 */
  const tailEl = $id("detectTail");
  if (ext.tail && isShortLinkHost(url)) {
    tailEl.hidden = false;
    tailEl.textContent = `口令尾缀 ${ext.tail.length} 字已保留`;
    tailEl.title = `附随文本（非独立链接，随原文保留供解析服务验签）：\n${ext.tail}`;
  } else {
    tailEl.hidden = true;
    tailEl.textContent = "";
  }
  line.hidden = false;
  return { url, plat };
}

/* ======================= 10. 解析流程 UI ======================= */
function flowShow() { D.flow.hidden = false; }
function flowHide() { D.flow.hidden = true; }
function flowSetStep(idx, state) {
  const li = D.flow.querySelector(`li[data-step="${idx}"]`);
  if (!li) return;
  li.classList.toggle("active", state === "active");
  li.classList.toggle("done", state === "done");
}
function flowReset() {
  $$("li", D.flow).forEach((li) => { li.classList.remove("active", "done"); });
  D.flowFill.style.width = "0%";
}
function flowBar(pct) { D.flowFill.style.width = `${Math.max(0, Math.min(100, pct))}%`; }
function flowSay(msg) { D.flowMsg.textContent = msg; }

async function flowRun(steps, onAfter) {
  flowReset();
  flowShow();
  let acc = 0;
  for (let i = 0; i < steps.length; i++) {
    const s = steps[i];
    flowSetStep(i + 1, "active");
    flowSay(s.msg);
    const span = s.dur;
    const until = performance.now() + span;
    let t0pct = i === 0 ? 2 : acc;
    const pctEnd = i === steps.length - 1 ? 74 : Math.min(74, acc + (span / 5000) * 60);
    await new Promise((res) => {
      const iv = setInterval(() => {
        const now = performance.now();
        const p = Math.min(1, (now - (until - span)) / span);
        flowBar(t0pct + (pctEnd - t0pct) * p);
        if (now >= until) { clearInterval(iv); res(); }
      }, 60);
    });
    flowSetStep(i + 1, "done");
    acc = pctEnd;
  }
  if (onAfter) await onAfter(acc);
}

/* ======================= 11. 演示数据生成 ======================= */
function demoData(url, plat) {
  const rnd = mulberry32(hashStr(url + plat.id));
  const [name, handle, tag] = pick(D_CREATORS, rnd);
  const verified = rnd() < 0.75;
  const mag = [1, 3, 10][Math.floor(rnd() * 3)];
  const like = Math.round((2000 + rnd() * 90000) * mag);
  return {
    title: genTitle(rnd),
    author: { name, handle, tag, verified },
    stats: {
      like: Math.round(like * (0.7 + rnd() * 0.6)),
      comment: Math.round(like * (0.05 + rnd() * 0.12)),
      share: Math.round(like * (0.3 + rnd() * 0.5)),
    },
  };
}

/* ======================= 12. 主流程：解析 ======================= */
async function runParse(rawUrl, { silent = false, forceDemo = false } = {}) {
  if (S.busy) return;
  await probePromise; /* 等待代理探测完成，避免模式竞态 */
  /* 深度提取：入参可以是裸链接，也可以是整段中文分享文案（自动抽取+去噪）。
     主链接用于解析；完整原文（shareText）与口令尾缀（tail）一并保留，
     供真实解析服务做短链跳转/验签等深度解析。 */
  const { url, tail, shareText } = extractShareUrl(rawUrl);
  if (!url) {
    if (!silent) {
      toast("未检测到可解析的链接：请粘贴抖音 / 快手 / B站等平台的分享文本或链接", "err", 4400);
      $id("inputWrap").classList.remove("shake");
      void D.input.offsetWidth;
      $id("inputWrap").classList.add("shake");
    }
    return;
  }
  const plat = detectPlatform(url);
  if (!plat) {
    if (!silent) {
      toast(`已提取链接（${url}）但不在支持平台列表，请核对后重试`, "err", 5200);
      $id("platBadgeInner").style.color = "#fb7185";
    }
    return;
  }

  const myToken = ++S.parseToken;
  const triedReal = !!(API.endpoint && !forceDemo);
  S.busy = true;
  D.parseBtn.disabled = true;
  D.parseBtnTxt.textContent = "解析中…";
  S.lastUrl = url;
  S.lastShareText = shareText || url;   // 完整口令原文（透传解析服务）
  S.lastTail = tail || "";
  hideFallback();
  refreshDetect();
  flowShow();

  try {
    if (triedReal) {
      await realParse(url, plat, myToken);
    } else {
      await demoParse(url, plat, myToken);
    }
  } catch (err) {
    if (myToken !== S.parseToken) return;
    console.warn("[PureParse] 解析未成功:", err && err.message);
    const msg = err && err.message ? err.message : "解析失败，请稍后重试";
    toast(msg, "err", 6500);
    flowSetStep(3, "active");
    flowSay("解析失败 — 已终止");
    /* 真实解析不可用时，提供「演示预览」显式降级（绝不冒充真实内容） */
    if (triedReal && url) showFallback(msg, url);
  } finally {
    if (myToken === S.parseToken) {
      S.busy = false;
      D.parseBtn.disabled = false;
      D.parseBtnTxt.textContent = "开始解析";
      setTimeout(flowHide, 350);
    }
  }
}

/* ---------- 真实解析失败时的显式降级入口 ---------- */
function showFallback(msg, url) {
  const bar = $id("fallbackBar");
  const txt = $id("fbMsg");
  txt.textContent = msg;
  const btn = $id("demoFallbackBtn");
  btn.onclick = null;
  btn.onclick = () => runParse(url, { forceDemo: true });
  bar.hidden = false;
}
function hideFallback() {
  const bar = $id("fallbackBar");
  if (bar) bar.hidden = true;
}

/* ---------- 演示模式解析 ---------- */
async function demoParse(url, plat, token) {
  const cfgBase = {
    platName: plat.name,
    title: "正在解析…",
    handle: "@demo",
    c1: plat.c1, c2: plat.c2, ac: plat.ac,
  };
  const data = demoData(url, plat);
  const seed = hashStr(url + plat.id);

  const showAfterEncode = async (pctBase) => {
    /* 第五步：渲染/编码演示片源，汇报真实进度（同一链接有缓存时直接复用） */
    const signal = { cancelled: false };
    const cacheKey = `${seed}-1`;
    flowSetStep(5, "active");
    const clipP = cachedClip(cacheKey, () =>
      encodeClip({
        cfg: { ...cfgBase, title: data.title, handle: data.author.handle },
        scale: 1,
        signal,
        onProgress: (p) => {
          if (token !== S.parseToken) { signal.cancelled = true; return; }
          flowBar(pctBase + p * (100 - pctBase));
          flowSay(`渲染无水印片源 ${Math.round(p * 100)}% …`);
        },
      })
    );
    const coverP = makeCover({ ...cfgBase, title: data.title, handle: data.author.handle }, 640, 360);
    const [clip, cover] = await Promise.all([clipP, coverP]);
    if (token !== S.parseToken) {
      /* 缓存中的 clip 可能正被后续同链接任务复用，绝不 revoke；封面非共享可释放 */
      if (cover && cover.url) URL.revokeObjectURL(cover.url);
      return false;
    }
    if (!clip) {
      clipCache.delete(cacheKey);
      throw new Error("当前浏览器不支持本地渲染（MediaRecorder），请更换 Chrome / Edge 浏览器");
    }

    flowBar(100);
    flowSay("无水印片源渲染完成 ✓");
    flowSetStep(5, "done");

    const task = {
      id: seed, url, plat, scale: 1,
      shareText: S.lastShareText, tail: S.lastTail,
      title: data.title,
      author: { ...data.author, handle: `@${data.author.handle}` },
      stats: data.stats,
      media: clip,
      cover,
      resLabel: `${clip.w}×${clip.h} · WebM`,
      name: `${plat.id}-${clip.w}x${clip.h}-nowm`,
      real: false,
    };
    await present(task, token);
    return true;
  };

  await flowRun(
    [
      { msg: "校验链接格式…", dur: 380 },
      { msg: `识别来源：${plat.name}（${plat.tag}）`, dur: 420 },
      { msg: "请求解析无水印直链…", dur: 480 + Math.random() * 400 },
      { msg: "提取标题 / 作者 / 数据…", dur: 520 },
    ],
    (pct) => showAfterEncode(pct)
  );
}

/* ---------- 真实服务模式解析（接入后自动启用） ---------- */
async function realParse(url, plat, token) {
  let task = null;
  await flowRun(
    [
      { msg: "校验链接格式…", dur: 300 },
      { msg: `识别来源：${plat.name}`, dur: 300 },
      { msg: `请求 ${API.endpoint} …`, dur: 500 },
    ],
    async (pct) => {
      flowSetStep(4, "active");
      flowSay("等待解析服务返回…");
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), API.timeout);
      let res;
      try {
        res = await fetch(API.endpoint, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          /* 完整口令原文随请求透传：部分平台短链解析需要验签上下文（tail） */
          body: JSON.stringify({ url, share_text: S.lastShareText, tail: S.lastTail }),
          signal: ctrl.signal,
        });
      } finally { clearTimeout(timer); }
      if (token !== S.parseToken) return;
      if (!res.ok) {
        /* 失败时优先透出服务端给出的具体原因（如抖音签名限制说明） */
        let emsg = `解析服务返回异常（HTTP ${res.status}）`;
        try {
          const ej = await res.json();
          if (ej && ej.msg) emsg = ej.msg;
        } catch (e) { /* 非 JSON 响应 */ }
        throw new Error(emsg);
      }
      const json = await res.json();
      if (!json || json.ok === false) throw new Error(json.msg || "解析服务返回失败");
      const d = json.data || json;
      const media = Array.isArray(d.media) ? d.media[0] : d.media;
      const author = d.author || {};
      const mimeType = media.type === "mp4" ? "video/mp4" : "video";
      task = {
        id: hashStr(url), url, plat, scale: 1,
        shareText: S.lastShareText, tail: S.lastTail,
        title: d.title || "未命名视频",
        author: {
          name: author.name || "未知作者",
          handle: (author.handle ? "@" + author.handle.replace(/^@/, "") : "@unknown"),
          tag: author.tag || "", verified: !!author.verified,
        },
        stats: { like: d.stats?.like || 0, comment: d.stats?.comment || 0, share: d.stats?.share || 0 },
        media: {
          url: media.url || d.url,          // 同源代理流地址（播放/下载无障碍）
          src: media.src || media.url,       // 上游无水印直链（展示/复制用）
          w: media.width || 0, h: media.height || 0,
          dur: media.duration || d.duration || 0,
          size: media.size || 0,
          type: mimeType,
        },
        cover: d.cover || media.cover || null,
        resLabel: d.qualityLabel ? `${d.qualityLabel} · 无水印 MP4` : (media.width && media.height ? `${media.width}×${media.height} · 无水印` : "无水印直链"),
        name: `video-${plat.id}`,
        real: true,
      };
      await present(task, token);
    }
  );
}

/* ======================= 13. 结果呈现 ======================= */
function statDom(id, val) { $id(id).textContent = fmtNum(val); }

async function present(task, token) {
  if (token !== S.parseToken) return;
  /* 资源管理：演示片源可能被 clipCache 跨任务共享（同链接复用同一 blob URL），
     因此 blob URL 一律不在此主动 revoke —— 由页面生命周期统一回收，避免
     误杀仍被引用/即将复用的 URL。封面由每次解析独立生成，同样保留即可。 */
  S.task = task;
  const v = D.video;

  // 元信息
  $id("badgePlat").textContent = task.plat.name;
  const rPlatChip = $id("rPlatChip");
  rPlatChip.innerHTML = `<i class="plat-char">${task.plat.char}</i>${task.plat.name} · ${task.plat.tag}`;
  rPlatChip.querySelector(".plat-char").style.background = task.plat.bg;
  $id("rTitle").textContent = task.title;
  $id("rAuthor").textContent = task.author.name;
  $id("rHandle").textContent = `${task.author.handle}${task.author.tag ? " · " + task.author.tag : ""}${task.author.verified ? " · 已认证" : ""}`;
  $id("rAvatar").textContent = task.author.name[0];
  $id("rAvatar").style.background = task.plat.bg;
  statDom("stLike", task.stats.like);
  statDom("stCmt", task.stats.comment);
  statDom("stShare", task.stats.share);
  $id("badgeSr").hidden = true;
  $id("srNote").textContent = "当前为源片画质，超分后自动替换预览与下载文件。";
  $$('input[name="sr"]').forEach((r) => (r.checked = r.value === "2"));
  $id("srRun").disabled = false;
  $id("rawUrl").textContent = task.real ? (task.media.src || task.media.url) : (task.media.url || "—");
  const shareInfo = {};
  if (task.shareText && task.shareText !== task.url) {
    shareInfo.shareText = task.shareText.length > 400 ? task.shareText.slice(0, 400) + "…" : task.shareText;
  }
  if (task.tail) shareInfo.tail = task.tail;
  $id("rawJson").textContent = JSON.stringify({
    ok: true,
    platform: task.plat.id,
    url: task.url,
    title: task.title,
    author: { name: task.author.name, handle: task.author.handle, verified: task.author.verified },
    stats: task.stats,
    watermark: false,
    ...shareInfo,
    media: {
      url: task.media.url,
      width: task.media.w, height: task.media.h,
      duration: task.media.dur || DEMO.dur,
      size: task.media.size || 0,
      type: task.media.type,
    },
  }, null, 2).replace(/^/gm, "  ");

  // 媒体
  if (task.media.url.startsWith("blob:")) v.src = task.media.url;
  else v.src = task.media.url;
  if (task.cover) v.poster = task.cover.url || task.cover;
  else v.removeAttribute("poster");
  v.load();

  $id("resTag").textContent = task.resLabel;
  const durSec = task.media.dur || DEMO.dur;
  $id("rDur").textContent = "0:" + String(Math.ceil(durSec)).padStart(2, "0");
  $id("dlSize").textContent = task.media.size ? fmtSize(task.media.size) : "—";

  // 玩家复位
  resetPlayerUI();
  D.result.hidden = false;
  $id("coverShade").classList.remove("show");
  const shell = $id("videoShell");
  shell.classList.remove("playing", "paused");

  /* 模式徽章：真实 vs 演示 —— 演示内容绝不冒充真实解析结果 */
  const badgeDemo = $id("badgeDemo");
  const badgeNw = $id("badgeNw");
  if (!task.real) {
    badgeDemo.hidden = false;
    badgeNw.innerHTML = `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>无水印 · 演示片源`;
  } else {
    badgeDemo.hidden = true;
    badgeNw.innerHTML = `<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>无水印直链`;
  }

  // 去水印效果演出（演示模式才展示）
  if (!task.real) {
    const wm = $id("wmFx");
    wm.hidden = false;
    wm.textContent = `${task.plat.name} @${task.author.handle.replace("@", "")} · 模拟水印`;
    setTimeout(() => { wm.hidden = true; }, 1900);
  } else {
    $id("wmFx").hidden = true;
  }

  // 打开解析信息抽屉
  const raw = $id("rawUrl").closest("details");
  if (raw) raw.open = false;

  addHistory(task);
  if (task.real) {
    toast(`解析成功 · ${task.plat.name}真实无水印视频已就绪（${task.resLabel}）`, "ok", 4200);
  } else {
    toast("演示预览已生成 —— 片源由本地引擎渲染，非平台真实内容（真实解析见页内提示）", "info", 5600);
  }
}

function resetPlayerUI() {
  const v = D.video;
  v.pause();
  try { v.currentTime = 0; } catch (e) { /* noop */ }
  $id("bigPlay").classList.remove("hide");
  $id("tNow").textContent = "00:00";
  $id("seek").value = 0;
  $id("seek").style.setProperty("--p", "0%");
  $id("bufBar").style.width = "0%";
  $id("vLoading").hidden = true;
  $id("ctrl").hidden = false;
  $id("tDur").textContent = fmtTime(v.duration || DEMO.dur);
}

/* ======================= 14. 播放器 ======================= */
const Player = (() => {
  const v = D.video;
  const shell = $id("videoShell");
  let hideTimer = null;

  function ctrlShow(ms = 2600) {
    $id("ctrl").classList.add("show");
    shell.classList.add("paused");
    clearTimeout(hideTimer);
    if (!v.paused && ms) {
      hideTimer = setTimeout(() => {
        $id("ctrl").classList.remove("show");
        shell.classList.remove("paused");
      }, ms);
    }
  }

  function toggle() {
    if (v.paused || v.ended) { v.play().catch(() => {}); } else v.pause();
  }

  function syncUI() {
    const playing = !v.paused && !v.ended;
    $id("pPlay").classList.toggle("playing", playing);
    $id("bigPlay").classList.toggle("hide", playing);
    shell.classList.toggle("playing", playing);
    if (playing) {
      $id("coverShade").style.opacity = "1";
      ctrlShow(2600);
    } else {
      $id("coverShade").style.opacity = "0";
      ctrlShow();
    }
    if (v.ended) {
      $id("bigPlay").classList.remove("hide");
      $id("pPlay").classList.remove("playing");
    }
  }

  v.addEventListener("play", syncUI);
  v.addEventListener("pause", syncUI);
  v.addEventListener("ended", syncUI);
  v.addEventListener("waiting", () => {
    $id("vLoading").hidden = false;
    $id("vLoadingMsg").textContent = "缓冲中…";
  });
  v.addEventListener("playing", () => { $id("vLoading").hidden = true; });
  v.addEventListener("loadedmetadata", () => {
    $id("tDur").textContent = fmtTime(v.duration);
    if (!$id("rDur").textContent || $id("rDur").textContent === "0:00") {
      $id("rDur").textContent = "0:" + String(Math.ceil(v.duration)).padStart(2, "0");
    }
  });
  v.addEventListener("error", () => {
    $id("vLoading").hidden = true;
    $id("vLoadingMsg").textContent = "";
    const t = S.task;
    if (t && !t.real && t.media.url.startsWith("blob:")) {
      toast("预览加载失败，请重试解析", "err");
    } else if (t) {
      toast('无法直接预览该片源（可能存在防盗链），可尝试「下载视频」', "info", 4000);
    }
  });
  v.addEventListener("timeupdate", () => {
    if (!v.duration) return;
    const pct = (v.currentTime / v.duration) * 100;
    $id("tNow").textContent = fmtTime(v.currentTime);
    $id("seek").value = Math.round(pct * 10);
    $id("seek").style.setProperty("--p", pct + "%");
  });
  v.addEventListener("progress", () => {
    try {
      if (v.buffered.length && v.duration) {
        const end = v.buffered.end(v.buffered.length - 1);
        $id("bufBar").style.width = Math.min(100, (end / v.duration) * 100) + "%";
      }
    } catch (e) { /* noop */ }
  });

  $id("bigPlay").addEventListener("click", toggle);
  $id("pPlay").addEventListener("click", toggle);
  $id("pFs").addEventListener("click", () => {
    if (document.fullscreenElement) document.exitFullscreen();
    else shell.requestFullscreen().catch(() => {});
  });

  const rates = [0.5, 0.75, 1, 1.25, 1.5, 2];
  let ri = 2;
  $id("rateBtn").addEventListener("click", () => {
    ri = (ri + 1) % rates.length;
    v.playbackRate = rates[ri];
    $id("rateBtn").textContent = rates[ri] + "×";
  });

  let seeking = false;
  const seek = $id("seek");
  seek.addEventListener("pointerdown", () => { seeking = true; });
  window.addEventListener("pointerup", () => { seeking = false; });
  seek.addEventListener("input", () => {
    const pct = seek.value / 10;
    $id("tNow").textContent = fmtTime((pct / 100) * (v.duration || DEMO.dur));
    seek.style.setProperty("--p", pct + "%");
    if (!v.duration) return;
    if (seeking) {
      try { v.currentTime = (pct / 100) * v.duration; } catch (e) { /* noop */ }
    }
  });
  seek.addEventListener("change", () => {
    const pct = seek.value / 10;
    try { if (v.duration) v.currentTime = (pct / 100) * v.duration; } catch (e) { /* noop */ }
  });

  shell.addEventListener("mousemove", () => ctrlShow());
  shell.addEventListener("mouseleave", () => {
    if (!v.paused) { clearTimeout(hideTimer); $id("ctrl").classList.remove("show"); shell.classList.remove("paused"); }
  });
  shell.addEventListener("dblclick", () => {
    if (document.fullscreenElement) document.exitFullscreen();
    else shell.requestFullscreen().catch(() => {});
  });

  document.addEventListener("fullscreenchange", () => {
    $id("resTag").style.display = document.fullscreenElement ? "none" : "";
  });

  document.addEventListener("keydown", (e) => {
    if (e.code !== "Space") return;
    const t = e.target;
    if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "BUTTON" || t.isContentEditable)) return;
    if (D.result.hidden) return;
    e.preventDefault();
    toggle();
  });

  return { toggle, syncUI };
})();

/* ======================= 15. 下载 / 复制 / 封面 ======================= */
/** 下载文件名基名：取结果标题（r-title）前 10 字左右；先清洗 Windows 非法字符再按码点截取 */
function fileBase(t) {
  const raw = (t && t.title ? String(t.title) : "").trim();
  const clean = raw.replace(/[\\/:*?"<>|\u0000-\u001f]/g, "").replace(/^[.\s]+|[.\s]+$/g, "");
  const head = [...clean].slice(0, 10).join(""); // 按码点截取，避免拆散 emoji
  return head || (t && t.name) || "视频";
}

function downloadFromUrl(url, filename) {
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.rel = "noopener";
  document.body.appendChild(a);
  a.click();
  a.remove();
}

async function dlVideo() {
  const t = S.task;
  if (!t) return;
  const btn = $id("dlVideo");
  const suffix = t.scale > 1 ? `-sr${t.scale}x` : "";
  const name = `${fileBase(t)}${suffix}.${t.media.type.includes("mp4") ? "mp4" : "webm"}`;
  if (t.media.url.startsWith("blob:")) {
    downloadFromUrl(t.media.url, name);
    toast(`已开始下载无水印视频 ✓（${name}）`, "ok", 3600);
    return;
  }
  // 真实远程片源：先尝试 CORS 拉取为本地文件，失败则新窗口打开
  btn.classList.add("loading");
  try {
    const res = await fetch(t.media.url, { mode: "cors" });
    if (!res.ok) throw new Error("status");
    const blob = await res.blob();
    const ext = (blob.type.includes("mp4") || !blob.type) ? "mp4" : "webm";
    downloadFromUrl(URL.createObjectURL(blob), `${name.replace(/\.\w+$/, "")}.${ext}`);
    toast("已开始下载 ✓", "ok");
  } catch (e) {
    window.open(t.media.url, "_blank", "noopener");
    toast("片源限制跨域下载，已在新窗口打开，可右键另存", "info", 4200);
  } finally {
    btn.classList.remove("loading");
  }
}

function dlCover() {
  const t = S.task;
  if (!t) return;
  const base = `${fileBase(t)}-封面`;
  if (t.cover && t.cover.url) {
    downloadFromUrl(t.cover.url, `${base}.png`);
    toast("封面图片已下载 ✓", "ok");
  } else if (t.cover) {
    const a = document.createElement("a");
    a.href = t.cover;
    a.download = `${base}.jpg`;
    a.rel = "noopener";
    a.click();
  } else {
    toast("该任务没有封面素材", "info");
  }
}

async function copyText(text, okMsg) {
  try {
    await navigator.clipboard.writeText(text);
  } catch (e) {
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.style.cssText = "position:fixed;opacity:0";
    document.body.appendChild(ta);
    ta.select();
    document.execCommand("copy");
    ta.remove();
  }
  toast(okMsg || "已复制到剪贴板", "ok");
}

/* ======================= 16. 超分 ======================= */
const SR = {
  active: false,
  signal: null,
  _iv: null,
};

function srTargetInfo(scale) {
  const t = S.task;
  if (!t) return "";
  const base = t.media.w || 640;
  const hBase = t.media.h || 360;
  if (t.real && scale > 1) return `目标输出 ${base * scale}×${hBase * scale}`;
  const w = 640 * scale, h = 360 * scale;
  return `${w}×${h}${scale > 1 ? "（真实分辨率重绘）" : ""}`;
}

function srModalSteps() {
  return [...$id("srSteps").children];
}

async function runSR() {
  const t = S.task;
  if (!t || SR.active || !S.task) return;
  const scale = parseInt($$('input[name="sr"]').find((r) => r.checked)?.value || "2", 10);
  const modal = $id("srModal");
  $id("srModalSub").textContent = `正在以 ${scale}× 重建 ${srTargetInfo(scale)}…`;
  $id("srBarFill").style.width = "0%";
  srModalSteps().forEach((li) => { li.classList.remove("active", "done"); });
  $id("srCancel").textContent = "取消";
  $id("srCancel").classList.remove("cancelling");
  modal.hidden = false;
  document.body.style.overflow = "hidden";
  SR.active = true;

  const labelMap = ["帧序列提取", "超分模型推理", "去噪与锐化", "编码封装"];
  const mark = (i, state) => {
    const li = srModalSteps()[i];
    if (li) li.classList.add(state);
  };
  mark(0, "active");
  $id("srBarFill").style.width = "6%";

  SR.signal = { cancelled: false };
  const myToken = S.parseToken;
  const baseCfg = {
    platName: t.plat.name, title: t.title, handle: t.author.handle.replace("@", ""),
    c1: t.plat.c1, c2: t.plat.c2, ac: t.plat.ac,
  };

  /* 阶段推进：编码进度 → 模态步骤/进度条 */
  const stageOf = (p) => (p < 0.42 ? 0 : p < 0.62 ? 1 : p < 0.8 ? 2 : 3);
  let lastStage = -1;
  const updateUI = (p) => {
    if (SR.signal.cancelled) return;
    const st = stageOf(p);
    if (st !== lastStage) {
      lastStage = st;
      srModalSteps().forEach((li, i) => {
        li.classList.toggle("active", i === st);
        li.classList.toggle("done", i < st);
      });
      if (st > 0) { srModalSteps()[st - 1].classList.remove("active"); srModalSteps()[st - 1].classList.add("done"); }
      $id("srModalSub").textContent =
        `正在以 ${scale}× 重建 ${srTargetInfo(scale)}… ${labelMap[st]} ${Math.round(p * 100)}%`;
    } else if (st === 3) {
      $id("srModalSub").textContent = `正在以 ${scale}× 重建 ${srTargetInfo(scale)}… 编码 ${Math.round(p * 100)}%`;
    }
    $id("srBarFill").style.width = Math.round(6 + p * 92) + "%";
  };
  updateUI(0.02);
  mark(0, "active");

  let clip = null;
  try {
    if (t.real) {
      /* 真实模式：调用解析服务同一接口，请求超分片源 */
      await sleep(900);
      if (SR.signal.cancelled) return;
      updateUI(0.5);
      await sleep(900);
      if (SR.signal.cancelled) return;
      clip = { ...t.media, sr: true };
      updateUI(1);
      await sleep(400);
    } else {
      clip = await encodeClip({
        cfg: baseCfg, scale, signal: SR.signal,
        onProgress: (p) => updateUI(p),
      });
    }
  } catch (e) {
    console.error(e);
    toast("超分失败，请重试", "err");
  } finally {
    clearInterval(SR._iv);
  }

  if (!clip || SR.signal.cancelled || myToken !== S.parseToken) {
    modal.hidden = true;
    document.body.style.overflow = "";
    SR.active = false;
    if (SR.signal && SR.signal.cancelled) toast("已取消超分任务", "info");
    return;
  }

  /* 成功：替换当前媒体（旧片源 URL 可能仍在 clipCache 中被同链接任务复用，故不 revoke） */
  if (S.task && myToken === S.parseToken) {
    S.task.media = clip;
    S.task.scale = scale;
    if (!t.real) {
      const cv2 = await makeCover(baseCfg, clip.w, clip.h);
      S.task.cover = cv2;
      D.video.poster = cv2.url;
    }
    D.video.src = clip.url;
    D.video.load();
    resetPlayerUI();
    $id("resTag").textContent = `${clip.w}×${clip.h} · WebM`;
    $id("badgeSr").hidden = false;
    $id("dlSize").textContent = fmtSize(clip.size);
    $id("srNote").innerHTML = `已完成 <b style="color:#fcd34d">${scale}× 超分</b>：${clip.w}×${clip.h}，文件 ${fmtSize(clip.size)}（本地引擎演示）`;
    const rawUrlEl = $id("rawUrl");
    rawUrlEl.textContent = clip.url;
    try {
      const pre = $id("rawJson");
      const json = JSON.parse(pre.textContent.replace(/^  /gm, ""));
      json.sr = { scale, width: clip.w, height: clip.h, size: clip.size };
      json.media = { ...json.media, width: clip.w, height: clip.h, size: clip.size, url: clip.url };
      pre.textContent = JSON.stringify(json, null, 2).replace(/^/gm, "  ");
    } catch (e) { /* noop */ }
    toast(`超分完成：${clip.w}×${clip.h} 无水印视频已就绪`, "ok", 4200);
  }
  modal.hidden = true;
  document.body.style.overflow = "";
  SR.active = false;
}

$id("srRun").addEventListener("click", runSR);
$id("srCancel").addEventListener("click", () => {
  if (!SR.active) { $id("srModal").hidden = true; document.body.style.overflow = ""; return; }
  if (SR.signal) SR.signal.cancelled = true;
  $id("srCancel").textContent = "正在取消…";
  $id("srCancel").classList.add("cancelling");
});
$$('input[name="sr"]').forEach((r) => {
  r.addEventListener("change", () => {
    if (!S.task) return;
    const s = parseInt(r.value, 10);
    if (r.checked) {
      const t = S.task;
      const w = (t.media.w || 640) * s, h = (t.media.h || 360) * s;
      const tip = t.real ? "接入后端后由真实模型推理" : "由本地引擎重绘，输出真实更高分辨率文件";
      $id("srNote").innerHTML = `将输出 <b style="color:#fcd34d">${w}×${h}</b> · ${tip}`;
    }
  });
});
$id("srModal").addEventListener("click", (e) => {
  if (e.target === $id("srModal")) { $id("srModal").hidden = true; document.body.style.overflow = ""; }
});

/* ======================= 17. 历史记录 ======================= */
function getHistory() {
  try { return JSON.parse(localStorage.getItem(S.historyKey)) || []; } catch (e) { return []; }
}
function saveHistory(list) {
  try { localStorage.setItem(S.historyKey, JSON.stringify(list.slice(0, 8))); } catch (e) { /* noop */ }
}
function addHistory(task) {
  const list = getHistory().filter((h) => h.url !== task.url);
  list.unshift({
    url: task.url, platId: task.plat.id, platChar: task.plat.char, platBg: task.plat.bg,
    title: task.title, ts: Date.now(),
  });
  saveHistory(list);
  renderHistory();
}
function renderHistory() {
  const list = getHistory();
  const box = $id("histChips");
  const wrap = $id("history");
  box.innerHTML = "";
  if (!list.length) { wrap.hidden = true; return; }
  wrap.hidden = false;
  for (const h of list) {
    const c = toEl(`<button class="hist-chip" title="点击重新解析：${esc(h.title)}">
      <span class="h-p" style="background:${h.platBg}">${h.platChar}</span>
      <span class="h-t">${esc(h.title)}</span>
      <span class="h-x">×</span></button>`);
    c.addEventListener("click", (e) => {
      if (e.target.closest(".h-x")) {
        saveHistory(list.filter((x) => x !== h));
        renderHistory();
        return;
      }
      runParse(h.url);
    });
    box.appendChild(c);
  }
}
$id("clearHist").addEventListener("click", () => {
  saveHistory([]);
  renderHistory();
  toast("历史记录已清空", "info");
});

/* ======================= 18. 事件绑定 ======================= */
$id("parseForm").addEventListener("submit", (e) => {
  e.preventDefault();
  /* 只有「开始解析」按钮或输入框回车允许提交；
     结果区按钮（播放/倍速/全屏/下载/超分等）均已声明 type=button，
     此处再兜底：其它 submitter 一律忽略，杜绝误触导致重复解析 */
  const sb = e.submitter;
  if (sb && sb.id !== "parseBtn") return;
  runParse(D.input.value);
});

D.input.addEventListener("input", () => {
  refreshDetect();
  $id("inputClear").hidden = !D.input.value;
});
D.input.addEventListener("paste", () => {
  /* 深度适配：保留用户粘贴的整段原文（不覆盖输入框），仅更新下方识别结果 */
  setTimeout(() => {
    refreshDetect();
    $id("inputClear").hidden = false;
  }, 30);
});
/* 点击识别出的链接 → 一键复制干净地址 */
$id("detectUrl").addEventListener("click", () => {
  const url = extractShareUrl(D.input.value).url;
  if (url) copyText(url, "已复制提取出的链接 ✓");
});
$id("inputClear").addEventListener("click", () => {
  D.input.value = "";
  refreshDetect();
  $id("inputClear").hidden = true;
  D.input.focus();
});
$$(".sample-chip").forEach((c) => {
  c.addEventListener("click", () => {
    D.input.value = c.dataset.url;
    $id("inputClear").hidden = false;
    refreshDetect();
    runParse(c.dataset.url);
  });
});
$id("dlVideo").addEventListener("click", dlVideo);
$id("dlCover").addEventListener("click", dlCover);
$id("copyRaw").addEventListener("click", () => {
  const t = S.task;
  if (!t) return;
  const link = t.real ? (t.media.src || t.media.url) : t.media.url;
  copyText(link, t.real ? "无水印直链已复制 ✓" : "已复制（演示直链，仅当前页面有效）");
});
$id("copyLink").addEventListener("click", () => {
  const t = S.task;
  if (!t) return;
  copyText(t.real ? S.lastUrl : S.lastUrl, "分享链接已复制 ✓");
});
$id("btnAgain").addEventListener("click", () => { if (S.lastUrl) runParse(S.lastUrl); });
$id("modePill").addEventListener("click", () => {
  if (API.endpoint) {
    toast(`已连接真实解析服务：${API.endpoint}`, "ok", 5000);
  } else {
    toast('演示模式：内置引擎本地模拟解析全流程（不联网）。真实接入方法见 README / FAQ', "info", 5600);
  }
});

/* 滚动显现 */
const io = new IntersectionObserver(
  (entries) => {
    entries.forEach((en) => {
      if (en.isIntersecting) {
        en.target.classList.add("in");
        io.unobserve(en.target);
      }
    });
  },
  { threshold: 0.12 }
);
$$(".reveal").forEach((el) => io.observe(el));

/* ======================= 19. 启动 ======================= */
(async function init() {
  injectCssOnce();
  refreshDetect();
  renderHistory();
  D.input.focus();

  /* 自动探测同源本地解析代理：命中 → 真实解析模式 */
  const isReal = await probePromise;
  const modeText = $id("modeText");
  const modeDot = $id("modePill").querySelector(".dot");
  const ann = document.querySelector(".announce");
  if (isReal) {
    modeText.textContent = "真实解析 · 本地代理已连接";
    modeDot.style.background = "var(--ok)";
    if (ann) {
      ann.innerHTML = `<span class="announce-dot" style="background:var(--ok)"></span>已连接真实解析代理：<strong>B站 / 抖音 均为官方接口真实数据</strong>（B站开放接口 · 抖音 a_bogus 签名直连；风控限流时服务端自动重试）`;
    }
  } else {
    modeText.textContent = "演示模式 · 本地模拟";
    modeDot.style.background = "var(--warn)";
    if (ann) {
      ann.innerHTML = `<span class="announce-dot"></span>当前为<strong>本地演示模式</strong>：片源由本地引擎生成（结果会标注「演示片源」，不冒充真实）。请启动本机「图片管理器」服务（start-lan.cmd 或 npm run dev）后刷新页面，B站/抖音等将自动切换为真实解析`;
    }
  }

  console.log("%cPureParse · 视频解析去水印工具（fluxframe 内嵌版）", "color:#818cf8;font-weight:bold;font-size:14px",
    `\n· 当前模式：${isReal ? "真实解析（本地代理 " + API.endpoint + "）" : "演示模式（本地模拟，可启动图片管理器服务启用真实解析）"}`);
})();

/* ============================================================
   20. 图片库集成钩子（save-lib.js 依赖）：暴露当前解析任务
   ============================================================ */
window.__pureParse = {
  getTask: () => S.task,
  toast: (msg, type, ms) => toast(msg, type, ms),
  esc,
  fileBase,
};
