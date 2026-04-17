import { Router } from 'express';
import { handleIncomingAndroidCall } from '../controllers/callController';

const router = Router();

// This defines the /api/calls endpoint
// Since index.ts uses app.use('/api', callRoutes), 
// this becomes http://localhost:3000/api/calls
router.post('/calls', handleIncomingAndroidCall);

export default router;