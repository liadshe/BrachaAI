"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.deviceToken = exports.logout = exports.refresh = exports.updateProfile = exports.getProfile = exports.login = exports.signup = void 0;
const bcryptjs_1 = __importDefault(require("bcryptjs"));
const jsonwebtoken_1 = __importDefault(require("jsonwebtoken"));
const User_1 = __importDefault(require("../models/User"));
const jwt_1 = require("../config/jwt");
const refreshTokenService_1 = require("../services/refreshTokenService");
const signup = async (req, res) => {
    try {
        // Validated before any DB work so a bad client costs no round-trips.
        const client = parseClient(req.body.client);
        if (!client) {
            return res.status(400).json({ message: 'Invalid client' });
        }
        const { name, email, password, phoneNumber, businessDescription } = req.body;
        // Check if user already exists
        const existingUser = await User_1.default.findOne({ email });
        if (existingUser) {
            return res.status(400).json({ message: 'User already exists' });
        }
        // Check if phone number already exists
        const existingPhone = await User_1.default.findOne({ phoneNumber });
        if (existingPhone) {
            return res.status(400).json({ message: 'Phone number already in use' });
        }
        // Hash password
        const hashedPassword = await bcryptjs_1.default.hash(password, 12);
        // Create new user
        const newUser = await User_1.default.create({
            name,
            email,
            phoneNumber,
            password: hashedPassword,
            businessDescription: businessDescription ?? '',
            settings: {
                autoCallRecording: false
            },
            permissions: {
                microphone: false,
                contacts: false
            }
        });
        // Generate JWT
        const token = jsonwebtoken_1.default.sign({
            id: newUser._id,
            name: newUser.name,
            email: newUser.email,
            phoneNumber: newUser.phoneNumber,
            businessDescription: newUser.businessDescription,
            profilePicture: newUser.profilePicture
        }, jwt_1.JWT_SECRET, { expiresIn: jwt_1.ACCESS_TOKEN_TTL });
        const refreshToken = await tryIssueRefreshToken(String(newUser._id), client);
        res.status(201).json({
            token,
            ...(refreshToken !== undefined && { refreshToken }),
            user: {
                id: newUser._id,
                name: newUser.name,
                email: newUser.email,
                phoneNumber: newUser.phoneNumber,
                businessDescription: newUser.businessDescription,
                settings: newUser.settings,
                permissions: newUser.permissions,
                profilePicture: newUser.profilePicture
            }
        });
    }
    catch (error) {
        console.error('Signup error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.signup = signup;
const login = async (req, res) => {
    try {
        const client = parseClient(req.body.client);
        if (!client) {
            return res.status(400).json({ message: 'Invalid client' });
        }
        const { email, password } = req.body;
        // Find user
        const user = await User_1.default.findOne({ email });
        if (!user || !user.password) {
            return res.status(401).json({ message: 'Invalid email or password' });
        }
        // Check password
        const isMatch = await bcryptjs_1.default.compare(password, user.password);
        if (!isMatch) {
            return res.status(401).json({ message: 'Invalid email or password' });
        }
        // Generate JWT
        const token = jsonwebtoken_1.default.sign({
            id: user._id,
            name: user.name,
            email: user.email,
            phoneNumber: user.phoneNumber,
            businessDescription: user.businessDescription,
            profilePicture: user.profilePicture
        }, jwt_1.JWT_SECRET, { expiresIn: jwt_1.ACCESS_TOKEN_TTL });
        const refreshToken = await tryIssueRefreshToken(String(user._id), client);
        res.status(200).json({
            token,
            ...(refreshToken !== undefined && { refreshToken }),
            user: {
                id: user._id,
                name: user.name,
                email: user.email,
                phoneNumber: user.phoneNumber,
                businessDescription: user.businessDescription,
                settings: user.settings,
                permissions: user.permissions,
                profilePicture: user.profilePicture
            }
        });
    }
    catch (error) {
        console.error('Login error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.login = login;
const getProfile = async (req, res) => {
    try {
        const userId = req.user?.id;
        const user = await User_1.default.findById(userId);
        if (!user) {
            return res.status(404).json({ message: 'User not found' });
        }
        res.status(200).json({
            user: {
                id: user._id,
                name: user.name,
                email: user.email,
                phoneNumber: user.phoneNumber,
                businessDescription: user.businessDescription,
                settings: user.settings,
                permissions: user.permissions,
                profilePicture: user.profilePicture
            }
        });
    }
    catch (error) {
        console.error('Get profile error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.getProfile = getProfile;
const updateProfile = async (req, res) => {
    try {
        const userId = req.user?.id;
        const { name, phoneNumber, password, profilePicture, businessDescription, settings } = req.body;
        const user = await User_1.default.findById(userId);
        if (!user) {
            return res.status(404).json({ message: 'User not found' });
        }
        if (phoneNumber && phoneNumber !== user.phoneNumber) {
            const phoneExists = await User_1.default.findOne({ phoneNumber });
            if (phoneExists) {
                return res.status(400).json({ message: 'Phone number already in use' });
            }
            user.phoneNumber = phoneNumber;
        }
        if (name)
            user.name = name;
        if (profilePicture !== undefined)
            user.profilePicture = profilePicture;
        if (businessDescription !== undefined)
            user.businessDescription = businessDescription;
        if (settings !== undefined)
            user.settings = {
                ...user.settings,
                ...settings
            };
        if (password) {
            user.password = await bcryptjs_1.default.hash(password, 12);
        }
        await user.save();
        const token = jsonwebtoken_1.default.sign({
            id: user._id,
            name: user.name,
            email: user.email,
            phoneNumber: user.phoneNumber,
            businessDescription: user.businessDescription,
            profilePicture: user.profilePicture
        }, jwt_1.JWT_SECRET, { expiresIn: jwt_1.ACCESS_TOKEN_TTL });
        res.status(200).json({
            message: 'Profile updated successfully',
            token,
            user: {
                id: user._id,
                name: user.name,
                email: user.email,
                phoneNumber: user.phoneNumber,
                businessDescription: user.businessDescription,
                settings: user.settings,
                permissions: user.permissions,
                profilePicture: user.profilePicture
            }
        });
    }
    catch (error) {
        console.error('Update profile error:', error);
        res.status(500).json({ message: 'Internal server error' });
    }
};
exports.updateProfile = updateProfile;
/**
 * A refresh-token write failure (e.g. a Mongo hiccup on the new collection) must not
 * turn a working login/signup into a 500 for every user — that would be a brand-new
 * failure mode on the most critical path. Degrade to the pre-branch response instead:
 * just the access token, with the failure logged for follow-up.
 */
const tryIssueRefreshToken = async (userId, client) => {
    try {
        return await (0, refreshTokenService_1.issueRefreshToken)(userId, client);
    }
    catch (error) {
        console.error('Failed to issue refresh token:', error);
        return undefined;
    }
};
/** Anything other than the two known clients is a bad request, not a silent default. */
const parseClient = (value) => {
    if (value === undefined || value === null || value === 'web')
        return 'web';
    if (value === 'native')
        return 'native';
    return null;
};
const refresh = async (req, res) => {
    try {
        const { refreshToken } = req.body ?? {};
        if (!refreshToken || typeof refreshToken !== 'string') {
            return res.status(400).json({ message: 'refreshToken is required' });
        }
        const client = parseClient(req.body.client);
        if (!client) {
            return res.status(400).json({ message: 'Invalid client' });
        }
        const rotated = await (0, refreshTokenService_1.rotateRefreshToken)(refreshToken, client);
        if (!rotated) {
            return res.status(401).json({ message: 'Invalid refresh token' });
        }
        const token = jsonwebtoken_1.default.sign({ id: rotated.userId }, jwt_1.JWT_SECRET, {
            expiresIn: jwt_1.ACCESS_TOKEN_TTL,
        });
        return res.status(200).json({ token, refreshToken: rotated.refreshToken });
    }
    catch (error) {
        console.error('Refresh error:', error);
        return res.status(500).json({ message: 'Internal server error' });
    }
};
exports.refresh = refresh;
const logout = async (req, res) => {
    try {
        const { refreshToken } = req.body ?? {};
        if (refreshToken && typeof refreshToken === 'string') {
            await (0, refreshTokenService_1.revokeRefreshToken)(refreshToken);
        }
        // 204 whenever revocation completes or there was nothing to revoke: a logout
        // with nothing to revoke has still achieved its goal, and failing it would
        // strand the UI on a session it has already discarded.
        return res.status(204).send();
    }
    catch (error) {
        console.error('Logout error:', error);
        return res.status(500).json({ message: 'Internal server error' });
    }
};
exports.logout = logout;
/**
 * Mints the native client's pair. The WebView is the only thing that can perform a
 * login, so it provisions the background uploader's credentials on its behalf.
 */
const deviceToken = async (req, res) => {
    try {
        const userId = req.user.id;
        const refreshToken = await (0, refreshTokenService_1.issueRefreshToken)(userId, 'native');
        const token = jsonwebtoken_1.default.sign({ id: userId }, jwt_1.JWT_SECRET, { expiresIn: jwt_1.ACCESS_TOKEN_TTL });
        return res.status(200).json({ token, refreshToken });
    }
    catch (error) {
        console.error('Device token error:', error);
        return res.status(500).json({ message: 'Internal server error' });
    }
};
exports.deviceToken = deviceToken;
