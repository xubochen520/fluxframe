/* ============================================================
   抖音 a_bogus 签名器（运行时模块）
   ------------------------------------------------------------
   来源：ylcangel/douyin_sign（Apache-2.0，V 1.0.1.19-fix.01）
   实现：纯 JS 重构版 a_bogus（sm3 + 魔改 RC4 + 自绘 base64），
   在 Node vm 沙箱中加载，无需任何浏览器环境与 npm 依赖。

   用法：
     import { signDouyin } from "./dyab/index.mjs";
     const ab = signDouyin("aweme_id=...&aid=6383&..."); // 与请求 URL 的查询串逐字一致
   ============================================================ */
import vm from "node:vm";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export const DY_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

let ctx = null;
let loadError = null;

function ensureCtx() {
  if (ctx) return ctx;
  try {
    const dir = fileURLToPath(new URL(".", import.meta.url));
    const sandbox = {
      console: { log() {}, warn() {}, error() {} }, // 签名实现内部调试日志：静默
      navigator: { userAgent: DY_UA, platform: "Win32" },
      window: {
        innerWidth: 2048,
        innerHeight: 960,
        outerWidth: 2554,
        outerHeight: 1386,
        screen: { availWidth: 2560, availHeight: 1392, width: 2560, height: 1440, sizeWidth: 2560, sizeHeight: 1440 },
      },
    };
    ctx = vm.createContext(sandbox);
    // 加载顺序与上游 test.html 一致
    vm.runInContext(readFileSync(new URL("./utils.js", import.meta.url), "utf8"), ctx, { filename: "dyab/utils.js" });
    vm.runInContext(readFileSync(new URL("./sm3.js", import.meta.url), "utf8"), ctx, { filename: "dyab/sm3.js" });
    vm.runInContext('programVersion = "release";', ctx); // 关闭 debug 日志开关
    /* 冒烟测试：确认 makeABogus 可产出合法长度签名 */
    const smoke = vm.runInContext('makeABogus("aweme_id=7681199202382269706&aid=6383", 0)', ctx);
    if (typeof smoke !== "string" || smoke.length < 100) throw new Error("签名冒烟测试未通过");
  } catch (e) {
    loadError = e;
    ctx = null;
  }
  return ctx;
}

/**
 * 生成 a_bogus。uri 必须与真实请求 URL 的问号后查询串逐字一致（参数顺序也一致）。
 * @param {string} uri  查询串（不含 "?"）
 * @returns {string|null}
 */
export function signDouyin(uri) {
  const c = ensureCtx();
  if (!c) return null;
  try {
    const ab = vm.runInContext(`makeABogus(${JSON.stringify(uri)}, 0)`, c);
    return typeof ab === "string" && ab.length >= 100 ? ab : null;
  } catch (e) {
    return null;
  }
}

export function signerReady() {
  ensureCtx();
  return !!ctx;
}

export function signerError() {
  ensureCtx();
  return loadError ? loadError.message : "";
}
