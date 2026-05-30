require('dotenv').config({path:__dirname+'/.env'});
/**
 * 定时断网助手 — 激活码验证服务
 * 独立服务，端口 3002，通过 lilihaha.com/api/* 反向代理访问
 */
const express = require('express');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const app = express();

const PORT = process.env.ACTIVATION_PORT || 3002;

// ── 配置 ──
const ADMIN_KEY = process.env.ACTIVATION_ADMIN_KEY || 'change-me-to-a-secret-key';
const DB_PATH = path.join(__dirname, '..', 'activation-data', 'codes.json');
const DATA_DIR = path.dirname(DB_PATH);

// ── 数据库 ──
function loadDb() {
  try {
    if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
    if (!fs.existsSync(DB_PATH)) {
      const init = { codes: {}, devices: {} };
      fs.writeFileSync(DB_PATH, JSON.stringify(init, null, 2));
      return init;
    }
    return JSON.parse(fs.readFileSync(DB_PATH, 'utf8'));
  } catch { return { codes: {}, devices: {} }; }
}

function saveDb(db) {
  fs.writeFileSync(DB_PATH, JSON.stringify(db, null, 2));
}

// ── 工具 ──
function generateCode() {
  const segments = [];
  for (let i = 0; i < 4; i++) {
    segments.push(crypto.randomBytes(3).toString('hex').toUpperCase());
  }
  return segments.join('-');
}

function getDeviceId(req) {
  return req.body?.deviceId || req.headers['x-device-id'] || 'unknown';
}

app.use(express.json());

// ── 对外开放的激活 API ──

// 激活码激活
app.post('/api/activate', (req, res) => {
  const { code } = req.body;
  const deviceId = getDeviceId(req);
  const db = loadDb();

  if (!code) return res.json({ ok: false, msg: '请输入激活码' });

  const entry = db.codes[code.trim().toUpperCase()];
  if (!entry) return res.json({ ok: false, msg: '激活码不存在' });
  if (entry.used) {
    // 同一设备再次激活允许（重装/换机需要管理员解绑）
    if (entry.deviceId === deviceId) {
      return res.json({ ok: true, msg: '激活成功（重复激活）' });
    }
    return res.json({ ok: false, msg: '激活码已被使用' });
  }

  // 激活
  entry.used = true;
  entry.deviceId = deviceId;
  entry.activatedAt = new Date().toISOString();
  db.devices[deviceId] = { code, activatedAt: entry.activatedAt };
  saveDb(db);

  res.json({ ok: true, msg: '激活成功' });
});

// 检查设备是否已激活（App 启动时调用）
app.post('/api/check', (req, res) => {
  const deviceId = getDeviceId(req);
  const db = loadDb();
  const device = db.devices[deviceId];
  res.json({ ok: true, activated: !!device, deviceId });
});

// ── 管理端 API（需 ADMIN_KEY）──

// 中间件：验证管理员身份
function getAdminKey() {
  return process.env.ACTIVATION_ADMIN_KEY || 'change-me-to-a-secret-key';
}

function requireAdmin(req, res, next) {
  const key = req.headers['x-admin-key'] || req.query.key;
  if (key !== getAdminKey()) {
    console.log('[AUTH] 密钥不匹配:', JSON.stringify(key), 'vs', JSON.stringify(getAdminKey()));
    console.log('[AUTH] headers:', JSON.stringify(req.headers));
    return res.status(403).json({ ok: false, msg: '无权限' });
  }
  next();
}

// 批量生成激活码
app.post('/api/admin/generate', requireAdmin, (req, res) => {
  const count = Math.min(req.body?.count || 1, 100);
  const db = loadDb();
  const codes = [];
  for (let i = 0; i < count; i++) {
    let c;
    do { c = generateCode(); } while (db.codes[c]);
    db.codes[c] = { used: false, deviceId: null, activatedAt: null };
    codes.push(c);
  }
  saveDb(db);
  res.json({ ok: true, count, codes });
});

// 查看所有激活码
app.get('/api/admin/codes', requireAdmin, (req, res) => {
  const db = loadDb();
  const list = Object.entries(db.codes).map(([code, info]) => ({ code, ...info }));
  res.json({ ok: true, total: list.length, codes: list });
});

// 解绑设备
app.post('/api/admin/unbind', requireAdmin, (req, res) => {
  const { code } = req.body;
  const db = loadDb();
  const entry = db.codes[code];
  if (!entry) return res.json({ ok: false, msg: '激活码不存在' });
  const deviceId = entry.deviceId;
  entry.used = false;
  entry.deviceId = null;
  entry.activatedAt = null;
  delete db.devices[deviceId];
  saveDb(db);
  res.json({ ok: true, msg: '解绑成功' });
});

