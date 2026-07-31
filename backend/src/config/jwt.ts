import dotenv from 'dotenv';

// Loaded here rather than relying on index.ts: this module is pulled in by the
// route imports, which run before index.ts's own dotenv.config() call.
dotenv.config();

const secret = process.env.JWT_SECRET;

if (!secret) {
    console.error('❌ JWT_SECRET is not set. Refusing to start with an insecure default.');
    process.exit(1);
}

export const JWT_SECRET: string = secret;

/**
 * Deliberately still long, and NOT the 15m the refresh-token design calls for.
 *
 * The refresh endpoints below can be deployed safely on their own — they are inert until
 * a client calls them. Shortening this is the one part that is not safe alone: the shipped
 * Android app's background uploader (`AudioProcessor`) holds this token and has no way to
 * refresh yet, so a 15-minute lifetime would make it 401 on every upload that happens more
 * than 15 minutes after the app was last opened, queueing call transcripts instead of
 * delivering them.
 *
 * Drop this to '15m' in the same release that ships the native TokenRefresher — not before.
 * See docs/superpowers/plans/2026-07-31-persistent-login-refresh-tokens.md, Tasks 10-12.
 */
export const ACCESS_TOKEN_TTL = '7d';
