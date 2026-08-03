"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.refreshTokenExpiry = exports.hashRefreshToken = exports.generateRefreshToken = exports.REFRESH_TOKEN_TTL_DAYS = void 0;
const crypto_1 = __importDefault(require("crypto"));
exports.REFRESH_TOKEN_TTL_DAYS = 90;
/**
 * An opaque 256-bit handle, deliberately not a JWT: it must be revocable by
 * deleting the stored row, which a self-validating token could not be.
 */
const generateRefreshToken = () => crypto_1.default.randomBytes(32).toString('hex');
exports.generateRefreshToken = generateRefreshToken;
/** Only this value is persisted — a database leak must not yield live sessions. */
const hashRefreshToken = (raw) => crypto_1.default.createHash('sha256').update(raw).digest('hex');
exports.hashRefreshToken = hashRefreshToken;
const refreshTokenExpiry = (now = new Date()) => {
    const expiry = new Date(now.getTime());
    expiry.setDate(expiry.getDate() + exports.REFRESH_TOKEN_TTL_DAYS);
    return expiry;
};
exports.refreshTokenExpiry = refreshTokenExpiry;
