import { Router } from 'express';
import { getBriefings, getBriefingByContactId } from '../controllers/briefingController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

router.get('/briefings', protect, getBriefings);
router.get('/briefings/:contactId', protect, getBriefingByContactId);

export default router;
