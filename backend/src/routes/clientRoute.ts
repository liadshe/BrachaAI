import { Router } from 'express';
import { getClients } from '../controllers/clientController';

const router = Router();

router.get('/clients', getClients);

export default router;
