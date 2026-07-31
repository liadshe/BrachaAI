import { Request, Response } from 'express';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import User from '../models/User';
import { JWT_SECRET, ACCESS_TOKEN_TTL } from '../config/jwt';
import { RefreshClient } from '../models/RefreshToken';
import {
    issueRefreshToken,
    rotateRefreshToken,
    revokeRefreshToken,
} from '../services/refreshTokenService';

export const signup = async (req: Request, res: Response) => {
    try {
        const client = parseClient(req.body.client);
        if (!client) {
            return res.status(400).json({ message: 'Invalid client' });
        }

        const { name, email, password, phoneNumber } = req.body;

        // Check if user already exists
        const existingUser = await User.findOne({ email });
        if (existingUser) {
            return res.status(400).json({ message: 'User already exists' });
        }

        // Check if phone number already exists
        const existingPhone = await User.findOne({ phoneNumber });
        if (existingPhone) {
            return res.status(400).json({ message: 'Phone number already in use' });
        }

        // Hash password
        const hashedPassword = await bcrypt.hash(password, 12);

        // Create new user
        const newUser = await User.create({
            name,
            email,
            phoneNumber,
            password: hashedPassword,
            settings: {
                googleCalendarSync: false,
                autoCallRecording: false
            },
            permissions: {
                microphone: false,
                contacts: false
            }
        });

        // Generate JWT
        const token = jwt.sign({ id: newUser._id }, JWT_SECRET, { expiresIn: ACCESS_TOKEN_TTL });
        const refreshToken = await tryIssueRefreshToken(String(newUser._id), client);

        res.status(201).json({
            token,
            ...(refreshToken !== undefined && { refreshToken }),
            user: {
                id: newUser._id,
                name: newUser.name,
                email: newUser.email,
                phoneNumber: newUser.phoneNumber,
                settings: newUser.settings,
                permissions: newUser.permissions
            }
        });
    } catch (error) {
        console.error('Signup error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};

export const login = async (req: Request, res: Response) => {
    try {
        const client = parseClient(req.body.client);
        if (!client) {
            return res.status(400).json({ message: 'Invalid client' });
        }

        const { email, password } = req.body;

        // Find user
        const user = await User.findOne({ email });
        if (!user || !user.password) {
            return res.status(401).json({ message: 'Invalid email or password' });
        }

        // Check password
        const isMatch = await bcrypt.compare(password, user.password);
        if (!isMatch) {
            return res.status(401).json({ message: 'Invalid email or password' });
        }

        // Generate JWT
        const token = jwt.sign({ id: user._id }, JWT_SECRET, { expiresIn: ACCESS_TOKEN_TTL });
        const refreshToken = await tryIssueRefreshToken(String(user._id), client);

        res.status(200).json({
            token,
            ...(refreshToken !== undefined && { refreshToken }),
            user: {
                id: user._id,
                name: user.name,
                email: user.email,
                phoneNumber: user.phoneNumber,
                settings: user.settings,
                permissions: user.permissions,
                profilePicture: user.profilePicture
            }
        });
    } catch (error) {
        console.error('Login error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};

import { AuthRequest } from '../middleware/authMiddleware';

export const updateProfile = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user?.id;
        const { name, phoneNumber, password, profilePicture } = req.body;

        const user = await User.findById(userId);
        if (!user) {
            return res.status(404).json({ message: 'User not found' });
        }

        if (phoneNumber && phoneNumber !== user.phoneNumber) {
            const phoneExists = await User.findOne({ phoneNumber });
            if (phoneExists) {
                return res.status(400).json({ message: 'Phone number already in use' });
            }
            user.phoneNumber = phoneNumber;
        }

        if (name) user.name = name;
        if (profilePicture !== undefined) user.profilePicture = profilePicture;

        if (password) {
            user.password = await bcrypt.hash(password, 12);
        }

        await user.save();

        res.status(200).json({
            message: 'Profile updated successfully',
            user: {
                id: user._id,
                name: user.name,
                email: user.email,
                phoneNumber: user.phoneNumber,
                settings: user.settings,
                permissions: user.permissions,
                profilePicture: user.profilePicture
            }
        });
    } catch (error) {
        console.error('Update profile error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};

/**
 * A refresh-token write failure (e.g. a Mongo hiccup on the new collection) must not
 * turn a working login/signup into a 500 for every user — that would be a brand-new
 * failure mode on the most critical path. Degrade to the pre-branch response instead:
 * just the access token, with the failure logged for follow-up.
 */
const tryIssueRefreshToken = async (
    userId: string,
    client: RefreshClient
): Promise<string | undefined> => {
    try {
        return await issueRefreshToken(userId, client);
    } catch (error) {
        console.error('Failed to issue refresh token:', error);
        return undefined;
    }
};

/** Anything other than the two known clients is a bad request, not a silent default. */
const parseClient = (value: unknown): RefreshClient | null => {
    if (value === undefined || value === null || value === 'web') return 'web';
    if (value === 'native') return 'native';
    return null;
};

export const refresh = async (req: Request, res: Response) => {
    try {
        const { refreshToken } = req.body ?? {};

        if (!refreshToken || typeof refreshToken !== 'string') {
            return res.status(400).json({ message: 'refreshToken is required' });
        }

        const client = parseClient(req.body.client);
        if (!client) {
            return res.status(400).json({ message: 'Invalid client' });
        }

        const rotated = await rotateRefreshToken(refreshToken, client);
        if (!rotated) {
            return res.status(401).json({ message: 'Invalid refresh token' });
        }

        const token = jwt.sign({ id: rotated.userId }, JWT_SECRET, {
            expiresIn: ACCESS_TOKEN_TTL,
        });

        return res.status(200).json({ token, refreshToken: rotated.refreshToken });
    } catch (error) {
        console.error('Refresh error:', error);
        return res.status(500).json({ message: 'Internal server error' });
    }
};

export const logout = async (req: Request, res: Response) => {
    try {
        const { refreshToken } = req.body ?? {};

        if (refreshToken && typeof refreshToken === 'string') {
            await revokeRefreshToken(refreshToken);
        }

        // 204 whenever revocation completes or there was nothing to revoke: a logout
        // with nothing to revoke has still achieved its goal, and failing it would
        // strand the UI on a session it has already discarded.
        return res.status(204).send();
    } catch (error) {
        console.error('Logout error:', error);
        return res.status(500).json({ message: 'Internal server error' });
    }
};

/**
 * Mints the native client's pair. The WebView is the only thing that can perform a
 * login, so it provisions the background uploader's credentials on its behalf.
 */
export const deviceToken = async (req: AuthRequest, res: Response) => {
    try {
        const userId = req.user!.id;
        const refreshToken = await issueRefreshToken(userId, 'native');
        const token = jwt.sign({ id: userId }, JWT_SECRET, { expiresIn: ACCESS_TOKEN_TTL });

        return res.status(200).json({ token, refreshToken });
    } catch (error) {
        console.error('Device token error:', error);
        return res.status(500).json({ message: 'Internal server error' });
    }
};
