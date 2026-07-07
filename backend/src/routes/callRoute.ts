import { Router } from 'express';
import { handleIncomingAndroidCall, getCalls } from '../controllers/callController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

// POST /api/calls -> PUBLIC (So the Android app can send data)
router.post('/calls', handleIncomingAndroidCall);

// GET /api/calls -> PROTECTED (So only logged-in users can see calls)
router.get('/calls', protect, getCalls);

export default router;
