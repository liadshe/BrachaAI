import { Router } from 'express';
import { getContacts, getContactById, deleteContact } from '../controllers/contactController';
import { protect } from '../middleware/authMiddleware';

const router = Router();

router.get('/contacts', protect, getContacts);
router.get('/contacts/:id', protect, getContactById);
router.delete('/contacts/:id', protect, deleteContact);

export default router;
