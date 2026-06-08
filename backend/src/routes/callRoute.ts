import { Router } from 'express';
import { handleIncomingAndroidCall, getCalls } from '../controllers/callController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

// This defines the /api/calls endpoint
// Since index.ts uses app.use('/api', callRoutes), 
// this becomes http://localhost:3000/api/calls
router.post('/calls', handleIncomingAndroidCall);
router.get('/calls', protect, getCalls);

export default router;