import { Router } from 'express';
import { signup, login, updateProfile } from '../controllers/authController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

router.post('/signup', signup);
router.post('/login', login);
router.put('/profile', protect, updateProfile);

export default router;

