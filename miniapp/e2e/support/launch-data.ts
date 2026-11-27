// Launch data as Telegram signs it for third parties (research brief 02 section 11.3, ARCHITECTURE API-02), with the
// suite's own Ed25519 key in place of Telegram's. The backend accepts that key only outside production.
import { createHash, createHmac, createPrivateKey, createPublicKey, sign } from 'node:crypto';

export const BOT_ID = 7000000001;
export const TELEGRAM_USER_ID = 6000000123;

// The key is derived from a fixed phrase, so the configuration and every worker process agree on it without a file.
const seed = createHash('sha256').update('dosegram e2e launch-data key').digest();
const PKCS8_ED25519_PREFIX = Buffer.from('302e020100300506032b657004220420', 'hex');
const privateKey = createPrivateKey({ key: Buffer.concat([PKCS8_ED25519_PREFIX, seed]), format: 'der', type: 'pkcs8' });

/** The raw public key as 64 hex digits: the value of MINIAPP_INITDATA_PUBLIC_KEY. */
export const PUBLIC_KEY_HEX = createPublicKey(privateKey).export({ format: 'der', type: 'spki' }).subarray(-32).toString('hex');

/** A launch value for the suite's user, signed now; `startParam` is the startapp payload of a deep link. */
export function signedInitData(startParam?: string, authDate: Date = new Date()): string {
  const fields: Record<string, string> = {
    auth_date: String(Math.floor(authDate.getTime() / 1000)),
    query_id: `e2e-${Math.random().toString(36).slice(2)}`,
    user: JSON.stringify({ id: TELEGRAM_USER_ID, first_name: 'Test', language_code: 'en' }),
  };
  if (startParam !== undefined) fields.start_param = startParam;
  const checkString = Object.keys(fields)
    .sort()
    .map((key) => `${key}=${fields[key]}`)
    .join('\n');
  const signature = sign(null, Buffer.from(`${BOT_ID}:WebAppData\n${checkString}`), privateKey).toString('base64url');
  // Real launch data also carries the HMAC `hash` over every field but itself, signature included; the server ignores
  // it when a signature is present.
  return new URLSearchParams({ ...fields, signature, hash: hmacHash({ ...fields, signature }) }).toString();
}

/** The bot token the suite's launch data is hashed with; it belongs to no bot. */
export const TEST_BOT_TOKEN = `${BOT_ID}:AAE2e-rehearsal-token-of-no-bot-000000`;

/**
 * The HMAC `hash` as Telegram computes it (research brief 02 section 11.3): HMAC-SHA-256 of the sorted fields, keyed
 * by HMAC-SHA-256 of the bot token keyed by "WebAppData".
 */
export function hmacHash(fields: Record<string, string>, botToken: string = TEST_BOT_TOKEN): string {
  const checkString = Object.keys(fields)
    .sort()
    .map((key) => `${key}=${fields[key]}`)
    .join('\n');
  const secret = createHmac('sha256', 'WebAppData').update(botToken).digest();
  return createHmac('sha256', secret).update(checkString).digest('hex');
}
