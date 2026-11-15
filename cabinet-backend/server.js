import { createServer } from 'node:http';
import { Firestore } from '@google-cloud/firestore';
import { Storage } from '@google-cloud/storage';
import { OAuth2Client } from 'google-auth-library';
import { createHandler } from './api.js';
import { cloudCabinets, cloudFiles } from './cloud.js';

const { GOOGLE_WEB_CLIENT_ID, CABINET_BUCKET, PORT = '8080' } = process.env;
if (!GOOGLE_WEB_CLIENT_ID || !CABINET_BUCKET) throw new Error('Set GOOGLE_WEB_CLIENT_ID and CABINET_BUCKET.');

const google = new OAuth2Client();
const verifyToken = async idToken => {
  try {
    const ticket = await google.verifyIdToken({ idToken, audience: GOOGLE_WEB_CLIENT_ID });
    const claims = ticket.getPayload();
    if (!/^\d{1,64}$/.test(claims?.sub ?? '')) return null;
    const email = claims.email_verified && claims.email ? claims.email.toLowerCase() : '';
    return { sub: claims.sub, email, authoritativeEmail: email.endsWith('@gmail.com') || Boolean(claims.hd) };
  } catch {
    return null;
  }
};

createServer(createHandler({
  verifyToken,
  cabinets: cloudCabinets(new Firestore()),
  files: cloudFiles(new Storage().bucket(CABINET_BUCKET))
})).listen(Number(PORT), '0.0.0.0');
