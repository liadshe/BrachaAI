import { Router } from 'express';
import { handleIncomingAndroidCall, getCalls, bulkDeleteCalls } from '../controllers/callController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

// This defines the /api/calls endpoint
// Since index.ts uses app.use('/api', callRoutes),
// this becomes http://localhost:3000/api/calls
router.post('/calls', protect, handleIncomingAndroidCall);
router.get('/calls', protect, getCalls);
router.post('/calls/bulk-delete', protect, bulkDeleteCalls);

export default router;