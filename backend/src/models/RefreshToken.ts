import mongoose, { Schema, Document } from 'mongoose';

export type RefreshClient = 'web' | 'native';

export interface IRefreshToken extends Document {
    userId: mongoose.Types.ObjectId;
    tokenHash: string;
    client: RefreshClient;
    expiresAt: Date;
    createdAt: Date;
}

const RefreshTokenSchema = new Schema<IRefreshToken>({
    userId: { type: Schema.Types.ObjectId, ref: 'User', required: true },
    tokenHash: { type: String, required: true },
    client: { type: String, required: true, enum: ['web', 'native'] },
    expiresAt: { type: Date, required: true },
    createdAt: { type: Date, default: Date.now },
});

/**
 * One row per (user, client). This is what makes rotation safe with two clients:
 * a web refresh upserts only the web row and cannot invalidate native's, so the
 * background uploader and the WebView never race each other into a spurious logout.
 */
RefreshTokenSchema.index({ userId: 1, client: 1 }, { unique: true });

/** Lookup path for the refresh endpoint. */
RefreshTokenSchema.index({ tokenHash: 1 });

/** Mongo reaps expired rows on its own; nothing in app code sweeps this collection. */
RefreshTokenSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });

export default mongoose.model<IRefreshToken>('RefreshToken', RefreshTokenSchema);
