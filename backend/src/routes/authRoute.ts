import { Router } from 'express';
import {
    signup,
    login,
    getProfile,
    updateProfile,
    refresh,
    logout,
    deviceToken,
} from '../controllers/authController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

router.post('/signup', signup);
router.post('/login', login);
router.post('/refresh', refresh);
router.post('/logout', logout);
router.post('/device-token', protect, deviceToken);
router.get('/profile', protect, getProfile);
router.put('/profile', protect, updateProfile);

export default router;
