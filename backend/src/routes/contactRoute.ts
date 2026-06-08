import { Router } from 'express';
import { getContacts, getContactById } from '../controllers/contactController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

router.get('/contacts', protect, getContacts);
router.get('/contacts/:id', protect, getContactById);

export default router;
