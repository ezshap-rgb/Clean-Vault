import { createHash } from 'node:crypto';
import { HttpError } from './api.js';

export function cloudCabinets(db) {
  const cabinet = id => db.collection('cabinets').doc(id);
  const accountCabinet = (sub, id) => db.collection('accounts').doc(sub).collection('cabinets').doc(id);
  const member = (id, sub) => cabinet(id).collection('members').doc(sub);
  const invitation = (id, email) => cabinet(id).collection('invites')
    .doc(createHash('sha256').update(email).digest('hex'));
  const file = (id, fileId) => cabinet(id).collection('files').doc(fileId);

  async function requireOwner(transaction, id, sub) {
    const snapshot = await transaction.get(cabinet(id));
    if (snapshot.data()?.owner !== sub) throw new HttpError(404, 'Cabinet not found.');
  }

  return {
    async create({ id, name, owner }) {
      const batch = db.batch();
      batch.create(cabinet(id), { name, owner });
      batch.create(member(id, owner), { role: 'owner' });
      batch.create(accountCabinet(owner, id), { name, role: 'owner' });
      await batch.commit();
    },
    async list(sub) {
      const { docs } = await db.collection('accounts').doc(sub).collection('cabinets').orderBy('name').limit(100).get();
      return docs.map(doc => ({ id: doc.id, ...doc.data() }));
    },
    invite(id, owner, hash, email, expiresAt) {
      return db.runTransaction(async transaction => {
        await requireOwner(transaction, id, owner);
        transaction.set(invitation(id, email), { hash, expiresAt });
      });
    },
    join(id, hash, user) {
      return db.runTransaction(async transaction => {
        const [cabinetSnapshot, inviteSnapshot, membership] = await transaction.getAll(
          cabinet(id), invitation(id, user.email), member(id, user.sub)
        );
        const invite = inviteSnapshot.data();
        if (!cabinetSnapshot.exists || !invite || invite.expiresAt <= Date.now() || invite.hash !== hash) {
          throw new HttpError(404, 'Invitation is unavailable.');
        }
        if (membership.exists) throw new HttpError(409, 'You already belong to this cabinet.');
        transaction.create(member(id, user.sub), { role: 'member', email: user.email });
        transaction.create(accountCabinet(user.sub, id), { name: cabinetSnapshot.data().name, role: 'member' });
        transaction.delete(invitation(id, user.email));
      });
    },
    async members(id, sub) {
      if ((await cabinet(id).get()).data()?.owner !== sub) throw new HttpError(404, 'Cabinet not found.');
      const { docs } = await cabinet(id).collection('members').where('role', '==', 'member').limit(100).get();
      return docs.map(doc => ({ id: doc.id, email: doc.data().email }));
    },
    remove(id, owner, sub) {
      return db.runTransaction(async transaction => {
        await requireOwner(transaction, id, owner);
        if (sub === owner) throw new HttpError(400, 'The owner cannot be removed.');
        const snapshot = await transaction.get(member(id, sub));
        if (!snapshot.exists) throw new HttpError(404, 'Member not found.');
        transaction.delete(member(id, sub));
        transaction.delete(accountCabinet(sub, id));
        transaction.delete(invitation(id, snapshot.data().email));
      });
    },
    async requireMember(id, sub) {
      if (!(await member(id, sub).get()).exists) throw new HttpError(404, 'Cabinet not found.');
    },
    async listFiles(id) {
      const { docs } = await cabinet(id).collection('files').orderBy('createdAt', 'desc').limit(100).get();
      return docs.map(doc => doc.data());
    },
    async getFile(id, fileId) {
      return (await file(id, fileId).get()).data();
    },
    addFile(id, sub, metadata) {
      return db.runTransaction(async transaction => {
        const membership = await transaction.get(member(id, sub));
        if (!membership.exists) throw new HttpError(404, 'Cabinet not found.');
        transaction.create(file(id, metadata.id), metadata);
      });
    }
  };
}

export function cloudFiles(bucket) {
  const object = (id, fileId) => bucket.file(`cabinets/${id}/${fileId}`);
  return {
    async read(id, fileId) {
      const [bytes] = await object(id, fileId).download();
      return bytes;
    },
    async write(id, fileId, bytes) {
      await object(id, fileId).save(bytes, {
        resumable: false,
        contentType: 'application/octet-stream',
        preconditionOpts: { ifGenerationMatch: 0 }
      });
    },
    async remove(id, fileId) {
      await object(id, fileId).delete({ ignoreNotFound: true });
    }
  };
}
