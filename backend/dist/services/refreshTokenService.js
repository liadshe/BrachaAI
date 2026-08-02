"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.revokeRefreshToken = exports.rotateRefreshToken = exports.issueRefreshToken = void 0;
const RefreshToken_1 = __importDefault(require("../models/RefreshToken"));
const refreshToken_1 = require("../utils/refreshToken");
/**
 * Issues a fresh refresh token for one client, replacing whatever that client held.
 * The upsert on (userId, client) is what keeps web and native independent.
 */
const issueRefreshToken = async (userId, client) => {
    const raw = (0, refreshToken_1.generateRefreshToken)();
    await RefreshToken_1.default.findOneAndUpdate({ userId, client }, {
        userId,
        client,
        tokenHash: (0, refreshToken_1.hashRefreshToken)(raw),
        expiresAt: (0, refreshToken_1.refreshTokenExpiry)(),
        createdAt: new Date(),
    }, { upsert: true, new: true });
    return raw;
};
exports.issueRefreshToken = issueRefreshToken;
/**
 * Verifies and rotates. Returns null for anything the caller should treat as a real
 * logout — unknown token, wrong client, or expired.
 */
const rotateRefreshToken = async (raw, client) => {
    const existing = await RefreshToken_1.default.findOne({
        tokenHash: (0, refreshToken_1.hashRefreshToken)(raw),
        client,
    });
    if (!existing) {
        return null;
    }
    if (existing.expiresAt.getTime() <= Date.now()) {
        await RefreshToken_1.default.deleteOne({ _id: existing._id });
        return null;
    }
    const userId = existing.userId.toString();
    const refreshToken = await (0, exports.issueRefreshToken)(userId, client);
    return { userId, refreshToken };
};
exports.rotateRefreshToken = rotateRefreshToken;
/** Used by logout. Deletes only the row for the presented token. */
const revokeRefreshToken = async (raw) => {
    await RefreshToken_1.default.deleteOne({ tokenHash: (0, refreshToken_1.hashRefreshToken)(raw) });
};
exports.revokeRefreshToken = revokeRefreshToken;
