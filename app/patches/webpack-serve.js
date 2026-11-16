// 移动端适配补丁（自研，随构建覆盖上游同名文件）。
// 上游 SillyTavern 会在 server.listen() 之前 await 一次完整 webpack 编译，
// 且 webpack.config.js 顶层 await getVersion() 会调用 child_process。
// 在 Android / nodejs-mobile 上这一步极慢，或抛异常后 Promise 永不 resolve，
// 导致端口永远不监听。npm 发布包已自带预编译产物，这里直接复用并跳过编译。
import path from 'node:path';
import fs from 'node:fs';

function getPrebuiltLib() {
    const root = process.env.ST_ROOT || process.cwd();
    return path.join(root, 'prebuilt', 'lib.js');
}

export default function getWebpackServeMiddleware() {
    /** 直接把预编译的前端库 bundle 交给浏览器，不再走 webpack-dev-middleware */
    function devMiddleware(req, res, next) {
        const parsedPath = path.parse(req.path);
        if (req.method === 'GET' && parsedPath.dir === '/' && parsedPath.base === 'lib.js') {
            const prebuilt = getPrebuiltLib();
            if (fs.existsSync(prebuilt)) {
                res.type('text/javascript');
                return res.sendFile(prebuilt);
            }
            console.error('[tavern] prebuilt lib.js missing at', prebuilt);
        }
        next();
    }

    /** 上游会 await 这个函数；移动端直接返回，保证秒级进入 listen() */
    devMiddleware.runWebpackCompiler = () => {
        const prebuilt = getPrebuiltLib();
        if (fs.existsSync(prebuilt)) {
            console.log('[tavern] using prebuilt lib.js, webpack compile skipped');
        } else {
            console.error('[tavern] WARNING: prebuilt lib.js missing, frontend may fail');
        }
        return Promise.resolve();
    };

    return devMiddleware;
}
