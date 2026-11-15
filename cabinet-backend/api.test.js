import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { test } from 'node:test';
import { createHandler } from './api.js';
import { cloudCabinets } from './cloud.js';

function memoryFirestore() {
  const entries = new Map();
  const read = ref => ({ exists: entries.has(ref.path), data: () => entries.get(ref.path) });
  const collection = path => ({ doc: id => document(path + '/' + id) });
  const document = path => ({ path, collection: name => collection(path + '/' + name), get() { return read(this); } });
  const writes = () => {
    const changes = [];
    return {
      get: read,
      getAll: (...refs) => refs.map(read),
      create: (ref, data) => changes.push(() => {
        assert.equal(entries.has(ref.path), false);
        entries.set(ref.path, data);
      }),
      set: (ref, data) => changes.push(() => entries.set(ref.path, data)),
      delete: ref => changes.push(() => entries.delete(ref.path)),
      commit: () => changes.forEach(change => change())
    };
  };
  return {
    collection,
    batch: writes,
    async runTransaction(work) {
      const transaction = writes();
      await work(transaction);
      transaction.commit();
    }
  };
}

test('an invited account can join and exchange files until its membership is removed', async t => {
  const bytes = new Map();
  const server = createServer(createHandler({
    verifyToken: async token => ['owner', 'partner', 'outsider'].includes(token)
      ? { sub: token, email: token + '@gmail.com', authoritativeEmail: true }
      : null,
    cabinets: cloudCabinets(memoryFirestore()),
    files: {
      write: async (cabinet, id, body) => bytes.set(cabinet + '/' + id, body),
      read: async (cabinet, id) => bytes.get(cabinet + '/' + id)
    }
  })).listen(0, '127.0.0.1');
  t.after(() => server.close());
  await once(server, 'listening');
  const request = (path, user, method = 'GET', body) => fetch(
    'http://127.0.0.1:' + server.address().port + path,
    { method, headers: user ? { authorization: 'Bearer ' + user } : {}, body }
  );

  assert.equal((await request('/v1/cabinets')).status, 401);
  const created = await request('/v1/cabinets', 'owner', 'POST', JSON.stringify({ name: 'Test cabinet' }));
  assert.equal(created.status, 201);
  const { id } = await created.json();
  const base = '/v1/cabinets/' + id;
  const upload = await request(base + '/files?name=sample.pdf', 'owner', 'POST', 'sample document');
  assert.equal(upload.status, 201);
  const file = base + '/files/' + (await upload.json()).id;
  assert.equal((await request(file, 'partner')).status, 404);

  const invite = await request(base + '/invites', 'owner', 'POST', JSON.stringify({ email: 'partner@gmail.com' }));
  assert.equal(invite.status, 201);
  const join = base + '/join';
  const invitation = JSON.stringify({ code: (await invite.json()).code });
  assert.equal((await request(join, 'outsider', 'POST', invitation)).status, 404);
  assert.equal((await request(join, 'partner', 'POST', invitation)).status, 200);
  assert.equal((await request(join, 'partner', 'POST', invitation)).status, 404);
  assert.equal(await (await request(file, 'partner')).text(), 'sample document');
  assert.equal((await request(file, 'outsider')).status, 404);
  assert.equal((await request(base + '/files?name=partner.pdf', 'partner', 'POST', 'partner sample')).status, 201);
  assert.equal((await request(base + '/invites', 'partner', 'POST', JSON.stringify({ email: 'outsider@gmail.com' }))).status, 404);
  assert.equal((await request(base + '/members/partner', 'owner', 'DELETE')).status, 200);
  assert.equal((await request(file, 'partner')).status, 404);
  assert.equal((await request(base + '/files?name=denied.pdf', 'partner', 'POST', 'denied')).status, 404);
});