// 统计
app.get('/api/admin/stats', requireAdmin, (req, res) => {
  const db = loadDb();
  const total = Object.keys(db.codes).length;
  const used = Object.values(db.codes).filter(c => c.used).length;
  const devices = Object.keys(db.devices).length;
  res.json({ ok: true, total, used, available: total - used, devices });
});

// 修改管理密钥
app.post('/api/admin/change-key', requireAdmin, (req, res) => {
  const { newKey } = req.body;
  if (!newKey || newKey.length < 4) return res.json({ ok: false, msg: '密钥至少4位字符' });
  
  // 更新 .env 文件
  const envPath = path.join(__dirname, '.env');
  try {
    let env = fs.readFileSync(envPath, 'utf8');
    if (env.includes('ACTIVATION_ADMIN_KEY=')) {
      env = env.replace(/ACTIVATION_ADMIN_KEY=.*/, `ACTIVATION_ADMIN_KEY=${newKey}`);
    } else {
      env += `\nACTIVATION_ADMIN_KEY=${newKey}`;
    }
    fs.writeFileSync(envPath, env);
    
    // 更新当前进程的 ADMIN_KEY
    // 注意：完全生效需要 pm2 restart，但后续请求会用新密钥
    process.env.ACTIVATION_ADMIN_KEY = newKey;
    
    res.json({ ok: true, msg: '密钥已修改（完全生效需重启服务）' });
  } catch (e) {
    res.json({ ok: false, msg: '写入失败: ' + e.message });
  }
});

// ── APK 上传 API ──
const TARGET_DIR = '/var/www/lilihaha.com/public/network-guard';

// 文件上传配置（multer 替代品，使用 busboy 或原生处理）
app.post('/api/upload-apk', requireAdmin, (req, res) => {
  const contentType = req.headers['content-type'] || '';
  if (!contentType.includes('multipart/form-data')) {
    return res.json({ ok: false, msg: '请使用 multipart/form-data 上传' });
  }

  const boundary = contentType.split('boundary=')[1];
  if (!boundary) return res.json({ ok: false, msg: '缺少 boundary' });

  const chunks = [];
  req.on('data', chunk => chunks.push(chunk));
  req.on('end', () => {
    const raw = Buffer.concat(chunks);

    // 从 multipart 中提取文件名和文件内容
    const parts = raw.toString('binary').split(new RegExp(boundary, 'g'));
    for (const part of parts) {
      if (!part.includes('filename="')) continue;
      const headerEnd = part.indexOf('\r\n\r\n');
      if (headerEnd === -1) continue;
      const header = part.substring(0, headerEnd);
      const fileNameMatch = header.match(/filename="([^"]+)"/);
      const name = fileNameMatch ? fileNameMatch[1] : 'app-arm64-v8a-debug.apk';

      // 提取文件内容（跳过 header 和尾部 boundary 标记）
      const fileData = part.substring(headerEnd + 4);
      // 去除尾部 \r\n-- 或 \r\n
      const endIdx = fileData.lastIndexOf('\r\n');
      const cleanData = endIdx > 0 ? fileData.substring(0, endIdx) : fileData;
      const fileBuffer = Buffer.from(cleanData, 'binary');

      if (fileBuffer.length < 1024) {
        return res.json({ ok: false, msg: '文件太小，可能不是有效的 APK' });
      }

      // 确保目录存在
      if (!fs.existsSync(TARGET_DIR)) {
        fs.mkdirSync(TARGET_DIR, { recursive: true });
      }

      const targetPath = path.join(TARGET_DIR, 'app-arm64-v8a-debug.apk');
      fs.writeFileSync(targetPath, fileBuffer);

      console.log(`📦 APK 已部署: ${targetPath} (${(fileBuffer.length / 1024 / 1024).toFixed(1)}MB)`);
      return res.json({ ok: true, msg: 'APK 部署成功', size: fileBuffer.length });
    }

    res.json({ ok: false, msg: '未找到文件内容' });
  });
});

// ── 启动 ──
app.listen(PORT, () => {
  console.log(`✅ 激活码服务已启动，端口 ${PORT}`);
  const ak = getAdminKey();
  console.log(`   管理密钥: ${ak === 'change-me-to-a-secret-key' ? '⚠️ 请修改默认密钥!' : '已设置'}`);
  console.log(`   数据文件: ${DB_PATH}`);
});
