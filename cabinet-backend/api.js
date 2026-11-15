import { createHash, randomBytes, randomUUID } from 'node:crypto';

const maxUpload = 16 * 1024 * 1024;
const inviteLifetime = 7 * 24 * 60 * 60 * 1000;

export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function requireValue(condition, status, message) {
  if (!condition) throw new HttpError(status, message);
}

function inviteHash(code) {
  return createHash('sha256').update(code).digest('hex');
}

async function readBody(request, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    requireValue(size <= limit, 413, 'Request body is too large.');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function readJson(request) {
  const body = await readBody(request, 4096);
  let value;
  try {
    value = JSON.parse(body);
  } catch {
    throw new HttpError(400, 'Expected a JSON object.');
  }
  requireValue(value && typeof value === 'object' && !Array.isArray(value), 400, 'Expected a JSON object.');
  return value;
}

export function createHandler({ verifyToken, cabinets, files }) {
  return async (request, response) => {
    const send = (status, value) => {
      response.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      response.end(JSON.stringify(value));
    };
    try {
      const url = new URL(request.url, 'http://localhost');
      const method = request.method;
      if (method === 'GET' && url.pathname === '/health') return send(200, { ok: true });
      const bearer = /^Bearer ([\w.-]+)$/.exec(request.headers.authorization ?? '');
      const user = bearer && await verifyToken(bearer[1]);
      requireValue(user, 401, 'Sign in with Google.');

      if (url.pathname === '/v1/cabinets' && method === 'POST') {
        const { name } = await readJson(request);
        requireValue(typeof name === 'string' && name.trim().length > 0 && name.length <= 100, 400, 'Enter a cabinet name.');
        const cabinet = { id: randomUUID(), name: name.trim(), owner: user.sub };
        await cabinets.create(cabinet);
        return send(201, { id: cabinet.id, name: cabinet.name, role: 'owner' });
      }
      if (url.pathname === '/v1/cabinets' && method === 'GET') {
        return send(200, await cabinets.list(user.sub));
      }

      const route = /^\/v1\/cabinets\/([\da-f-]{36})\/(join|invites|members|files)(?:\/([\da-zA-Z_-]{1,128}))?$/.exec(url.pathname);
      requireValue(route, 404, 'Route not found.');
      const [, cabinetId, resource, id] = route;

      if (resource === 'join' && !id && method === 'POST') {
        const { code } = await readJson(request);
        requireValue(typeof code === 'string' && /^[\w-]{43}$/.test(code), 404, 'Invitation is unavailable.');
        requireValue(user.email && user.authoritativeEmail, 403, 'Sign in with the invited Gmail or Google Workspace account.');
        await cabinets.join(cabinetId, inviteHash(code), user);
        return send(200, { id: cabinetId, role: 'member' });
      }
      if (resource === 'invites' && !id && method === 'POST') {
        const { email } = await readJson(request);
        const address = typeof email === 'string' ? email.trim().toLowerCase() : '';
        requireValue(address.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address), 400, 'Enter an email address.');
        const code = randomBytes(32).toString('base64url');
        await cabinets.invite(cabinetId, user.sub, inviteHash(code), address, Date.now() + inviteLifetime);
        return send(201, { cabinetId, code });
      }
      if (resource === 'members' && !id && method === 'GET') {
        return send(200, await cabinets.members(cabinetId, user.sub));
      }
      if (resource === 'members' && id && method === 'DELETE') {
        await cabinets.remove(cabinetId, user.sub, id);
        return send(200, { removed: true });
      }
      if (resource === 'files') {
        await cabinets.requireMember(cabinetId, user.sub);
        if (!id && method === 'GET') return send(200, await cabinets.listFiles(cabinetId));
        if (id && method === 'GET') {
          const file = await cabinets.getFile(cabinetId, id);
          requireValue(file, 404, 'File not found.');
          const bytes = await files.read(cabinetId, id);
          response.writeHead(200, {
            'cache-control': 'no-store',
            'content-type': 'application/octet-stream',
            'content-disposition': "attachment; filename*=UTF-8''" + encodeURIComponent(file.name).replace(/['()*]/g, char => '%' + char.charCodeAt(0).toString(16)),
            'x-content-type-options': 'nosniff'
          });
          return response.end(bytes);
        }
        if (!id && method === 'POST') {
          const name = url.searchParams.get('name')?.trim();
          requireValue(name && name.length <= 255 && !/[\u0000-\u001f\u007f/\\]/u.test(name), 400, 'Enter a valid file name.');
          const bytes = await readBody(request, maxUpload);
          const file = { id: randomUUID(), name, size: bytes.length, createdAt: Date.now() };
          await files.write(cabinetId, file.id, bytes);
          try {
            await cabinets.addFile(cabinetId, user.sub, file);
          } catch (error) {
            if (error instanceof HttpError) await files.remove(cabinetId, file.id);
            throw error;
          }
          return send(201, file);
        }
      }
      throw new HttpError(404, 'Route not found.');
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('Cabinet request failed:', error.code ?? error.name);
      send(error instanceof HttpError ? error.status : 500,
        { error: error instanceof HttpError ? error.message : 'Cabinet request failed.' });
    }
  };
}
