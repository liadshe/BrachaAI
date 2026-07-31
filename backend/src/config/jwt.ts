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
 * Short by design: the session is kept alive by refresh tokens, not by a long-lived
 * access token. Both the WebView and the native uploader refresh on 401.
 */
export const ACCESS_TOKEN_TTL = '15m';
