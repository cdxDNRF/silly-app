// SillyTavern 启动引导（自研）。由 JNI 直接加载，负责把运行环境钉死在应用私有目录。
// 用 .cjs 是为了能在 CommonJS 上下文里做同步 chdir，再用动态 import 加载 ESM 的 server.js。
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');

const root = process.env.ST_ROOT || process.cwd();

// 早于任何依赖加载先落一行日志：真机上只要看到这行，就说明 JNI + Node 已经跑通。
console.log('[bootstrap] ==== start ====');
console.log('[bootstrap] node', process.version, 'platform', process.platform, 'arch', process.arch);
console.log('[bootstrap] cwd', process.cwd(), 'ST_ROOT', root);
console.log('[bootstrap] argv', JSON.stringify(process.argv));
for (const m of ['child_process', 'worker_threads', 'crypto', 'http', 'fs', 'os']) {
    try { require(m); console.log('[bootstrap] module OK:', m); }
    catch (e) { console.error('[bootstrap] module MISSING:', m, e && e.message); }
}

try {
    process.chdir(root);
    console.log('[bootstrap] chdir ->', process.cwd());
} catch (e) {
    console.error('[bootstrap] chdir failed:', e && e.stack ? e.stack : e);
}

// nodejs-mobile 没有可用的外部进程。ST 启动链路里 getVersion() 会用
// command-exists 的 execSync('command -v git') 探测 git，在 Android 上这个
// 同步调用可能长时间阻塞甚至不返回。这里让它立即抛错，command-exists
// 自身会捕获并返回 false（等价于"手机上没有 git"），不影响任何功能。
try {
    const cp = require('child_process');
    const origExecSync = cp.execSync;
    cp.execSync = function (cmd) {
        const s = String(cmd || '');
        if (/^(command -v|where )/.test(s) || /\bgit\b/.test(s)) {
            const err = new Error('[tavern] execSync disabled on Android: ' + s);
            err.status = 127;
            throw err;
        }
        return origExecSync.apply(this, arguments);
    };
    console.log('[bootstrap] execSync guarded for Android');
} catch (e) {
    console.error('[bootstrap] execSync guard skipped:', e && e.message);
}

for (const d of ['data', 'cache', 'downloads', 'prebuilt']) {
    try { fs.mkdirSync(path.join(root, d), { recursive: true }); } catch (_) {}
}
try { fs.mkdirSync(path.join(__dirname, '..', 'downloads'), { recursive: true }); } catch (_) {}

console.log('[bootstrap] prebuilt/lib.js exists:',
    fs.existsSync(path.join(root, 'prebuilt', 'lib.js')));

process.on('uncaughtException', (err) => {
    console.error('[bootstrap] uncaught:', err && err.stack ? err.stack : err);
});
process.on('unhandledRejection', (err) => {
    console.error('[bootstrap] unhandled rejection:', err && err.stack ? err.stack : err);
});
process.on('exit', (code) => {
    console.log('[bootstrap] process exit, code =', code);
});

// 看门狗：只提示，不干预，方便在真机日志里定位"卡在初始化"的情况。
setTimeout(() => {
    console.error('[bootstrap] WATCHDOG: 25s elapsed; if no "listening" line below, startup is stuck');
}, 25000);

const server = path.join(root, 'server.js');
console.log('[bootstrap] importing SillyTavern from', server);
import(pathToFileURL(server).href)
    .then(() => console.log('[bootstrap] server.js import resolved'))
    .catch((err) => {
        console.error('[bootstrap] failed to start SillyTavern:', err && err.stack ? err.stack : err);
    });
