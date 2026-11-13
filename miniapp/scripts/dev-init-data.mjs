// Development only: prints launch data signed with the HMAC scheme of the Mini App platform for a
// development bot token, so `pnpm run dev` can open a session on a local backend that runs with
// MINIAPP_HMAC_FALLBACK=true and the same token. The value is accepted for 300 seconds.
//
//   DEV_BOT_TOKEN=<development token> node scripts/dev-init-data.mjs <telegram user id>
//
// The token is read from the environment so it stays out of the shell history. Never use the
// token of a real bot here.
import { createHmac } from 'node:crypto';

const token = process.env.DEV_BOT_TOKEN;
const userId = Number(process.argv[2]);
if (!token || !Number.isSafeInteger(userId) || userId <= 0) {
  console.error('usage: DEV_BOT_TOKEN=<development token> node scripts/dev-init-data.mjs <telegram user id>');
  process.exit(2);
}

const fields = {
  auth_date: String(Math.floor(Date.now() / 1000)),
  user: JSON.stringify({ id: userId, first_name: 'Developer' }),
};
const checkString = Object.keys(fields)
  .sort()
  .map((key) => `${key}=${fields[key]}`)
  .join('\n');
const secret = createHmac('sha256', 'WebAppData').update(token).digest();
const hash = createHmac('sha256', secret).update(checkString).digest('hex');
console.log(new URLSearchParams({ ...fields, hash }).toString());
